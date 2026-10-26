package io.infrahack.explorejavaconcurrency.kitchen;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SynchronizedKitchen {
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, Entry> meals = new HashMap<>();

    public SynchronizedKitchen(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void add(Meal meal) {
        Objects.requireNonNull(meal, "meal");
        synchronized (lock) {
            if (meals.containsKey(meal.id())) {
                throw new IllegalArgumentException("duplicate meal id: " + meal.id());
            }
            meals.put(meal.id(), new Entry(meal));
        }
    }

    public PickupResult claim(String mealId) {
        Objects.requireNonNull(mealId, "mealId");
        synchronized (lock) {
            Entry entry = meals.get(mealId);
            if (entry == null) {
                return PickupResult.NOT_FOUND;
            }
            if (entry.status == MealStatus.CLAIMED) {
                return PickupResult.ALREADY_CLAIMED;
            }
            if (entry.status == MealStatus.EXPIRED) {
                return PickupResult.EXPIRED;
            }
            Instant decisionAt = clock.instant();
            if (!decisionAt.isBefore(entry.meal.expiresAt())) {
                entry.status = MealStatus.EXPIRED;
                return PickupResult.EXPIRED;
            }
            entry.status = MealStatus.CLAIMED;
            return PickupResult.CLAIMED;
        }
    }

    public ExpiryResult expire(String mealId) {
        Objects.requireNonNull(mealId, "mealId");
        synchronized (lock) {
            Entry entry = meals.get(mealId);
            if (entry == null) {
                return ExpiryResult.NOT_FOUND;
            }
            if (entry.status == MealStatus.CLAIMED) {
                return ExpiryResult.ALREADY_CLAIMED;
            }
            if (entry.status == MealStatus.EXPIRED) {
                return ExpiryResult.ALREADY_EXPIRED;
            }
            if (clock.instant().isBefore(entry.meal.expiresAt())) {
                return ExpiryResult.NOT_DUE;
            }
            entry.status = MealStatus.EXPIRED;
            return ExpiryResult.EXPIRED;
        }
    }

    public Optional<MealStatus> status(String mealId) {
        Objects.requireNonNull(mealId, "mealId");
        synchronized (lock) {
            Entry entry = meals.get(mealId);
            return entry == null ? Optional.empty() : Optional.of(entry.status);
        }
    }

    private static final class Entry {
        private final Meal meal;
        private MealStatus status = MealStatus.AVAILABLE;

        private Entry(Meal meal) {
            this.meal = meal;
        }
    }
}
