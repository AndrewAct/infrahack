package io.infrahack.ridesharedispatch.domain;

/**
 * Hot operational state machine, held in Redis rather than Postgres (see
 * infrastructure/redis/DriverOperationalStateStore). RESERVED is intentionally not a
 * column write -- it is modeled as a TTL reservation key so a crashed matching worker
 * cannot strand a driver in RESERVED forever.
 *
 * <pre>
 * OFFLINE -&gt; AVAILABLE -&gt; RESERVED -&gt; OCCUPIED -&gt; AVAILABLE
 * </pre>
 */
public enum DriverOperationalStatus {
    OFFLINE,
    AVAILABLE,
    RESERVED,
    OCCUPIED;

    public boolean canTransitionTo(DriverOperationalStatus target) {
        return switch (this) {
            case OFFLINE -> target == AVAILABLE;
            case AVAILABLE -> target == RESERVED || target == OFFLINE;
            case RESERVED -> target == OCCUPIED || target == AVAILABLE;
            case OCCUPIED -> target == AVAILABLE;
        };
    }
}
