package io.infrahack.ridesharedispatch.infrastructure.redis;

import io.infrahack.ridesharedispatch.domain.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Coarse fixed-size lat/lng grid. Deliberately simple: a cell is just
 * {@code floor(lat / SIZE) : floor(lng / SIZE)}. This is not a sophisticated GIS index --
 * cell area varies with latitude and there is no attempt at equal-area partitioning --
 * but it is enough to bound "search nearby cells" to O(rings^2) instead of scanning every
 * known agent, which is the property that actually matters for this module. See
 * SpatialIndex for the swap-in seam if a production system needed something sharper.
 */
final class GeoCell {

    /** ~0.02 degrees of latitude is roughly 2.2 km. Coarse on purpose: a request should
     *  usually find candidates within ring 0-2 in a normal urban density. */
    static final double CELL_SIZE_DEGREES = 0.02;

    private GeoCell() {
    }

    record Index(long latIdx, long lngIdx) {
        String cellId() {
            return latIdx + ":" + lngIdx;
        }
    }

    static Index indexOf(GeoPoint point) {
        long latIdx = (long) Math.floor(point.latitude() / CELL_SIZE_DEGREES);
        long lngIdx = (long) Math.floor(point.longitude() / CELL_SIZE_DEGREES);
        return new Index(latIdx, lngIdx);
    }

    /** Every cell whose Chebyshev distance from {@code center} is exactly {@code ring}. */
    static List<String> cellsAtRing(Index center, int ring) {
        List<String> cells = new ArrayList<>();
        if (ring == 0) {
            cells.add(center.cellId());
            return cells;
        }
        for (long dLat = -ring; dLat <= ring; dLat++) {
            for (long dLng = -ring; dLng <= ring; dLng++) {
                boolean onPerimeter = Math.max(Math.abs(dLat), Math.abs(dLng)) == ring;
                if (onPerimeter) {
                    cells.add((center.latIdx() + dLat) + ":" + (center.lngIdx() + dLng));
                }
            }
        }
        return cells;
    }
}
