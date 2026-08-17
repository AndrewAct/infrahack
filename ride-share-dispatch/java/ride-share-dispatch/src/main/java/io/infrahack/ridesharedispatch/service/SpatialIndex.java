package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;

import java.util.List;

/**
 * Candidate generation seam: "which agents are near this point?" The MVP implementation
 * is a coarse lat/lng grid backed by Redis sets (see infrastructure/redis/RedisGridSpatialIndex).
 * A production system could swap in a hierarchical geospatial index (e.g. S2, H3, geohash
 * with variable precision) behind this exact interface without touching the matching
 * pipeline that calls it.
 *
 * <p>The index is a discovery aid, not a source of truth: membership can lag a moving
 * agent by one update, so callers must still validate eligibility (freshness, availability)
 * and claim ownership atomically at reservation time. See docs/DESIGN.md "Consistency
 * decisions".
 */
public interface SpatialIndex {

    /** Move (or newly place) an agent into the cell containing {@code point}. */
    void upsert(AgentId agentId, GeoPoint point);

    /** Drop an agent from spatial candidate discovery entirely (e.g. it went offline). */
    void remove(AgentId agentId);

    /**
     * Expand outward from {@code origin}'s cell ring by ring, stopping once at least
     * {@code maxCandidates} agents have been collected or {@code maxRings} is exhausted.
     * Both bounds exist to keep a single request's search cost constant regardless of
     * how densely or sparsely agents are distributed.
     */
    List<AgentId> nearby(GeoPoint origin, int maxRings, int maxCandidates);
}
