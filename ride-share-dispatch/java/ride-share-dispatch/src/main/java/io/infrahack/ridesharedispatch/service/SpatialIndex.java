package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.DriverId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;

import java.util.List;

/**
 * Candidate generation seam: "which drivers are near this point?" The MVP implementation
 * is a coarse lat/lng grid backed by Redis sets (see infrastructure/redis/RedisGridSpatialIndex).
 * A production system could swap in a hierarchical geospatial index (e.g. S2, H3, geohash
 * with variable precision) behind this exact interface without touching the matching
 * pipeline that calls it.
 *
 * <p>The index is a discovery aid, not a source of truth: membership can lag a moving
 * driver by one update, so callers must still validate eligibility (freshness, availability)
 * and claim ownership atomically at reservation time. See docs/DESIGN.md "Consistency
 * decisions".
 */
public interface SpatialIndex {

    /** Move (or newly place) a driver into the cell containing {@code point}. */
    void upsert(DriverId driverId, GeoPoint point);

    /** Drop a driver from spatial candidate discovery entirely (e.g. it went offline). */
    void remove(DriverId driverId);

    /**
     * Expand outward from {@code origin}'s cell ring by ring, stopping once at least
     * {@code maxCandidates} drivers have been collected or {@code maxRings} is exhausted.
     * Both bounds exist to keep a single request's search cost constant regardless of
     * how densely or sparsely drivers are distributed.
     */
    List<DriverId> nearby(GeoPoint origin, int maxRings, int maxCandidates);
}
