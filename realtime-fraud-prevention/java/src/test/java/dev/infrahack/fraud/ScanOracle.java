package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Plain ordered scan: does not call CEP, Admission, FeatureHistory, Features, or their comparators. */
final class ScanOracle {
    record Row(JsonNode doc, long time, long sequence, String id, String attempt, String status, String canonical) {}
    record Expected(List<ObjectNode> cases, List<String> rejected) {}

    static Expected evaluate(Timelines.Trace trace) throws Exception {
        long watermark = Long.MIN_VALUE;
        var accepted = new HashMap<String, List<Row>>();
        var attempts = new HashMap<String, Map<String, Row>>();
        var events = new HashMap<String, Map<String, Row>>();
        var conflicts = new HashMap<String, Long>();
        var cases = new ArrayList<ObjectNode>();
        var rejections = new ArrayList<String>();
        for (String raw : trace.steps()) {
            JsonNode n;
            try { n = PaymentJson.JSON.readTree(raw); }
            catch (Exception e) { rejections.add("invalid"); continue; }
            if (n.has("_watermark")) {
                watermark = n.path("_watermark").asLong();
                finalizeThrough(accepted, conflicts, cases, watermark, trace.coverageStart());
                final long w = watermark;
                attempts.values().forEach(m -> m.values().removeIf(r -> r.time + 120_000 <= w));
                events.values().forEach(m -> m.values().removeIf(r -> r.time + 120_000 <= w));
                continue;
            }
            if (n.at("/payment/attempt_id").asText().isBlank()
                || !n.at("/payment/amount_minor").isIntegralNumber()
                || !Set.of("declined", "authorized").contains(n.at("/payment/authorization_status").asText())) {
                rejections.add("invalid"); continue;
            }
            String key = PaymentJson.JSON.writeValueAsString(List.of(n.path("tenant_id").asText(), n.path("account_id").asText(),
                n.at("/payment/card_token_namespace").asText(), n.at("/payment/card_token").asText()));
            ObjectNode business = ((ObjectNode) n).deepCopy();
            business.remove(List.of("event_id", "emitted_at"));
            Row row = new Row(n, Instant.parse(n.path("occurred_at").asText()).toEpochMilli(), n.path("source_sequence").asLong(),
                n.path("event_id").asText(), n.at("/payment/attempt_id").asText(), n.at("/payment/authorization_status").asText(),
                canonical(business));
            if (row.time <= watermark) { rejections.add("late:" + row.id); continue; }
            var byAttempt = attempts.computeIfAbsent(key, k -> new HashMap<>());
            var byEvent = events.computeIfAbsent(key, k -> new HashMap<>());
            Row a = byAttempt.get(row.attempt), e = byEvent.get(row.id);
            if ((a != null && !a.canonical.equals(row.canonical)) || (e != null && !e.canonical.equals(row.canonical))) {
                rejections.add("conflict:" + row.id);
                long until = Math.max(row.time, Math.max(a == null ? Long.MIN_VALUE : a.time, e == null ? Long.MIN_VALUE : e.time)) + 600_000;
                conflicts.merge(key, until, Math::max);
            } else if (a != null || e != null) {
                byEvent.put(row.id, row);
                rejections.add("duplicate:" + row.id);
            } else {
                byAttempt.put(row.attempt, row);
                byEvent.put(row.id, row);
                accepted.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        finalizeThrough(accepted, conflicts, cases, Long.MAX_VALUE, trace.coverageStart());
        return new Expected(cases, rejections);
    }

    private static void finalizeThrough(Map<String, List<Row>> accepted, Map<String, Long> conflicts,
                                        List<ObjectNode> cases, long watermark, long coverageStart) {
        var emitted = new HashSet<String>();
        cases.forEach(n -> emitted.add(n.path("evidence_event_ids").get(3).asText()));
        for (var entry : accepted.entrySet()) {
            var rows = entry.getValue().stream().filter(r -> r.time <= watermark)
                .sorted(Comparator.comparingLong(Row::time).thenComparingLong(Row::sequence).thenComparing(Row::id)).toList();
            for (int i = 3; i < rows.size(); i++) {
                Row s = rows.get(i);
                if (!s.status.equals("authorized") || emitted.contains(s.id)) continue;
                var witness = rows.subList(i - 3, i + 1);
                if (witness.subList(0, 3).stream().anyMatch(r -> !r.status.equals("declined"))
                    || s.time - witness.getFirst().time >= 120_000) continue;
                var h = rows.subList(0, i + 1).stream().filter(r -> r.time > s.time - 600_000).toList();
                ObjectNode result = PaymentJson.JSON.createObjectNode();
                var ids = result.putArray("evidence_event_ids");
                witness.forEach(r -> ids.add(r.id));
                boolean complete = coverageStart <= s.time - 600_000 && s.time >= conflicts.getOrDefault(entry.getKey(), Long.MIN_VALUE);
                var devices = new HashSet<String>();
                var ips = new HashSet<String>();
                long total = 0;
                int declines = 0;
                var gaps = new ArrayList<Double>();
                for (int j = 0; j < h.size(); j++) {
                    Row r = h.get(j);
                    String device = r.doc.at("/context/device/device_token").asText("");
                    String ip = r.doc.at("/context/network/ip").asText("");
                    complete &= !device.isBlank() && !ip.isBlank()
                        && r.doc.at("/payment/currency").equals(s.doc.at("/payment/currency"));
                    if (!device.isBlank()) devices.add(device);
                    if (!ip.isBlank()) ips.add(ip);
                    total += r.doc.at("/payment/amount_minor").asLong();
                    if (r.status.equals("declined")) declines++;
                    if (j > 0) gaps.add((r.time - h.get(j - 1).time) / 1000.0);
                }
                Collections.sort(gaps);
                double median = gaps.size() % 2 == 1 ? gaps.get(gaps.size() / 2)
                    : (gaps.get(gaps.size() / 2 - 1) + gaps.get(gaps.size() / 2)) / 2;
                double duration = (s.time - witness.getFirst().time) / 1000.0;
                var f = result.putObject("features");
                f.put("attempt_count_10m", h.size()).put("failed_attempt_count_10m", declines)
                    .put("failure_fraction_10m", (double) declines / h.size()).put("distinct_device_count_10m", devices.size())
                    .put("distinct_ip_count_10m", ips.size()).put("attempted_amount_minor_sum_10m", total)
                    .put("median_inter_attempt_seconds_10m", median).put("matched_sequence_duration_seconds", duration);
                result.put("feature_completeness", complete ? "complete" : "incomplete");
                result.put("feature_cutoff", Instant.ofEpochMilli(s.time).toString());
                if (complete) {
                    double[] raw = {h.size(), declines, (double) declines / h.size(), devices.size(), ips.size(), total, median, duration};
                    double[] caps = {100, 100, 1, 20, 20, 1_000_000, 600, 600};
                    var vector = result.putArray("behavior_vector");
                    for (int j = 0; j < raw.length; j++) vector.add(j == 2 ? raw[j]
                        : (double) (float) (Math.log(1 + Math.min(raw[j], caps[j])) / Math.log(1 + caps[j])));
                }
                cases.add(result);
                emitted.add(s.id);
            }
        }
    }

    private static String canonical(JsonNode node) throws Exception {
        if (node.isObject()) {
            var ordered = new TreeMap<String, String>();
            var fields = node.fields();
            while (fields.hasNext()) { var e = fields.next(); ordered.put(e.getKey(), canonical(e.getValue())); }
            return ordered.toString();
        }
        if (node.isArray()) {
            var values = new ArrayList<String>();
            for (JsonNode child : node) values.add(canonical(child));
            return values.toString();
        }
        return node.toString();
    }
}
