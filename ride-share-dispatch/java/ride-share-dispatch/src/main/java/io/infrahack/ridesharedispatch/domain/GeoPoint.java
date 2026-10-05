package io.infrahack.ridesharedispatch.domain;

/**
 * Immutable lat/lng pair with a cheap haversine distance. This is the approximate
 * "as the crow flies" distance used for coarse candidate ranking -- see
 * {@link io.infrahack.ridesharedispatch.service.EtaEstimator} for why that is not
 * the same thing as travel time.
 */
public record GeoPoint(double latitude, double longitude) {

    private static final double EARTH_RADIUS_KM = 6371.0;

    public GeoPoint {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new IllegalArgumentException("latitude and longitude must be finite numbers");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude out of range: " + longitude);
        }
    }

    public double distanceKmTo(GeoPoint other) {
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = Math.toRadians(other.latitude - this.latitude);
        double deltaLng = Math.toRadians(other.longitude - this.longitude);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double clamped = Math.max(0.0, Math.min(1.0, a));
        double c = 2 * Math.atan2(Math.sqrt(clamped), Math.sqrt(1 - clamped));
        return EARTH_RADIUS_KM * c;
    }
}
