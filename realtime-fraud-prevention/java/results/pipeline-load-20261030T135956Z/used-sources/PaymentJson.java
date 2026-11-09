package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.TreeMap;

public final class PaymentJson {
    public static final ObjectMapper JSON = new ObjectMapper();
    private PaymentJson() {}

    public static Payment parse(String raw) throws Exception {
        JsonNode n = JSON.readTree(raw);
        if (!n.path("schema_version").isIntegralNumber() || n.path("schema_version").asInt() != 1
            || !"payment.attempt.observed".equals(text(n, "/event_type"))) {
            throw new IllegalArgumentException("unsupported schema or event type");
        }
        Payment p = new Payment();
        p.tenantId = text(n, "/tenant_id");
        p.accountId = text(n, "/account_id");
        p.eventId = text(n, "/event_id");
        p.attemptId = text(n, "/payment/attempt_id");
        p.cardNamespace = text(n, "/payment/card_token_namespace");
        p.cardToken = text(n, "/payment/card_token");
        p.occurredAt = Instant.parse(text(n, "/occurred_at")).toEpochMilli();
        Instant.parse(text(n, "/emitted_at"));
        p.sourceSequence = integer(n, "/source_sequence");
        p.amountMinor = integer(n, "/payment/amount_minor");
        if (p.sourceSequence < 0 || p.amountMinor < 0 || p.occurredAt > Long.MAX_VALUE - 600_000L) {
            throw new IllegalArgumentException("invalid sequence, amount or time");
        }
        p.currency = text(n, "/payment/currency");
        p.status = text(n, "/payment/authorization_status");
        if (!p.status.equals("declined") && !p.status.equals("authorized")) {
            throw new IllegalArgumentException("expected a terminal authorization outcome");
        }
        p.device = optional(n.at("/context/device/device_token"));
        p.ip = optional(n.at("/context/network/ip"));
        // A fresh transport event ID does not create a new business attempt.
        ObjectNode business = ((ObjectNode) n).deepCopy();
        business.remove("event_id");
        business.remove("emitted_at");
        p.fingerprint = JSON.writeValueAsString(canonical(business));
        return p;
    }

    private static JsonNode canonical(JsonNode n) {
        if (n.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            n.fields().forEachRemaining(e -> fields.put(e.getKey(), canonical(e.getValue())));
            ObjectNode out = JSON.createObjectNode();
            fields.forEach(out::set);
            return out;
        }
        if (n.isArray()) {
            var out = JSON.createArrayNode();
            n.forEach(e -> out.add(canonical(e)));
            return out;
        }
        return n;
    }

    private static String optional(JsonNode n) { return n.isTextual() && !n.asText().isBlank() ? n.asText() : null; }
    private static String text(JsonNode n, String path) {
        String value = optional(n.at(path));
        if (value == null) throw new IllegalArgumentException("missing string " + path);
        return value;
    }
    private static long integer(JsonNode n, String path) {
        JsonNode v = n.at(path);
        if (!v.isIntegralNumber() || !v.canConvertToLong()) throw new IllegalArgumentException("invalid integer " + path);
        return v.longValue();
    }
}
