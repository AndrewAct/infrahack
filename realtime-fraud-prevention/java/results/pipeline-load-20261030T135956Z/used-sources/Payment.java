package dev.infrahack.fraud;

import java.io.Serializable;
import java.util.Comparator;

/** Flink POJO: identity and ordering fields come from the immutable nested source document. */
public class Payment implements Serializable {
    public String tenantId;
    public String accountId;
    public String cardNamespace;
    public String cardToken;
    public String eventId;
    public String attemptId;
    public long occurredAt;
    public long sourceSequence;
    public String status;
    public long amountMinor;
    public String currency;
    public String device;
    public String ip;
    public String fingerprint;
    public boolean conflict;
    public long conflictUntil;
    public Features snapshot;

    public Payment() {}

    public String key() {
        // Length prefixes avoid collisions when a reference itself contains a delimiter.
        return part(tenantId) + part(accountId) + part(cardNamespace) + part(cardToken);
    }

    private static String part(String value) { return value.length() + ":" + value; }

    public static final Comparator<Payment> ORDER = Comparator.comparingLong((Payment p) -> p.occurredAt)
        .thenComparingLong(p -> p.sourceSequence).thenComparing(p -> p.eventId);
}
