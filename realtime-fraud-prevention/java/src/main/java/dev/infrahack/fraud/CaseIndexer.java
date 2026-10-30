package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded projection. Persist content conflicts before committing; publication/permanent failures stop the batch. */
public final class CaseIndexer {
    private static final Logger LOG = LoggerFactory.getLogger(CaseIndexer.class);
    private static final int MAX_BULK_BYTES = 1_048_576;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI index;
    private long created, duplicates, conflicts, retries, quarantined;

    public record Stats(long created, long duplicates, long conflicts, long retries, long quarantined) {}
    private record Item(JsonNode document, int sourceIndex) {}
    @FunctionalInterface private interface ConflictHandler {
        void persist(Item item, JsonNode stored) throws Exception;
    }
    public CaseIndexer(URI endpoint, String indexName) {
        if (!indexName.matches("[a-z0-9][a-z0-9_-]*")) throw new IllegalArgumentException("invalid index name");
        this.index = URI.create(endpoint.toString().replaceAll("/$", "") + "/" + indexName);
    }
    public Stats stats() { return new Stats(created, duplicates, conflicts, retries, quarantined); }

    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length > 7) throw new IllegalArgumentException("bootstrap case-topic group endpoint index max-records [conflict-topic]");
        var indexer = new CaseIndexer(URI.create(args[3]), args[4]);
        indexer.consume(args[0], args[1], args[2], Integer.parseInt(args[5]), args.length == 7 ? args[6] : args[1] + "-conflicts");
        LOG.info("indexing completed: {}", indexer.stats());
    }

    public void consume(String bootstrap, String topic, String group, int maxRecords) throws Exception {
        consume(bootstrap, topic, group, maxRecords, topic + "-conflicts");
    }

    public void consume(String bootstrap, String topic, String group, int maxRecords, String conflictTopic) throws Exception {
        if (maxRecords < 1) throw new IllegalArgumentException("max-records must be positive");
        if (topic.equals(conflictTopic)) throw new IllegalArgumentException("conflict topic must differ from input topic");
        var config = new Properties();
        config.put("bootstrap.servers", bootstrap);
        config.put("group.id", group);
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("auto.offset.reset", "earliest");
        config.put("enable.auto.commit", "false");
        config.put("isolation.level", "read_committed");
        config.put("max.poll.records", "100");
        var producerConfig = new Properties();
        producerConfig.put("bootstrap.servers", bootstrap);
        producerConfig.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerConfig.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerConfig.put("acks", "all");
        producerConfig.put("enable.idempotence", "true");
        producerConfig.put("max.block.ms", "5000");
        producerConfig.put("request.timeout.ms", "3000");
        producerConfig.put("delivery.timeout.ms", "10000");
        try (var consumer = new KafkaConsumer<String, String>(config);
             var quarantine = new KafkaProducer<String, String>(producerConfig)) {
            consumer.subscribe(List.of(topic));
            int processed = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (processed < maxRecords) {
                var records = consumer.poll(Duration.ofMillis(250));
                var batch = new ArrayList<ConsumerRecord<String, String>>();
                for (var r : records) {
                    if (batch.size() == maxRecords - processed) break;
                    batch.add(r);
                }
                if (batch.isEmpty()) {
                    if (System.nanoTime() >= deadline) throw new IOException("case consumer idle for 30 seconds");
                    continue;
                }
                project(batch.stream().map(ConsumerRecord::value).toList(), (item, stored) ->
                    quarantine(quarantine, conflictTopic, batch.get(item.sourceIndex()), item.document(), stored));
                var offsets = new HashMap<TopicPartition, OffsetAndMetadata>();
                for (var r : batch) offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                consumer.commitSync(offsets);
                processed += batch.size();
                deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            }
        }
    }

    /** At most 100 items / 1 MiB per request. A failed batch may be replayed; successful creates remain immutable. */
    public void project(List<String> values) throws Exception {
        // Fixture/helper calls have no source coordinates or quarantine destination: fail explicitly on conflict.
        project(values, (item, stored) -> { throw new IOException("content conflict requires Kafka quarantine context"); });
    }

    private void project(List<String> values, ConflictHandler handler) throws Exception {
        var batch = new ArrayList<Item>();
        int bytes = 0;
        for (int sourceIndex = 0; sourceIndex < values.size(); sourceIndex++) {
            String value = values.get(sourceIndex);
            JsonNode n = PaymentJson.JSON.readTree(value);
            String id = n.path("case_id").asText();
            if (!id.matches("case_[a-f0-9]{64}")) throw new IOException("invalid case identity");
            int size = action(n).getBytes(StandardCharsets.UTF_8).length;
            if (size > MAX_BULK_BYTES) throw new IOException("case exceeds 1 MiB request bound");
            if (batch.size() == 100 || bytes + size > MAX_BULK_BYTES) { bulk(batch, handler); batch.clear(); bytes = 0; }
            batch.add(new Item(n, sourceIndex));
            bytes += size;
        }
        if (!batch.isEmpty()) bulk(batch, handler);
    }

    private static String action(JsonNode n) {
        return "{\"create\":{\"_id\":\"" + n.path("case_id").asText() + "\"}}\n" + n + "\n";
    }

    private void bulk(List<Item> batch, ConflictHandler handler) throws Exception {
        var pending = new ArrayList<>(batch);
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                if (attempt > 3) throw new IOException("bulk retry budget exhausted; offsets remain uncommitted");
                retries++;
                Thread.sleep(100L << (attempt - 1));
            }
            StringBuilder body = new StringBuilder();
            pending.forEach(item -> body.append(action(item.document())));
            HttpResponse<String> response;
            try { response = request("POST", URI.create(index + "/_bulk"), body.toString()); }
            catch (IOException e) { continue; } // Outcome unknown; create-only replay resolves already written items.
            if (response.statusCode() == 429 || response.statusCode() >= 500) continue;
            if (response.statusCode() != 200) throw new IOException("bulk HTTP " + response.statusCode() + ": " + response.body());
            JsonNode items = PaymentJson.JSON.readTree(response.body()).path("items");
            if (!items.isArray() || items.size() != pending.size()) throw new IOException("invalid bulk item response");
            var retry = new ArrayList<Item>();
            for (int i = 0; i < pending.size(); i++) {
                JsonNode item = items.get(i).path("create"), value = pending.get(i).document();
                int status = item.path("status").asInt();
                if (status == 201) created++;
                else if (status == 409) {
                    var existing = request("GET", URI.create(index + "/_doc/" + value.path("case_id").asText()), null);
                    if (existing.statusCode() != 200) throw new IOException("cannot verify existing case: " + existing.statusCode());
                    JsonNode stored = PaymentJson.JSON.readTree(existing.body()).path("_source");
                    if (!equivalent(value, stored)) {
                        conflicts++;
                        handler.persist(pending.get(i), stored);
                        quarantined++;
                        LOG.error("immutable case conflict quarantined: {}", value.path("case_id").asText());
                    } else duplicates++;
                } else if (status == 429 || status >= 500) retry.add(pending.get(i));
                else throw new IOException("permanent bulk failure: " + item);
            }
            if (retry.isEmpty()) return;
            pending = retry;
        }
    }

    private void quarantine(KafkaProducer<String, String> producer, String topic,
                            ConsumerRecord<String, String> source, JsonNode incoming, JsonNode stored) throws Exception {
        // Stable across consumer restarts and groups; producer idempotence alone does not deduplicate application replays.
        byte[] identity = PaymentJson.JSON.writeValueAsBytes(List.of(index.getPath(), source.topic(), source.partition(), source.offset()));
        String id = "conflict_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        var event = PaymentJson.JSON.createObjectNode().put("schema_version", "case-index-conflict-v1")
            .put("conflict_id", id).put("reason", "immutable_case_content_mismatch").put("projection_index", index.getPath());
        event.putObject("source").put("topic", source.topic()).put("partition", source.partition())
            .put("offset", source.offset()).put("key", source.key());
        event.set("incoming_case", incoming);
        event.set("stored_case", stored);
        producer.send(new ProducerRecord<>(topic, id, event.toString())).get(12, TimeUnit.SECONDS);
    }

    private HttpResponse<String> request(String method, URI uri, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/x-ndjson");
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static boolean equivalent(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            var fields = a.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode other = b.path(field.getKey());
                if (field.getKey().equals("behavior_vector")) {
                    JsonNode vector = field.getValue();
                    if (!other.isArray() || vector.size() != other.size()) return false;
                    for (int i = 0; i < vector.size(); i++) {
                        if (Float.compare((float) vector.get(i).asDouble(), (float) other.get(i).asDouble()) != 0) return false;
                    }
                } else if (!equivalent(field.getValue(), other)) return false;
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equivalent(a.get(i), b.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }
}
