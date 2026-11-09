package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Synthetic source documents, not observations of real payments or fraud. */
final class Timelines {
    static final long BASE = Instant.parse("2026-10-26T15:00:00Z").toEpochMilli();
    static final long SEED = 20261026L;
    record Trace(String name, long coverageStart, List<String> steps) {}
    private static final ObjectNode TEMPLATE;
    static {
        try (var in = Timelines.class.getResourceAsStream("/payment-attempt.json")) {
            TEMPLATE = (ObjectNode) PaymentJson.JSON.readTree(in);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    static String event(String id, String account, long millis, long sequence, boolean success) {
        ObjectNode n = TEMPLATE.deepCopy();
        n.put("event_id", id).put("account_id", account).put("source_sequence", sequence);
        n.put("occurred_at", Instant.ofEpochMilli(BASE + millis).toString());
        n.put("emitted_at", Instant.ofEpochMilli(BASE + millis + 100).toString());
        ((ObjectNode) n.path("payment")).put("attempt_id", "attempt_" + id)
            .put("authorization_status", success ? "authorized" : "declined").put("amount_minor", 1000 + sequence);
        ((ObjectNode) n.at("/context/device")).put("device_token", "device_" + sequence % 2);
        ((ObjectNode) n.at("/context/network")).put("ip", "192.0.2." + (sequence % 3 + 1));
        return n.toString();
    }

    static String change(String raw, String parent, String field, String value) throws Exception {
        ObjectNode n = (ObjectNode) PaymentJson.JSON.readTree(raw);
        ((ObjectNode) n.at(parent)).put(field, value);
        return n.toString();
    }

    static String wm(long relativeMillis) { return "{\"_watermark\":" + (BASE + relativeMillis) + "}"; }
    static Trace trace(String name, String... events) { return new Trace(name, BASE - 600_000, List.of(events)); }

    static List<String> sequence(String prefix, String statuses, long[] times) {
        var out = new ArrayList<String>();
        for (int i = 0; i < statuses.length(); i++) out.add(event(prefix + "_" + i, prefix, times[i], i, statuses.charAt(i) == 'S'));
        return out;
    }

    static List<Trace> all() throws Exception {
        var traces = new ArrayList<Trace>();
        var standard = sequence("standard", "FFFS", new long[] {0, 30_000, 60_000, 90_000});
        traces.add(new Trace("ordered", BASE - 600_000, standard));
        traces.add(trace("reordered-admitted", standard.get(0), standard.get(1), standard.get(3), wm(59_999), standard.get(2)));
        traces.add(trace("reordered-too-late", standard.get(0), standard.get(1), standard.get(3), wm(60_000), standard.get(2)));
        for (long tolerance : new long[] {2_000, 10_000, 45_000}) {
            var steps = new ArrayList<String>();
            long maximum = Long.MIN_VALUE;
            for (int index : new int[] {0, 1, 3, 2}) {
                String raw = standard.get(index);
                steps.add(raw);
                maximum = Math.max(maximum, Instant.parse(PaymentJson.JSON.readTree(raw).path("occurred_at").asText()).toEpochMilli());
                steps.add(wm(maximum - BASE - tolerance - 1));
            }
            traces.add(new Trace("watermark-tolerance-" + tolerance, BASE - 600_000, steps));
        }
        traces.add(new Trace("two-failures", BASE - 600_000, sequence("short", "FFS", new long[] {0, 30_000, 60_000})));
        traces.add(new Trace("latest-three-of-four", BASE - 600_000, sequence("four", "FFFFS", new long[] {0, 10_000, 20_000, 30_000, 40_000})));
        traces.add(new Trace("success-breaks-sequence", BASE - 600_000, sequence("break", "FSFFS", new long[] {0, 10_000, 20_000, 30_000, 40_000})));
        traces.add(trace("one-failure-redelivered", standard.get(0), standard.get(0), standard.get(0), standard.get(3)));
        traces.add(trace("business-duplicate-fresh-event-id", standard.get(0), standard.get(1),
            change(standard.get(1), "", "event_id", "transport_redelivery"), standard.get(2), standard.get(3)));
        traces.add(new Trace("unfinished", BASE - 600_000, standard.subList(0, 3)));
        for (long span : new long[] {119_999, 120_000, 120_001}) {
            traces.add(new Trace("duration-" + span, BASE - 600_000,
                sequence("bound" + span, "FFFS", new long[] {0, 30_000, 60_000, span})));
        }
        var ties = sequence("tie", "FFFSF", new long[] {0, 0, 0, 0, 0});
        traces.add(trace("equal-time-position-cutoff", ties.get(4), ties.get(3), ties.get(2), ties.get(1), ties.get(0)));
        var tieIds = new ArrayList<String>();
        for (String raw : ties.subList(0, 4)) {
            ObjectNode n = (ObjectNode) PaymentJson.JSON.readTree(raw);
            n.put("source_sequence", 0);
            tieIds.add(n.toString());
        }
        Collections.reverse(tieIds);
        traces.add(new Trace("equal-time-sequence-event-id-tie", BASE - 600_000, tieIds));
        var keys = new ArrayList<String>();
        // Account, tenant, card token and token namespace must all partition the sequence.
        for (int k = 0; k < 5; k++) {
            var seq = sequence("keys" + k, k == 4 ? "FFS" : "FFFS", k == 4
                ? new long[] {0, 30_000, 60_000} : new long[] {0, 30_000, 60_000, 90_000});
            for (String raw : seq) {
                raw = change(raw, "", "account_id", k == 0 ? "other_account" : "shared_account");
                raw = change(raw, "", "tenant_id", k == 1 ? "other_tenant" : "tenant_synthetic_demo");
                raw = change(raw, "/payment", "card_token", k == 2 ? "other_card" : "shared_card");
                raw = change(raw, "/payment", "card_token_namespace", k == 3 ? "other_namespace" : "shared_namespace");
                keys.add(raw);
            }
        }
        Collections.shuffle(keys, new Random(SEED));
        traces.add(new Trace("interleaved-full-keys", BASE - 600_000, keys));
        String conflict = change(standard.get(1), "/payment", "currency", "EUR");
        traces.add(trace("attempt-conflict", standard.get(0), standard.get(1), conflict, standard.get(2), standard.get(3)));
        String eventConflict = change(standard.get(1), "/payment", "attempt_id", "different_attempt");
        traces.add(trace("event-id-conflict", standard.get(0), standard.get(1), eventConflict, standard.get(2), standard.get(3)));
        traces.add(trace("missing-device", standard.get(0), change(standard.get(1), "/context/device", "device_token", ""), standard.get(2), standard.get(3)));
        traces.add(trace("mixed-currency", standard.get(0), change(standard.get(1), "/payment", "currency", "EUR"), standard.get(2), standard.get(3)));
        traces.add(new Trace("startup-incomplete", BASE, standard));
        var boundary = new ArrayList<String>();
        boundary.add(event("history_excluded", "standard", -510_000, 0, true));
        boundary.add(event("history_included", "standard", -509_999, 1, true));
        boundary.addAll(standard);
        traces.add(new Trace("ten-minute-open-lower-bound", BASE - 1_200_000, boundary));
        traces.add(trace("invalid-input", "not-json", change(standard.get(0), "/payment", "attempt_id", ""),
            change(standard.get(0), "/payment", "authorization_status", "pending"),
            change(standard.get(0), "/payment", "amount_minor", "1000")));
        var random = new Random(SEED);
        var corpus = new ArrayList<String>();
        for (int key = 0; key < 64; key++) {
            long t = 0;
            for (int i = 0; i < 18; i++) {
                t += random.nextInt(45_000);
                String raw = event("random_" + key + "_" + i, "random_" + key, t, i, random.nextDouble() > 0.70);
                corpus.add(raw);
                if (random.nextDouble() < 0.25) corpus.add(raw);
            }
        }
        Collections.shuffle(corpus, new Random(SEED));
        traces.add(new Trace("seeded-randomized", BASE - 600_000, corpus));
        return traces;
    }
}
