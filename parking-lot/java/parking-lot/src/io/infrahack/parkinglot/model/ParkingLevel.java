package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.SpotType;

import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * One floor. Availability is tracked as a per-{@link SpotType} lock-free
 * free-list. Claiming is a single {@code pollFirst()}; releasing is a single
 * {@code addLast()}. Because a spot lives in at most one free-list, two threads
 * can never claim the same spot: at most one wins the poll, the other sees the
 * next element or null. This is the concurrency backbone of the lot — no global
 * lock, so throughput scales with the number of free spots, not contention on a
 * single mutex.
 */
public class ParkingLevel {
    private final int number;
    private final Map<String, ParkingSpot> spots = new ConcurrentHashMap<>();
    private final Map<SpotType, Deque<ParkingSpot>> freeByType = new EnumMap<>(SpotType.class);

    public ParkingLevel(int number) {
        this.number = number;
        for (SpotType type : SpotType.values()) {
            freeByType.put(type, new ConcurrentLinkedDeque<>());
        }
    }

    public void addSpot(ParkingSpot spot) {
        spots.put(spot.id(), spot);
        freeByType.get(spot.type()).addLast(spot);
    }

    /** Atomically claim a free spot of exactly {@code type}, or empty if none. */
    public Optional<ParkingSpot> tryClaim(SpotType type) {
        ParkingSpot spot = freeByType.get(type).pollFirst();
        if (spot == null) {
            return Optional.empty();
        }
        spot.markOccupied();
        return Optional.of(spot);
    }

    /**
     * Return a spot to the free pool. Idempotency and the out-of-service case
     * are handled by the caller's ticket lifecycle: a spot taken out of service
     * while occupied stays out (it is not re-added) so admin intent survives the
     * driver's exit.
     */
    public void release(ParkingSpot spot) {
        spot.markFree();
        if (!spot.isOutOfService()) {
            freeByType.get(spot.type()).addLast(spot);
        }
    }

    /** Admin: pull a spot from rotation. If free now, it leaves the pool immediately. */
    public boolean takeOutOfService(String spotId) {
        ParkingSpot spot = spots.get(spotId);
        if (spot == null) {
            return false;
        }
        freeByType.get(spot.type()).remove(spot);
        spot.markOutOfService();
        return true;
    }

    public boolean returnToService(String spotId) {
        ParkingSpot spot = spots.get(spotId);
        if (spot == null || !spot.returnToService()) {
            return false;
        }
        if (spot.isFreeForAssignment()) {
            freeByType.get(spot.type()).addLast(spot);
        }
        return true;
    }

    public Optional<ParkingSpot> findSpot(String spotId) {
        return Optional.ofNullable(spots.get(spotId));
    }

    public int number() {
        return number;
    }

    public long freeCount(SpotType type) {
        return freeByType.get(type).size();
    }

    public long freeCount() {
        return freeByType.values().stream().mapToLong(Deque::size).sum();
    }

    public int totalSpots() {
        return spots.size();
    }
}
