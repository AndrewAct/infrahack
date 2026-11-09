package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** One recovery scenario, two sink guarantees, and one raw replay. Broker is owned by kafka-check.sh. */
@EnabledIfSystemProperty(named = "kafka.bootstrap", matches = ".+")
class KafkaPipelineTest {
    static String bootstrap() { return System.getProperty("kafka.bootstrap"); }
    static Path evidence() throws Exception { return CepIntegrationTest.results(); }
    record Run(String topic, List<ConsumerRecord<String, String>> committed) {}

    @Test @Timeout(150) void pendingCepRestoreAndCaseProjection() throws Exception {
        String namespace = "cep-" + UUID.randomUUID().toString().substring(0, 8);
        var eos = live(namespace + "-eos", DeliveryGuarantee.EXACTLY_ONCE);
        var alo = live(namespace + "-alo", DeliveryGuarantee.AT_LEAST_ONCE);
        assertEquals(logical(eos.committed().getFirst().value()), logical(alo.committed().getFirst().value()));

        String replayTopic = namespace + "-replay-cases";
        createTopics(replayTopic);
        var env = KafkaPaymentCepJob.environment();
        var input = env.fromSource(KafkaPaymentCepJob.source(bootstrap(), namespace + "-eos-raw", namespace + "-replay", true),
            KafkaPaymentCepJob.watermarks(45_000), "raw-replay").uid("kafka-payments-v1");
        var streams = PaymentCep.build(input, Timelines.BASE - 600_000, namespace + "-replay");
        streams.cases().sinkTo(KafkaPaymentCepJob.sink(bootstrap(), replayTopic, namespace + "-replay", DeliveryGuarantee.EXACTLY_ONCE))
            .uid("kafka-cases-v1");
        env.execute("bounded raw replay");
        var replay = read(replayTopic, "read_committed");
        assertEquals(1, replay.size());
        assertEquals(logical(eos.committed().getFirst().value()), logical(replay.getFirst().value()));
        saveRows("raw-replay", replay);

        var summary = PaymentJson.JSON.createObjectNode().put("exactly_once_committed", eos.committed().size())
            .put("at_least_once_committed", alo.committed().size()).put("raw_replay_committed", replay.size())
            .put("pending_cep_restored", true).put("live_replay_logical_equal", true)
            .put("scope", "one local broker; one source partition; parallelism 1; task failure, not broker/node loss");
        if (System.getProperty("opensearch.endpoint") != null) {
            IndexerIntegration.verify(eos.topic(), eos.committed().getFirst(), namespace, summary, alo.topic());
        }
        PaymentJson.JSON.writerWithDefaultPrettyPrinter().writeValue(evidence().resolve("kafka-summary.json").toFile(), summary);
    }

