package io.infrahack.ridesharedispatch.domain;

/**
 * Integer cents, never floating point -- binary floats cannot represent most decimal
 * currency amounts exactly, and that drift compounds across charges and reconciliation.
 */
public record Money(long cents) {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + cents);
        }
    }

    public static Money ofCents(long cents) {
        return new Money(cents);
    }
}
