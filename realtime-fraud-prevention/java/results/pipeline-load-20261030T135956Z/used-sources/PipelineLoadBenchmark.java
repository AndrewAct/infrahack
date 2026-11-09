package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;

/** One open-loop load ladder over the real Kafka/CEP/create-only projection. */
public final class PipelineLoadBenchmark {
    static org.apache.flink.configuration.Configuration checkpointConfig(Path directory) {
        return new org.apache.flink.configuration.Configuration()
            .set(org.apache.flink.configuration.CheckpointingOptions.CHECKPOINT_STORAGE, "filesystem")
            .set(org.apache.flink.configuration.CheckpointingOptions.CHECKPOINTS_DIRECTORY, directory.toAbsolutePath().toUri().toString());
    }
    static final int WARMUP_SECONDS = 50, STAGE_SECONDS = 60;
    static final int[] RATES = {100, 1000, 5000};
    static final int TOTAL = (100 * WARMUP_SECONDS + Arrays.stream(RATES).sum() * STAGE_SECONDS) / 4;
    static final AtomicInteger committed = new AtomicInteger(), visible = new AtomicInteger();
    static final AtomicReference<Throwable> error = new AtomicReference<>();
    static final ConcurrentHashMap<Integer, Long> emitted = new ConcurrentHashMap<>(), observed = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Integer, Double> gaps = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Integer, Long> amounts = new ConcurrentHashMap<>();
    static String bootstrap, namespace, rawTopic, caseTopic, index;
    static volatile boolean stop;

