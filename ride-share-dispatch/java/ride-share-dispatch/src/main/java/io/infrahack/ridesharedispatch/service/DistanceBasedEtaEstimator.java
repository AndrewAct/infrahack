package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.GeoPoint;
import org.springframework.stereotype.Component;

/**
 * MVP implementation: haversine distance over an assumed average urban speed. This is
 * an approximation, not a claim about real travel time -- see EtaEstimator javadoc.
 */
@Component
public class DistanceBasedEtaEstimator implements EtaEstimator {

    private static final double ASSUMED_AVERAGE_SPEED_KMH = 30.0;

    @Override
    public double estimateEtaMinutes(GeoPoint from, GeoPoint to) {
        double distanceKm = from.distanceKmTo(to);
        return (distanceKm / ASSUMED_AVERAGE_SPEED_KMH) * 60.0;
    }
}
