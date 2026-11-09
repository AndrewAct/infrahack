package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.*;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternSelectFunction;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class CepIntegrationTest {
    static Path results() throws Exception {
        Path dir = Path.of(System.getProperty("evidence.dir", "target/evidence"));
        Files.createDirectories(dir);
        return dir;
    }

    @Test @Timeout(60) void processingTimeUsesObservedOrder() throws Exception {
        var ordered = Timelines.sequence("processing_ordered", "FFFS", new long[] {0, 30_000, 60_000, 90_000});
        var reordered = Timelines.sequence("processing_reordered", "FFFS", new long[] {0, 30_000, 60_000, 90_000});
        var raw = new ArrayList<>(ordered);
        for (int index : new int[] {0, 1, 3, 2}) raw.add(reordered.get(index));
        var env = StreamExecutionEnvironment.createLocalEnvironment(1, new Configuration().set(PipelineOptions.GENERIC_TYPES, false));
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        var payments = env.fromData(raw, Types.STRING).map(PaymentJson::parse);
        var cases = CEP.pattern(payments.keyBy(Payment::key), PaymentCep.pattern()).inProcessingTime()
            .select((PatternSelectFunction<Payment, String>) match -> match.get("success").getFirst().accountId);
        var actual = new ArrayList<String>();
        try (var it = cases.executeAndCollect("Processing-time observed-order control")) {
            while (it.hasNext()) actual.add(it.next());
        }
        assertEquals(List.of("processing_ordered"), actual);
        Files.write(results().resolve("processing-time-input.jsonl"), raw);
        var summary = PaymentJson.JSON.createObjectNode().put("ordered_cases", 1).put("reordered_cases", 0)
            .put("processing_time_policy", "arrival order; no event-time comparator or feature reordering");
        PaymentJson.JSON.writerWithDefaultPrettyPrinter().writeValue(results().resolve("processing-time-summary.json").toFile(), summary);
    }

    @Test @Timeout(180) void boundedMiniClusterMatchesIndependentScan() throws Exception {
        List<Timelines.Trace> traces = Timelines.all();
        // One physical source and key namespace per fixture; controls describe global progress.
        // Separate jobs preserve fixture-specific coverage/watermark policies, especially startup.
        var summary = new ArrayList<ObjectNode>();
        ObjectNode baselineCase = null;
        for (Timelines.Trace trace : traces) {
            var expected = ScanOracle.evaluate(trace);
            var env = StreamExecutionEnvironment.createLocalEnvironment(1, new Configuration().set(PipelineOptions.GENERIC_TYPES, false));
            env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            env.getConfig().setAutoWatermarkInterval(0);
            var timed = env.fromData(trace.steps(), Types.STRING)
                .assignTimestampsAndWatermarks(new Controls());
            // Watermark frames are fixture controls, never business events.
            var input = timed.filter(raw -> !raw.startsWith("{\"_watermark\":"));
            var streams = PaymentCep.build(input, trace.coverageStart(), "cep-fixtures-v1");
            var output = streams.cases().map(s -> "case:" + s)
                .union(streams.rejected().map(s -> "reject:" + s), streams.cepLate().map(p -> "cep-late:" + p.eventId));
            var actual = new ArrayList<ObjectNode>();
            var rejects = new ArrayList<String>();
            try (var it = output.executeAndCollect("CEP fixture: " + trace.name())) {
                while (it.hasNext()) {
                    String row = it.next();
                    if (row.startsWith("case:")) actual.add((ObjectNode) PaymentJson.JSON.readTree(row.substring(5)));
                    else if (row.startsWith("reject:")) rejects.add(row.substring(7));
                    else fail(trace.name() + ": upstream emitted late data to CEP: " + row);
                }
            }
            Files.write(results().resolve(trace.name() + "-input.jsonl"), trace.steps());
            writeCases(trace.name() + "-actual", actual);
            writeCases(trace.name() + "-expected", expected.cases());
            Files.write(results().resolve(trace.name() + "-rejections.txt"), rejects);
            assertCases(trace.name(), expected.cases(), actual);
            assertEquals(reasonCounts(expected.rejected()), reasonCounts(rejects), trace.name() + " rejection accounting");
            assertEquals(normalizedRejections(expected.rejected()), normalizedRejections(rejects), trace.name() + " rejected identities");
            if (trace.name().equals("ordered")) {
                baselineCase = actual.getFirst().deepCopy();
                baselineCase.remove("knowledge_time");
            }
            if (trace.name().equals("reordered-admitted")) {
                ObjectNode reorderedCase = actual.getFirst().deepCopy();
                reorderedCase.remove("knowledge_time");
                assertEquals(baselineCase, reorderedCase, "equivalent admission must preserve logical case content and ID");
            }
            if (trace.name().equals("ordered")) assertEquals(1, actual.size());
            if (trace.name().equals("reordered-too-late")) assertEquals(0, actual.size());
            if (trace.name().equals("duration-120000") || trace.name().equals("duration-120001")) assertTrue(actual.isEmpty());
            if (trace.name().equals("latest-three-of-four")) {
                assertEquals(List.of("four_1", "four_2", "four_3", "four_4"),
                    PaymentJson.JSON.convertValue(actual.getFirst().path("evidence_event_ids"), List.class));
            }
            if (trace.name().equals("equal-time-position-cutoff")) assertEquals(4, actual.getFirst().at("/features/attempt_count_10m").asInt());
            if (trace.name().equals("ten-minute-open-lower-bound")) assertEquals(5, actual.getFirst().at("/features/attempt_count_10m").asInt());
            if (trace.name().equals("duration-119999") || trace.name().equals("watermark-tolerance-45000")) assertEquals(1, actual.size());
            if (trace.name().equals("watermark-tolerance-2000") || trace.name().equals("watermark-tolerance-10000")) assertTrue(actual.isEmpty());
            var row = PaymentJson.JSON.createObjectNode().put("fixture", trace.name()).put("steps", trace.steps().size())
                .put("expected_cases", expected.cases().size()).put("actual_cases", actual.size()).put("pass", true);
            row.set("rejections", PaymentJson.JSON.valueToTree(reasonCounts(rejects)));
            summary.add(row);
            System.out.println(row);
        }
        PaymentJson.JSON.writerWithDefaultPrettyPrinter().writeValue(results().resolve("cep-summary.json").toFile(), summary);
    }

    static void assertCases(String name, List<ObjectNode> expected, List<ObjectNode> actual) throws Exception {
        Comparator<ObjectNode> order = Comparator.comparing(n -> n.path("evidence_event_ids").get(3).asText());
        expected.sort(order); actual.sort(order);
        assertEquals(expected.size(), actual.size(), name + " case count");
        var ids = new HashSet<String>();
        for (int i = 0; i < actual.size(); i++) {
            ObjectNode a = actual.get(i), e = (ObjectNode) PaymentJson.JSON.readTree(expected.get(i).toString());
            assertTrue(ids.add(a.path("case_id").asText()), name + " duplicate case ID");
            for (String field : List.of("evidence_event_ids", "feature_cutoff", "feature_completeness")) {
                assertEquals(e.get(field), a.get(field), name + " " + field);
            }
            var fields = e.path("features").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode value = field.getValue(), observed = a.path("features").path(field.getKey());
                if (value.isIntegralNumber()) assertEquals(value.asLong(), observed.asLong(), name + " " + field.getKey());
                // Independent arithmetic may round sub-nanosecond fractions differently.
                else assertEquals(value.asDouble(), observed.asDouble(), 1e-10, name + " " + field.getKey());
            }
            assertEquals(e.has("behavior_vector"), a.has("behavior_vector"), name + " vector eligibility");
            if (e.has("behavior_vector")) {
                for (int j = 0; j < 8; j++) assertEquals(e.get("behavior_vector").get(j).asDouble(), a.get("behavior_vector").get(j).asDouble(), 1e-7, name + " coordinate " + j);
            }
        }
    }

    private static Map<String, Long> reasonCounts(List<String> rows) {
        var counts = new TreeMap<String, Long>();
        rows.forEach(r -> counts.merge(r.split(":", 2)[0], 1L, Long::sum));
        return counts;
    }
    private static List<String> normalizedRejections(List<String> rows) {
        return rows.stream().map(r -> r.startsWith("invalid:") ? "invalid" : r).sorted().toList();
    }
    private static void writeCases(String name, List<ObjectNode> rows) throws Exception {
        PaymentJson.JSON.writerWithDefaultPrettyPrinter().writeValue(results().resolve(name + ".json").toFile(), rows);
    }

    static class Controls implements WatermarkStrategy<String> {
        @Override public TimestampAssigner<String> createTimestampAssigner(TimestampAssignerSupplier.Context context) {
            return (raw, previous) -> {
                try {
                    JsonNode n = PaymentJson.JSON.readTree(raw);
                    return n.has("_watermark") ? n.get("_watermark").asLong() : Instant.parse(n.path("occurred_at").asText()).toEpochMilli();
                } catch (Exception e) { return 0; }
            };
        }
        @Override public WatermarkGenerator<String> createWatermarkGenerator(WatermarkGeneratorSupplier.Context context) {
            return new WatermarkGenerator<>() {
                @Override public void onEvent(String raw, long timestamp, WatermarkOutput output) {
                    if (raw.startsWith("{\"_watermark\":")) output.emitWatermark(new Watermark(timestamp));
                }
                @Override public void onPeriodicEmit(WatermarkOutput output) {}
            };
        }
    }
}