    private Run live(String namespace, DeliveryGuarantee guarantee) throws Exception {
        String raw = namespace + "-raw", cases = namespace + "-cases";
        createTopics(raw, cases);
        Path prefix = evidence().resolve(namespace + "-prefix-checkpoint.txt");
        Path failure = evidence().resolve(namespace + "-failure.txt");
        Path restored = evidence().resolve(namespace + "-restored-inputs.txt");
        var env = KafkaPaymentCepJob.environment();
        env.getConfig().setAutoWatermarkInterval(100);
        var input = env.fromSource(KafkaPaymentCepJob.source(bootstrap(), raw, namespace, false),
                KafkaPaymentCepJob.watermarks(45_000), "raw-live").uid("kafka-payments-v1")
            .transform("observe-prefix-checkpoint", Types.STRING, new PrefixCheckpoint(prefix.toString(), restored.toString()));
        var streams = PaymentCep.build(input, Timelines.BASE - 600_000, namespace);
        streams.cases().process(new FailAfterVisibleCase(bootstrap(), cases, failure.toString())).uid("test-case-failure")
            .sinkTo(KafkaPaymentCepJob.sink(bootstrap(), cases, namespace, guarantee)).uid("kafka-cases-v1");
        streams.rejected().print("rejected");
        streams.cepLate().map(p -> p.eventId).print("unexpected-cep-late");
        var job = env.executeAsync("business CEP restore: " + guarantee);
        try {
            var sequence = Timelines.sequence("recovery", "FFFS", new long[] {0, 30_000, 60_000, 90_000});
            var prefixRows = List.of(sequence.get(0), sequence.get(2), sequence.get(1), sequence.get(1),
                Timelines.event("frontier_before_s", "frontier", 110_000, 0, false));
            var suffixRows = List.of(sequence.get(3), Timelines.event("frontier_after_s", "frontier", 140_000, 1, false));
            var all = new ArrayList<>(prefixRows); all.addAll(suffixRows);
            Files.write(evidence().resolve(namespace + "-input.jsonl"), all);
            publish(raw, prefixRows);
            awaitFile(prefix); // Completed whole-job checkpoint with FFF finalized, but no S yet.
            publish(raw, suffixRows);
            awaitFile(failure);
            int expected = guarantee == DeliveryGuarantee.EXACTLY_ONCE ? 1 : 2;
            List<ConsumerRecord<String, String>> committed = List.of();
            long until = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < until) {
                committed = read(cases, "read_committed");
                if (committed.size() >= expected) break;
                Thread.sleep(100);
            }
            assertEquals(expected, committed.size(), namespace + " committed count");
            assertTrue(Files.exists(restored), "source/operator task restored from checkpoint");
            String restoredRows = Files.readString(restored);
            assertTrue(restoredRows.contains("recovery_3"));
            for (String id : List.of("recovery_0", "recovery_1", "recovery_2")) assertFalse(restoredRows.contains(id), "FFF must not be reread");
            var oracle = ScanOracle.evaluate(new Timelines.Trace("recovery", Timelines.BASE - 600_000, all));
            for (var r : committed) {
                var doc = (ObjectNode) PaymentJson.JSON.readTree(r.value());
                CepIntegrationTest.assertCases(namespace, oracle.cases(), new ArrayList<>(List.of(doc)));
                assertEquals(new String(KafkaPaymentCepJob.accountKey(doc.path("tenant_id").asText(), doc.path("account_id").asText()),
                    java.nio.charset.StandardCharsets.UTF_8), r.key());
            }
            for (var r : committed) assertEquals(logical(committed.getFirst().value()), logical(r.value()));
            var physical = read(cases, "read_uncommitted");
            assertEquals(2, physical.size(), "both attempts reached Kafka; EOS hides the aborted one");
            saveRows(namespace + "-committed", committed);
            saveRows(namespace + "-uncommitted", physical);
            return new Run(cases, committed);
        } finally { job.cancel().get(15, TimeUnit.SECONDS); }
    }

    static ObjectNode logical(String value) throws Exception {
        ObjectNode n = (ObjectNode) PaymentJson.JSON.readTree(value);
        n.remove(List.of("case_id", "run_id", "knowledge_time"));
        return n;
    }
    static void saveRows(String name, List<ConsumerRecord<String, String>> rows) throws Exception {
        var out = new ArrayList<ObjectNode>();
        for (var r : rows) {
            var n = PaymentJson.JSON.createObjectNode().put("partition", r.partition()).put("offset", r.offset()).put("key", r.key());
            n.set("case", PaymentJson.JSON.readTree(r.value())); out.add(n);
        }
        PaymentJson.JSON.writerWithDefaultPrettyPrinter().writeValue(evidence().resolve(name + ".json").toFile(), out);
    }
    static void createTopics(String... names) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap()))) {
            admin.createTopics(java.util.Arrays.stream(names).map(n -> new NewTopic(n, 1, (short) 1)).toList()).all().get(10, TimeUnit.SECONDS);
        }
    }
    static void publish(String topic, List<String> values) throws Exception {
        var config = new Properties();
        config.put("bootstrap.servers", bootstrap()); config.put("acks", "all");
        config.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (var producer = new KafkaProducer<byte[], String>(config)) {
            for (String value : values) {
                var n = PaymentJson.JSON.readTree(value);
                producer.send(new ProducerRecord<>(topic, KafkaPaymentCepJob.accountKey(n.path("tenant_id").asText(), n.path("account_id").asText()), value)).get(10, TimeUnit.SECONDS);
            }
        }
    }
    static List<ConsumerRecord<String, String>> read(String topic, String isolation) throws Exception {
        var config = new Properties();
        config.put("bootstrap.servers", bootstrap()); config.put("isolation.level", isolation);
        config.put("enable.auto.commit", "false");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            var tp = new TopicPartition(topic, 0);
            consumer.assign(List.of(tp)); consumer.seekToBeginning(List.of(tp));
            long end = consumer.endOffsets(List.of(tp)).get(tp);
            var rows = new ArrayList<ConsumerRecord<String, String>>();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (consumer.position(tp) < end && System.nanoTime() < until) consumer.poll(Duration.ofMillis(100)).forEach(rows::add);
            assertTrue(consumer.position(tp) >= end, "reader caught up");
            return rows;
        }
    }
    static void awaitFile(Path path) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!Files.exists(path) && System.nanoTime() < until) Thread.sleep(100);
        assertTrue(Files.exists(path), "timed out: " + path);
    }

    static final class PrefixCheckpoint extends AbstractStreamOperator<String> implements OneInputStreamOperator<String, String> {
        private final String prefix, restored;
        private transient boolean recovering, sawSuccess;
        private transient int failures;
        private transient long frontier, candidate;
        PrefixCheckpoint(String prefix, String restored) { this.prefix = prefix; this.restored = restored; }
        @Override public void initializeState(StateInitializationContext ctx) throws Exception {
            super.initializeState(ctx); recovering = ctx.isRestored();
        }
        @Override public void processElement(StreamRecord<String> record) throws Exception {
            var n = PaymentJson.JSON.readTree(record.getValue());
            if (n.path("account_id").asText().equals("recovery")) {
                if (n.at("/payment/authorization_status").asText().equals("authorized")) sawSuccess = true;
                else failures++;
            }
            if (recovering) Files.writeString(Path.of(restored), n.path("event_id").asText() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            output.collect(record);
        }
        @Override public void processWatermark(Watermark mark) throws Exception { frontier = mark.getTimestamp(); super.processWatermark(mark); }
        @Override public void snapshotState(StateSnapshotContext ctx) throws Exception {
            super.snapshotState(ctx);
            if (!recovering && !sawSuccess && failures >= 3 && frontier >= Timelines.BASE + 60_000) candidate = ctx.getCheckpointId();
        }
        @Override public void notifyCheckpointComplete(long id) throws Exception {
            super.notifyCheckpointComplete(id);
            if (candidate == id && Files.notExists(Path.of(prefix))) Files.writeString(Path.of(prefix), "checkpoint=" + id + " watermark=" + frontier + "\n");
        }
    }

    static final class FailAfterVisibleCase extends ProcessFunction<String, String> {
        private final String bootstrap, topic, marker;
        FailAfterVisibleCase(String bootstrap, String topic, String marker) { this.bootstrap = bootstrap; this.topic = topic; this.marker = marker; }
        @Override public void processElement(String value, Context context, Collector<String> out) throws Exception {
            out.collect(value);
            if (Files.notExists(Path.of(marker))) {
                // Block this operator before the next barrier until its first output physically reaches Kafka.
                long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                boolean seen = false;
                while (!seen && System.nanoTime() < until) {
                    seen = read(topic, "read_uncommitted").stream().anyMatch(r -> r.value().equals(value));
                    if (!seen) Thread.sleep(50);
                }
                if (!seen) throw new IllegalStateException("failure hook could not observe first output");
                Files.writeString(Path.of(marker), "case reached Kafka before checkpoint; injecting task failure\n");
                throw new IllegalStateException("injected after case write, before checkpoint");
            }
        }
    }
}
