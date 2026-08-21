package io.infrahack.ridesharedispatch.infrastructure.redis;

import io.infrahack.ridesharedispatch.domain.DriverId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.service.SpatialIndex;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Grid membership is two structures per driver:
 * <ul>
 *   <li>{@code geo:cell:{cellId}} -&gt; Redis SET of driver ids currently in that cell</li>
 *   <li>{@code geo:driver-cell:{driverId}} -&gt; the one cell id this driver is currently filed
 *   under, so a move can find and clean up its previous membership</li>
 * </ul>
 * Moving a driver is a read-old / write-new / remove-old sequence, not a single atomic
 * operation. That is an intentional tradeoff: candidate discovery is documented as
 * eventually consistent (see SpatialIndex), and the authoritative check happens at
 * reservation time, so a Lua script here would add complexity without changing any
 * correctness guarantee.
 */
@Component
public class RedisGridSpatialIndex implements SpatialIndex {

    private static final String CELL_KEY_PREFIX = "geo:cell:";
    private static final String DRIVER_CELL_KEY_PREFIX = "geo:driver-cell:";

    /** Collect a small multiple of what the caller asked for before ranking/filtering
     *  trims it down -- some candidates will be stale or already reserved. */
    private static final int CANDIDATE_OVERSAMPLE_FACTOR = 3;

    private final StringRedisTemplate redis;

    public RedisGridSpatialIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void upsert(DriverId driverId, GeoPoint point) {
        String newCellId = GeoCell.indexOf(point).cellId();
        String driverCellKey = DRIVER_CELL_KEY_PREFIX + driverId.value();

        String oldCellId = redis.opsForValue().getAndSet(driverCellKey, newCellId);
        if (oldCellId != null && !oldCellId.equals(newCellId)) {
            redis.opsForSet().remove(CELL_KEY_PREFIX + oldCellId, driverId.value().toString());
        }
        redis.opsForSet().add(CELL_KEY_PREFIX + newCellId, driverId.value().toString());
    }

    @Override
    public void remove(DriverId driverId) {
        String driverCellKey = DRIVER_CELL_KEY_PREFIX + driverId.value();
        String cellId = redis.opsForValue().get(driverCellKey);
        if (cellId != null) {
            redis.opsForSet().remove(CELL_KEY_PREFIX + cellId, driverId.value().toString());
            redis.delete(driverCellKey);
        }
    }

    @Override
    public List<DriverId> nearby(GeoPoint origin, int maxRings, int maxCandidates) {
        GeoCell.Index center = GeoCell.indexOf(origin);
        Set<DriverId> collected = new LinkedHashSet<>();
        int perRingBudget = Math.max(1, maxCandidates * CANDIDATE_OVERSAMPLE_FACTOR);

        for (int ring = 0; ring <= maxRings; ring++) {
            int collectedThisRing = 0;
            for (String cellId : GeoCell.cellsAtRing(center, ring)) {
                if (collectedThisRing >= perRingBudget) break;
                ScanOptions options = ScanOptions.scanOptions()
                        .count(Math.min(128, perRingBudget - collectedThisRing))
                        .build();
                try (Cursor<String> members = redis.opsForSet().scan(CELL_KEY_PREFIX + cellId, options)) {
                    while (members.hasNext() && collectedThisRing < perRingBudget) {
                        if (collected.add(DriverId.of(UUID.fromString(members.next())))) {
                            collectedThisRing++;
                        }
                    }
                }
            }
        }
        return List.copyOf(collected);
    }
}
