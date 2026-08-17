package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.GeoPoint;

/**
 * Ranks candidates once the spatial index has already narrowed the field down to a
 * small top-K. Straight-line distance is not travel time -- a river, a highway, or
 * one-way streets can make a geometrically closer agent slower to arrive than one
 * further away. A production system would call a real road-network routing/ETA
 * service here, but only for the handful of candidates surviving spatial filtering
 * (coarse-to-fine: cheap filtering over many, expensive ranking over few). Swapping
 * the implementation does not touch MatchingService.
 */
public interface EtaEstimator {

    double estimateEtaMinutes(GeoPoint from, GeoPoint to);
}
