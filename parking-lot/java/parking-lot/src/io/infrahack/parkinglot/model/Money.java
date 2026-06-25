package io.infrahack.parkinglot.model;

/**
 * Integer-cents money value type. Currency is implicit (single-currency lot).
 * Using cents avoids binary floating-point rounding errors on fees and totals.
 */
public record Money(long cents) {
    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + cents);
        }
    }

    public static Money zero() {
        return new Money(0);
    }

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    public Money plus(Money other) {
        return new Money(this.cents + other.cents);
    }

    public Money times(long factor) {
        return new Money(this.cents * factor);
    }

    public boolean isGreaterThan(Money other) {
        return this.cents > other.cents;
    }

    @Override
    public String toString() {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }
}
