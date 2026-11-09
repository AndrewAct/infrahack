package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import static org.junit.jupiter.api.Assertions.*;

final class IndexerIntegration {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static void verify(String topic, ConsumerRecord<String, String> record, String namespace, ObjectNode summary, String aloTopic) throws Exception {
        String endpoint = System.getProperty("opensearch.endpoint").replaceAll("/$", "");
        String index = "fraud-article-" + namespace;
        String root = endpoint + "/" + index;
        String mapping;
        try (var in = IndexerIntegration.class.getResourceAsStream("/case-index-mapping.json")) {
            mapping = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Files.writeString(KafkaPipelineTest.evidence().resolve("opensearch-mapping.json"), mapping);
        request("PUT", root, mapping, 200);
        try {
            // Write acknowledgment followed by a simulated crash before the Kafka offset commit.
            var beforeCrash = new CaseIndexer(URI.create(endpoint), index);
            beforeCrash.project(List.of(record.value()));
            assertEquals(1, beforeCrash.stats().created());
            String group = namespace + "-indexer";
            var resumed = new CaseIndexer(URI.create(endpoint), index);
            resumed.consume(KafkaPipelineTest.bootstrap(), topic, group, 1);
            assertEquals(1, resumed.stats().duplicates());
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", KafkaPipelineTest.bootstrap()))) {
                var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                assertEquals(record.offset() + 1, offsets.get(new TopicPartition(topic, 0)).offset());
            }
            // A new consumer group rebuilds from the same immutable case bytes, including knowledge_time.
            var rebuild = new CaseIndexer(URI.create(endpoint), index);
            rebuild.consume(KafkaPipelineTest.bootstrap(), topic, namespace + "-rebuild", 1);
            assertEquals(1, rebuild.stats().duplicates());
            JsonNode original = PaymentJson.JSON.readTree(record.value());
            String docUrl = root + "/_doc/" + original.path("case_id").asText();
            JsonNode stored = PaymentJson.JSON.readTree(request("GET", docUrl, null, 200));
            assertTrue(CaseIndexer.equivalent(original, stored.path("_source")));
            assertEquals(1, stored.path("_version").asInt());

            ObjectNode changed = (ObjectNode) original.deepCopy();
            ((ObjectNode) changed.path("features")).put("attempt_count_10m", 999);
            assertThrows(IOException.class, () -> rebuild.project(List.of(changed.toString())));
            assertEquals(1, rebuild.stats().conflicts());
            JsonNode afterConflict = PaymentJson.JSON.readTree(request("GET", docUrl, null, 200));
            assertTrue(CaseIndexer.equivalent(original, afterConflict.path("_source")));
            assertEquals(1, afterConflict.path("_version").asInt());
            Files.writeString(KafkaPipelineTest.evidence().resolve("stored-case.json"), afterConflict.toPrettyString());
            request("POST", root + "/_refresh", null, 200);
            String search = request("POST", root + "/_search", "{\"query\":{\"match_all\":{}}}", 200);
            assertEquals(1, PaymentJson.JSON.readTree(search).at("/hits/total/value").asInt());
            Files.writeString(KafkaPipelineTest.evidence().resolve("search-visible.json"), search);

            // A partial bulk can create its first item, then fail permanently on its second item.
            // The consumer must not commit past either item; the first safely becomes a duplicate on retry.
            String badTopic = namespace + "-partial-bulk", badGroup = namespace + "-partial-indexer";
            KafkaPipelineTest.createTopics(badTopic);
            ObjectNode good = (ObjectNode) original.deepCopy(), bad = (ObjectNode) original.deepCopy();
            good.put("case_id", "case_" + "1".repeat(64));
            bad.put("case_id", "case_" + "2".repeat(64));
            ((ObjectNode) bad.path("features")).put("attempt_count_10m", "invalid-count");
            KafkaPipelineTest.publish(badTopic, List.of(good.toString(), bad.toString()));
            var partial = new CaseIndexer(URI.create(endpoint), index);
            assertThrows(IOException.class, () -> partial.consume(KafkaPipelineTest.bootstrap(), badTopic, badGroup, 2));
            assertEquals(1, partial.stats().created());
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", KafkaPipelineTest.bootstrap()))) {
                assertTrue(admin.listConsumerGroupOffsets(badGroup).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).isEmpty());
            }
            partial.project(List.of(good.toString()));
            assertEquals(1, partial.stats().duplicates());
            QuarantineIntegration.verify(endpoint, index, aloTopic, namespace, summary);
            summary.put("index_created", beforeCrash.stats().created()).put("index_retry_duplicates", resumed.stats().duplicates())
                .put("index_rebuild_duplicates", rebuild.stats().duplicates()).put("index_conflicts", rebuild.stats().conflicts())
                .put("partial_bulk_offsets_uncommitted", true).put("search_visible_cases_before_partial_fixture", 1)
                .put("opensearch_index", index).put("opensearch_test_index_deleted", true);
        } finally { request("DELETE", root, null, 200); }
    }
    private static String request(String method, String uri, String body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), method + " " + uri + " " + response.body());
        return response.body();
    }
}
