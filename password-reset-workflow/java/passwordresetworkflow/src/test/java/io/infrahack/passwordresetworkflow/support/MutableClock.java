package io.infrahack.passwordresetworkflow.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can pin and advance, so the 30s expiry boundary is exercised exactly
 * (29s / 30s / 31s) instead of approximately via {@code Thread.sleep}.
 */
public final class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(Instant start) {
        this.instant = start;
    }

    public void set(Instant newInstant) {
        this.instant = newInstant;
    }

    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
