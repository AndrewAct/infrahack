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
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.RecordTooLargeException;
import static org.junit.jupiter.api.Assertions.*;

/** Real broker + OpenSearch checks of continuation and the quarantine-ack / source-commit boundary. */
final class QuarantineIntegration {
    static void verify(String endpoint, String index, String aloTopic, String namespace, ObjectNode summary) throws Exception {
        var alo = KafkaPipelineTest.read(aloTopic, "read_committed");
        assertEquals(2, alo.size());
        ObjectNode first = (ObjectNode) PaymentJson.JSON.readTree(alo.getFirst().value());
        ObjectNode second = (ObjectNode) PaymentJson.JSON.readTree(alo.get(1).value());
        assertEquals(first.get("case_id"), second.get("case_id"));
        assertNotEquals(first.get("knowledge_time"), second.get("knowledge_time"));
        assertEquals(KafkaPipelineTest.logical(first.toString()), KafkaPipelineTest.logical(second.toString()));

        // The actual ALO re-emission goes through the indexer, followed by an ordinary valid case.
        ObjectNode following = first.deepCopy();
        following.put("case_id", "case_" + "3".repeat(64));
        KafkaPipelineTest.publish(aloTopic, List.of(following.toString()));
        String conflicts = aloTopic + "-conflicts", group = namespace + "-alo-indexer";
        KafkaPipelineTest.createTopics(conflicts);
        var indexer = new CaseIndexer(URI.create(endpoint), index);
        indexer.consume(KafkaPipelineTest.bootstrap(), aloTopic, group, 3);
        assertEquals(2, indexer.stats().created());
        assertEquals(1, indexer.stats().conflicts());
        assertEquals(1, indexer.stats().quarantined());
        assertOffset(group, aloTopic, 3L);
        var quarantined = KafkaPipelineTest.read(conflicts, "read_committed");
        assertEquals(1, quarantined.size());
        JsonNode event = PaymentJson.JSON.readTree(quarantined.getFirst().value());
        assertEquals(event.path("conflict_id").asText(), quarantined.getFirst().key());
        assertEquals("case-index-conflict-v1", event.path("schema_version").asText());
        assertEquals(aloTopic, event.at("/source/topic").asText());
        assertEquals(alo.get(1).offset(), event.at("/source/offset").asLong());
        assertEquals(0, event.at("/source/partition").asInt());
        assertEquals(alo.get(1).key(), event.at("/source/key").asText());
        assertTrue(CaseIndexer.equivalent(first, event.path("stored_case")));
        assertTrue(CaseIndexer.equivalent(second, event.path("incoming_case")));
        assertStored(endpoint, index, first);
        assertStored(endpoint, index, following);
        KafkaPipelineTest.saveRows("quarantine-actual-alo", quarantined);

        // Reject publication at the broker, then recover. No source offset may advance before the ack.
        String rejectedSource = namespace + "-rejected-source", rejectedTopic = namespace + "-restricted-conflicts";
        String rejectedGroup = namespace + "-rejected-indexer";
        KafkaPipelineTest.createTopics(rejectedSource);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KafkaPipelineTest.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(rejectedTopic, 1, (short) 1)
                .configs(Map.of("max.message.bytes", "128")))).all().get(10, TimeUnit.SECONDS);
        }
        ObjectNode changed = first.deepCopy(), normal = first.deepCopy();
        ((ObjectNode) changed.path("features")).put("attempt_count_10m", 999);
        normal.put("case_id", "case_" + "4".repeat(64));
        KafkaPipelineTest.publish(rejectedSource, List.of(changed.toString(), normal.toString()));
        var rejected = new CaseIndexer(URI.create(endpoint), index);
        Exception failure = assertThrows(Exception.class, () -> rejected.consume(
            KafkaPipelineTest.bootstrap(), rejectedSource, rejectedGroup, 2, rejectedTopic));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(RecordTooLargeException.class, cause);
        Files.writeString(KafkaPipelineTest.evidence().resolve("quarantine-rejected-error.txt"), failure.toString());
        assertEquals(0, rejected.stats().quarantined());
        assertOffset(rejectedGroup, rejectedSource, null);
        assertTrue(KafkaPipelineTest.read(rejectedTopic, "read_committed").isEmpty());
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KafkaPipelineTest.bootstrap()))) {
            var resource = new ConfigResource(ConfigResource.Type.TOPIC, rejectedTopic);
            admin.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(
                new ConfigEntry("max.message.bytes", "1048576"), AlterConfigOp.OpType.SET)))).all().get(10, TimeUnit.SECONDS);
        }
        var recovered = new CaseIndexer(URI.create(endpoint), index);
        recovered.consume(KafkaPipelineTest.bootstrap(), rejectedSource, rejectedGroup, 2, rejectedTopic);
        assertEquals(1, recovered.stats().quarantined());
        assertOffset(rejectedGroup, rejectedSource, 2L);
        assertStored(endpoint, index, first);
        assertStored(endpoint, index, normal);
        KafkaPipelineTest.saveRows("quarantine-after-publication-recovery", KafkaPipelineTest.read(rejectedTopic, "read_committed"));

        // Quarantine ack succeeds, then a different permanent bulk item fails before source commit.
        // Reprocessing the uncommitted conflict emits the same identity and body, with no silent loss.
        String replaySource = namespace + "-quarantine-replay", replayGroup = namespace + "-quarantine-replay-indexer";
        String replayTopic = replaySource + "-conflicts";
        KafkaPipelineTest.createTopics(replaySource, replayTopic);
        ObjectNode bad = first.deepCopy();
        bad.put("case_id", "case_" + "5".repeat(64));
        ((ObjectNode) bad.path("features")).put("attempt_count_10m", "invalid-count");
        KafkaPipelineTest.publish(replaySource, List.of(changed.toString(), bad.toString()));
        var beforeCommitFailure = new CaseIndexer(URI.create(endpoint), index);
        assertThrows(IOException.class, () -> beforeCommitFailure.consume(KafkaPipelineTest.bootstrap(), replaySource, replayGroup, 2));
        assertEquals(1, beforeCommitFailure.stats().quarantined());
        assertOffset(replayGroup, replaySource, null);
        assertEquals(1, KafkaPipelineTest.read(replayTopic, "read_committed").size());
        var replayed = new CaseIndexer(URI.create(endpoint), index);
        replayed.consume(KafkaPipelineTest.bootstrap(), replaySource, replayGroup, 1);
        assertEquals(1, replayed.stats().quarantined());
        assertOffset(replayGroup, replaySource, 1L);
        var replayRows = KafkaPipelineTest.read(replayTopic, "read_committed");
        assertEquals(2, replayRows.size());
        assertEquals(replayRows.get(0).key(), replayRows.get(1).key());
        assertEquals(replayRows.get(0).value(), replayRows.get(1).value());
        assertStored(endpoint, index, first);
        KafkaPipelineTest.saveRows("quarantine-ack-before-commit-replay", replayRows);
        summary.put("alo_index_created", indexer.stats().created()).put("alo_index_conflicts", indexer.stats().conflicts())
            .put("alo_index_quarantined", indexer.stats().quarantined()).put("case_after_conflict_indexed", true)
            .put("quarantine_rejection_offsets_uncommitted", true).put("quarantine_publication_recovered", true)
            .put("quarantine_ack_before_commit_replay_records", replayRows.size()).put("quarantine_replay_unique_ids", 1);
    }

    private static void assertOffset(String group, String topic, Long expected) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KafkaPipelineTest.bootstrap()))) {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(topic, 0));
            if (expected == null) assertNull(offset, "source batch must remain uncommitted");
            else { assertNotNull(offset); assertEquals(expected.longValue(), offset.offset()); }
        }
    }

    private static void assertStored(String endpoint, String index, ObjectNode expected) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(endpoint + "/" + index + "/_doc/" + expected.path("case_id").asText()))
                .timeout(Duration.ofSeconds(10)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            JsonNode actual = PaymentJson.JSON.readTree(response.body());
            assertEquals(1, actual.path("_version").asInt());
            assertTrue(CaseIndexer.equivalent(expected, actual.path("_source")));
        }
    }
}