    static Properties consumerConfig(String group) {
        var p = new Properties(); p.put("bootstrap.servers", bootstrap); p.put("group.id", group);
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("auto.offset.reset", "earliest"); p.put("enable.auto.commit", "false"); p.put("isolation.level", "read_committed");
        return p;
    }
    static void check(JsonNode doc, Set<Integer> seen) {
        var ids = doc.path("evidence_event_ids");
        String first = ids.get(0).asText(); int episode = Integer.parseInt(first.split("_")[1]);
        if (!seen.add(episode)) throw new AssertionError("duplicate logical case " + episode);
        for (int j = 0; j < 4; j++) if (!ids.get(j).asText().equals("load_" + episode + "_" + j)) throw new AssertionError("wrong witness");
        var f = doc.path("features");
        if (f.path("attempt_count_10m").asInt() != 4 || f.path("failed_attempt_count_10m").asInt() != 3
            || f.path("failure_fraction_10m").asDouble() != .75 || f.path("distinct_device_count_10m").asInt() != 2
            || f.path("distinct_ip_count_10m").asInt() != 3 || f.path("attempted_amount_minor_sum_10m").asLong() != amounts.get(episode)
            || Math.abs(f.path("median_inter_attempt_seconds_10m").asDouble() - gaps.get(episode)) > 1e-9
            || Math.abs(f.path("matched_sequence_duration_seconds").asDouble() - 3 * gaps.get(episode)) > 1e-9
            || !doc.path("feature_completeness").asText().equals("complete") || doc.path("behavior_vector").size() != 8)
            throw new AssertionError("wrong features " + doc);
        // Independent transform of the expected raw fields, rather than calling Features.vector.
        double[] raw = {4, 3, .75, 2, 3, amounts.get(episode), gaps.get(episode), 3 * gaps.get(episode)};
        double[] caps = {100, 100, 1, 20, 20, 1000000, 600, 600};
        for (int j = 0; j < raw.length; j++) {
            double expected = j == 2 ? raw[j] : Math.log1p(Math.min(raw[j], caps[j])) / Math.log1p(caps[j]);
            if (Math.abs(doc.path("behavior_vector").get(j).floatValue() - (float) expected) > 1e-7) throw new AssertionError("wrong vector");
        }
    }
    static Thread observe(Path output) {
        return Thread.ofPlatform().start(() -> {
            var seen = new HashSet<Integer>();
            try (var consumer = new KafkaConsumer<String, String>(consumerConfig(namespace + "-observe"));
                 var samples = Files.newBufferedWriter(output.resolve("committed.jsonl"))) {
                consumer.subscribe(List.of(caseTopic));
                while (!stop && committed.get() < TOTAL) for (var record : consumer.poll(Duration.ofMillis(100))) {
                    var doc = PaymentJson.JSON.readTree(record.value()); check(doc, seen);
                    int episode = Integer.parseInt(doc.path("evidence_event_ids").get(0).asText().split("_")[1]);
                    long now = System.currentTimeMillis(); observed.put(episode, now);
                    samples.write(PaymentJson.JSON.writeValueAsString(Map.of("episode", episode, "observed_ms", now,
                        "emitted_ms", emitted.get(episode), "cutoff", doc.path("feature_cutoff").asText(), "doc", doc)));
                    samples.newLine(); committed.incrementAndGet();
                }
            } catch (Throwable e) { error.compareAndSet(null, e); }
        });
    }
    static Thread indexer(CaseIndexer projection) {
        return Thread.ofPlatform().start(() -> {
            try {
                while (!stop && committed.get() == 0) Thread.sleep(100);
                if (!stop) projection.consume(bootstrap, caseTopic, namespace + "-indexer", TOTAL);
            } catch (Throwable e) { error.compareAndSet(null, e); }
        });
    }
    public static void main(String[] args) throws Exception {
        bootstrap = args[0]; Path output = Path.of(args[1]); Files.createDirectories(output);
        namespace = "load-" + output.getFileName().toString().toLowerCase(Locale.ROOT);
        rawTopic = namespace + "-raw"; caseTopic = namespace + "-cases"; index = "fraud-" + namespace;
        Path retrievalRun = Path.of(args[2]);
        String historicalIndex = index + "-history";
        float[][] historicalQueries = PaymentJson.JSON.readValue(retrievalRun.resolve("queries-100000-256.json").toFile(), float[][].class);
        RetrievalBenchmark.create(historicalIndex, 256);
        try (var vectors = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(retrievalRun.resolve("vectors-100000-256.f32"))))) {
            var batch = new StringBuilder();
            for (int i = 0; i < 100000; i++) {
                var vector = new float[256]; for (int j = 0; j < 256; j++) vector[j] = vectors.readFloat();
                RetrievalBenchmark.append(batch, "c" + i, RetrievalBenchmark.doc("account-" + i, vector));
                if ((i + 1) % 100 == 0) RetrievalBenchmark.bulk(historicalIndex, batch);
            }
        }
        RetrievalBenchmark.call("POST", "/" + historicalIndex + "/_refresh", null);
        RetrievalBenchmark.call("POST", "/" + historicalIndex + "/_forcemerge?max_num_segments=1", null);
        RetrievalBenchmark.save(output.resolve("historical-path.json"), RetrievalBenchmark.path(historicalIndex, historicalQueries[0], 100, false));
        try (var baseline = Files.newBufferedWriter(output.resolve("historical-baseline.jsonl"))) {
            for (int warm = 0; warm < 2; warm++) for (var q : historicalQueries) RetrievalBenchmark.call("POST", "/" + historicalIndex + "/_search", RetrievalBenchmark.query(q, false, 100, false));
            for (int rep = 0; rep < 3; rep++) for (int q = 0; q < historicalQueries.length; q++) {
                long start = System.nanoTime();
                var response = RetrievalBenchmark.call("POST", "/" + historicalIndex + "/_search", RetrievalBenchmark.query(historicalQueries[q], false, 100, false));
                baseline.write(PaymentJson.JSON.writeValueAsString(Map.of("query", q, "millis", (System.nanoTime() - start) / 1e6, "ids", RetrievalBenchmark.ids(response)))); baseline.newLine();
            }
        }
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(rawTopic, 1, (short) 1), new NewTopic(caseTopic, 1, (short) 1),
                new NewTopic(caseTopic + "-conflicts", 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        ObjectNode mapping;
        try (var in = PipelineLoadBenchmark.class.getResourceAsStream("/case-index-mapping.json")) { mapping = (ObjectNode) PaymentJson.JSON.readTree(in); }
        RetrievalBenchmark.call("PUT", "/" + index, mapping);
        var env = KafkaPaymentCepJob.environment(); env.getConfig().setAutoWatermarkInterval(100);
        env.configure(checkpointConfig(output.resolve("checkpoints")));
        var input = env.fromSource(KafkaPaymentCepJob.source(bootstrap, rawTopic, namespace, false), KafkaPaymentCepJob.watermarks(45000), "load-raw").uid("kafka-payments-v1");
        long base = System.currentTimeMillis();
        var streams = PaymentCep.build(input, base - 600000, namespace);
        streams.cases().sinkTo(KafkaPaymentCepJob.sink(bootstrap, caseTopic, namespace, DeliveryGuarantee.EXACTLY_ONCE)).uid("kafka-cases-v1");
        streams.rejected().print("rejected"); streams.cepLate().map(p -> p.eventId).print("unexpected-cep-late");
        var job = env.executeAsync("Article 2 one load ladder");
        job.getJobExecutionResult().whenComplete((result, failure) -> {
            if (!stop) {
                Throwable problem = failure != null ? failure : new IllegalStateException("unbounded load job ended unexpectedly");
                error.compareAndSet(null, problem);
                try (var log = new java.io.PrintWriter(output.resolve("job-failure.txt").toFile())) { problem.printStackTrace(log); }
                catch (Exception ignored) { problem.printStackTrace(); }
            }
        });
        var projection = new CaseIndexer(java.net.URI.create(RetrievalBenchmark.ENDPOINT), index);
        var observer = observe(output); var indexing = indexer(projection);
        Thread polling = Thread.ofPlatform().start(() -> {
            try (var metrics = Files.newBufferedWriter(output.resolve("visibility.jsonl"))) {
                while (!stop) {
                    long before = System.currentTimeMillis();
                    var search = RetrievalBenchmark.call("POST", "/" + index + "/_search", Map.of("size", 1, "track_total_hits", true,
                        "sort", List.of(Map.of("feature_cutoff", "desc")), "_source", List.of("feature_cutoff", "evidence_event_ids"), "query", Map.of("match_all", Map.of())));
                    long count = search.path("hits").path("total").path("value").asLong();
                    visible.set((int) count);
                    metrics.write(PaymentJson.JSON.writeValueAsString(Map.of("before_ms", before, "after_ms", System.currentTimeMillis(), "committed", committed.get(), "visible", count, "latest_hit", search.path("hits").path("hits"))));
                    metrics.newLine(); metrics.flush(); Thread.sleep(100);
                }
            } catch (Throwable e) { if (!stop) error.compareAndSet(null, e); }
        });
        // A fixed historical corpus is queried under live pipeline load. This is not an online enriched-feature join.
        Thread historicalSearch = Thread.ofPlatform().start(() -> {
            try (var timings = Files.newBufferedWriter(output.resolve("historical-queries.jsonl"))) {
                int q = 0;
                while (!stop) {
                    int queryId = q++ % historicalQueries.length;
                    long before = System.currentTimeMillis(), start = System.nanoTime();
                    var response = RetrievalBenchmark.call("POST", "/" + historicalIndex + "/_search", RetrievalBenchmark.query(historicalQueries[queryId], false, 100, false));
                    timings.write(PaymentJson.JSON.writeValueAsString(Map.of("before_ms", before, "query", queryId, "millis", (System.nanoTime() - start) / 1e6, "ids", RetrievalBenchmark.ids(response))));
                    timings.newLine(); timings.flush(); Thread.sleep(500);
                }
            } catch (Throwable e) { if (!stop) error.compareAndSet(null, e); }
        });
        Thread lagSampler = Thread.ofPlatform().start(() -> {
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap, "default.api.timeout.ms", "5000", "request.timeout.ms", "3000"));
                 var lag = Files.newBufferedWriter(output.resolve("topic-lag.jsonl"))) {
                var rawPartition = new TopicPartition(rawTopic, 0); var casePartition = new TopicPartition(caseTopic, 0);
                while (!stop) {
                    var ends = admin.listOffsets(Map.of(rawPartition, OffsetSpec.latest(), casePartition, OffsetSpec.latest())).all().get(6, TimeUnit.SECONDS);
                    var source = admin.listConsumerGroupOffsets(namespace).partitionsToOffsetAndMetadata().get(6, TimeUnit.SECONDS);
                    var sink = admin.listConsumerGroupOffsets(namespace + "-indexer").partitionsToOffsetAndMetadata().get(6, TimeUnit.SECONDS);
                    long rawOffset = source.containsKey(rawPartition) ? source.get(rawPartition).offset() : 0;
                    long indexedOffset = sink.containsKey(casePartition) ? sink.get(casePartition).offset() : 0;
                    lag.write(PaymentJson.JSON.writeValueAsString(Map.of("wall_ms", System.currentTimeMillis(), "raw_end_offset", ends.get(rawPartition).offset(),
                        "source_checkpoint_committed_offset", rawOffset, "case_end_offset", ends.get(casePartition).offset(), "indexer_committed_offset", indexedOffset)));
                    lag.newLine(); lag.flush(); Thread.sleep(1000);
                }
            } catch (Throwable e) { if (!stop) error.compareAndSet(null, e); }
        });
        var p = new Properties(); p.put("bootstrap.servers", bootstrap); p.put("acks", "all"); p.put("enable.idempotence", "true");
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer"); p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("buffer.memory", "33554432"); p.put("max.block.ms", "5000"); p.put("request.timeout.ms", "5000"); p.put("delivery.timeout.ms", "15000");
        var stages = new ArrayList<Object>(); int episode = 0;
        try (var producer = new KafkaProducer<String, String>(p); var schedule = Files.newBufferedWriter(output.resolve("schedule.jsonl"))) {
            int[] allRates = {100, 100, 1000, 5000};
            for (int stage = 0; stage < allRates.length; stage++) {
                int rate = allRates[stage], seconds = stage == 0 ? WARMUP_SECONDS : STAGE_SECONDS;
                long start = System.nanoTime(), wallStart = System.currentTimeMillis();
                int episodes = rate * seconds / 4; double maxLagMs = 0;
                System.out.println("LOAD stage " + stage + " " + rate + " events/s for " + seconds + "s");
                for (int i = 0; i < episodes; i++, episode++) {
                    long scheduled = start + (long) (i * 4e9 / rate);
                    long wait;
                    while ((wait = scheduled - System.nanoTime()) > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
                    double lag = Math.max(0, (System.nanoTime() - scheduled) / 1e6); maxLagMs = Math.max(maxLagMs, lag);
                    // Event time follows scheduled wall time; intra-episode offsets are synthetic, <=3ms.
                    long t0 = wallStart + (long) (i * 4000.0 / rate);
                    gaps.put(episode, .001); amounts.put(episode, 4006L); emitted.put(episode, System.currentTimeMillis());
                    for (int j = 0; j < 4; j++) {
                        var n = (ObjectNode) PaymentJson.JSON.readTree(Timelines.event("load_" + episode + "_" + j, "account-" + episode, t0 + j - Timelines.BASE, j, j == 3));
                        n.put("emitted_at", Instant.now().toString());
                        if (j == 3) emitted.put(episode, System.currentTimeMillis());
                        producer.send(new ProducerRecord<>(rawTopic, "account-" + episode, n.toString()), (metadata, failure) -> { if (failure != null) error.compareAndSet(null, failure); });
                    }
                    schedule.write(PaymentJson.JSON.writeValueAsString(Map.of("episode", episode, "stage", stage, "offered_rate", rate, "scheduled_ms", t0, "emitted_ms", emitted.get(episode), "lag_ms", lag)));
                    schedule.newLine();
                    if (error.get() != null) throw new RuntimeException(error.get());
                }
                producer.flush();
                stages.add(Map.of("stage", stage, "rate", rate, "planned_events", episodes * 4, "elapsed_seconds", (System.nanoTime() - start) / 1e9,
                    "wall_start_ms", wallStart, "wall_end_ms", System.currentTimeMillis(), "max_generator_lag_ms", maxLagMs, "committed_at_end", committed.get(), "visible_at_end", visible.get()));
            }
            producer.send(new ProducerRecord<>(rawTopic, "sentinel", Timelines.event("load_frontier", "frontier", System.currentTimeMillis() + 50000 - Timelines.BASE, 0, false))).get(10, TimeUnit.SECONDS);
            producer.flush();
            long end = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while ((committed.get() < TOTAL || visible.get() < TOTAL) && System.nanoTime() < end) {
                if (error.get() != null) throw new RuntimeException(error.get());
                Thread.sleep(200);
            }
            if (committed.get() != TOTAL || visible.get() != TOTAL) throw new AssertionError("incomplete drain " + committed + "/" + visible + " expected " + TOTAL);
            indexing.join(10000);
            if (error.get() != null) throw new RuntimeException(error.get());
            var summary = new LinkedHashMap<String, Object>(); summary.put("stages", stages); summary.put("expected_cases", TOTAL);
            summary.put("committed_cases", committed.get()); summary.put("search_visible_cases", visible.get()); summary.put("indexer_stats", projection.stats());
            summary.put("node_stats", RetrievalBenchmark.call("GET", "/_nodes/stats/jvm,process,os,indices", null));
            summary.put("scope", "one ladder, no task failure; P=1; actual Kafka/CEP/transactional case log/create-only index; live 8D features; event time equals scheduled wall time plus 0..3ms; 45s watermarks, 1s checkpoints/refresh");
            RetrievalBenchmark.save(output.resolve("load-summary.json"), summary);
            System.out.println("PASS load " + TOTAL + " cases");
        } finally {
            stop = true;
            try { job.cancel().get(30, TimeUnit.SECONDS); } catch (Exception ignored) { /* Job failure is retained separately. */ }
            observer.join(5000); polling.join(5000); historicalSearch.join(5000); lagSampler.join(7000); indexing.join(35000);
            RetrievalBenchmark.call("DELETE", "/" + index, null);
            RetrievalBenchmark.call("DELETE", "/" + historicalIndex, null);
        }
    }
}
