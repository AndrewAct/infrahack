package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.EventComparator;
import org.apache.flink.cep.functions.PatternProcessFunction;
import org.apache.flink.cep.nfa.aftermatch.AfterMatchSkipStrategy;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

public final class PaymentCep {
    public static final String RULE = "three-failures-then-success-v1";
    public static final OutputTag<String> INVALID = new OutputTag<>("invalid-input") {};
    public static final OutputTag<Payment> CEP_LATE = new OutputTag<>("unexpected-cep-late") {};
    private PaymentCep() {}

    public record Streams(DataStream<String> cases, DataStream<String> rejected, DataStream<Payment> cepLate) {}

    /** Input timestamps/watermarks are assigned by the source policy, not inferred from arrival order. */
    public static Streams build(DataStream<String> input, long coverageStart, String runId) {
        var parsed = input.process(new Parse()).name("validate-nested-payment").uid("payment-validation-v1");
        var admitted = parsed.keyBy(Payment::key).process(new Admission()).name("late-admission-and-business-dedup").uid("payment-admission-v1");
        var ordered = admitted.keyBy(Payment::key).process(new FeatureHistory(coverageStart)).name("ordered-cutoff-features").uid("payment-features-v1");
        var cases = detect(ordered, runId);
        return new Streams(cases, parsed.getSideOutput(INVALID).union(admitted.getSideOutput(Admission.REJECTED)),
            cases.getSideOutput(CEP_LATE));
    }

    static SingleOutputStreamOperator<String> detect(DataStream<Payment> ordered, String runId) {
        EventComparator<Payment> tieOrder = (a, b) -> Payment.ORDER.compare(a, b);
        return CEP.pattern(ordered.keyBy(Payment::key), pattern(), tieOrder)
            .sideOutputLateData(CEP_LATE).process(new ToCase(runId)).name("latest-three-failures-then-authorization").uid("payment-cep-v1");
    }

    static Pattern<Payment, Payment> pattern() {
        return Pattern.<Payment>begin("failures", AfterMatchSkipStrategy.skipPastLastEvent())
            .where(new Status("declined")).times(3).consecutive()
            .next("success").where(new Status("authorized")).within(Duration.ofMillis(120_000));
    }

    static final class Parse extends ProcessFunction<String, Payment> {
        @Override public void processElement(String raw, Context ctx, Collector<Payment> out) throws Exception {
            Payment p;
            try {
                p = PaymentJson.parse(raw);
                if (ctx.timestamp() == null || ctx.timestamp() != p.occurredAt) {
                    throw new IllegalArgumentException("source timestamp must equal occurred_at");
                }
            } catch (Exception e) {
                ctx.output(INVALID, "invalid:" + e.getMessage());
                return;
            }
            out.collect(p);
        }
    }

    static final class Status extends SimpleCondition<Payment> {
        private final String expected;
        Status(String expected) { this.expected = expected; }
        @Override public boolean filter(Payment p) { return p.status.equals(expected); }
    }

    static final class ToCase extends PatternProcessFunction<Payment, String> {
        private final String runId;
        ToCase(String runId) { this.runId = runId; }
        @Override public void processMatch(Map<String, List<Payment>> match, Context ctx, Collector<String> out) throws Exception {
            List<Payment> failures = match.get("failures");
            Payment s = match.get("success").getFirst();
            double duration = (s.occurredAt - failures.getFirst().occurredAt) / 1000.0;
            if (duration >= 120) throw new IllegalStateException("CEP violated strict witness duration");
            Features f = s.snapshot;
            ObjectNode n = PaymentJson.JSON.createObjectNode();
            String identity = PaymentJson.JSON.writeValueAsString(List.of(runId, s.tenantId, RULE, s.eventId));
            n.put("case_id", "case_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))));
            n.put("run_id", runId);
            n.put("tenant_id", s.tenantId);
            n.put("account_id", s.accountId);
            n.put("card_token_namespace", s.cardNamespace);
            n.put("card_token", s.cardToken);
            n.put("entity_type", "account_card");
            n.put("entity_id", s.key());
            n.put("case_family", "payment_retry");
            n.put("currency", s.currency);
            n.put("rule_version", RULE);
            n.put("feature_spec_version", "payment-retry-single-card-8d-v2");
            n.put("transform_version", "log-cap-v1");
            n.put("feature_admission_policy", "event-time-watermark-v1");
            n.put("feature_completeness", f.complete ? "complete" : "incomplete");
            n.put("feature_history_start", Instant.ofEpochMilli(f.historyStart).toString());
            n.put("case_time", Instant.ofEpochMilli(s.occurredAt).toString());
            n.put("feature_cutoff", Instant.ofEpochMilli(s.occurredAt).toString());
            // Persisted with the case; index rebuilds reuse it. Raw replays use a separate run namespace.
            n.put("knowledge_time", Instant.ofEpochMilli(ctx.currentProcessingTime()).toString());
            var ids = n.putArray("evidence_event_ids");
            failures.forEach(p -> ids.add(p.eventId));
            ids.add(s.eventId);
            var features = n.putObject("features");
            features.put("attempt_count_10m", f.attempts);
            features.put("failed_attempt_count_10m", f.failures);
            features.put("failure_fraction_10m", f.failureFraction);
            features.put("distinct_device_count_10m", f.devices);
            features.put("distinct_ip_count_10m", f.ips);
            features.put("attempted_amount_minor_sum_10m", f.amountMinor);
            features.put("median_inter_attempt_seconds_10m", f.medianGapSeconds);
            features.put("matched_sequence_duration_seconds", duration);
            double[] vector = f.vector(duration);
            if (vector != null) {
                var values = n.putArray("behavior_vector");
                for (double value : vector) values.add(value);
            }
            out.collect(PaymentJson.JSON.writeValueAsString(n));
        }
    }
}
