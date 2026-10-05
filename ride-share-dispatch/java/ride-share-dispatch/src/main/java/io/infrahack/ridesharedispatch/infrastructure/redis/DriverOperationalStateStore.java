package io.infrahack.ridesharedispatch.infrastructure.redis;

import io.infrahack.ridesharedispatch.domain.Driver;
import io.infrahack.ridesharedispatch.domain.DriverId;
import io.infrahack.ridesharedispatch.domain.DriverOperationalState;
import io.infrahack.ridesharedispatch.domain.DriverOperationalStatus;
import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.service.SpatialIndex;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Hot driver state. Lua scripts enforce transitions that must be atomic with reservations. */
@Component
public class DriverOperationalStateStore {

    private static final String STATE_KEY_PREFIX = "driver:state:";
    private static final String RESERVATION_KEY_PREFIX = "reservation:";
    private static final long TTL_MULTIPLE_OF_FRESHNESS = 6L;

    private static final RedisScript<Long> COMPARE_AND_SET_LOCATION = new DefaultRedisScript<>("""
            local currentSeq = tonumber(redis.call('HGET', KEYS[1], 'seq') or '-1')
            local newSeq = tonumber(ARGV[3])
            if newSeq <= currentSeq then return 0 end
            redis.call('HSET', KEYS[1],
              'lat', ARGV[1], 'lng', ARGV[2], 'seq', ARGV[3],
              'lastSeen', ARGV[4], 'spatialCell', ARGV[6])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private static final RedisScript<Long> SET_AVAILABILITY = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            local active = redis.call('HGET', KEYS[1], 'activeAssignmentId')
            if status == 'OCCUPIED' or active then return 0 end
            redis.call('HSET', KEYS[1],
              'status', ARGV[1], 'serviceType', ARGV[2], 'accountStatus', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    /**
     * Consumes the exact reservation token and marks the driver occupied in one Redis
     * operation. A retry for the same deterministic assignment id is allowed; a stale
     * token or a different assignment is rejected.
     */
    private static final RedisScript<Long> CONSUME_RESERVATION_AND_OCCUPY = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            local active = redis.call('HGET', KEYS[1], 'activeAssignmentId')
            if status == 'OCCUPIED' and active == ARGV[2] then return 2 end
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
            if status ~= 'AVAILABLE' or active then return -1 end
            local lastSeen = tonumber(redis.call('HGET', KEYS[1], 'lastSeen') or '-1')
            if lastSeen < tonumber(ARGV[3]) then return -1 end
            redis.call('HSET', KEYS[1], 'status', 'OCCUPIED', 'activeAssignmentId', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private static final RedisScript<Long> RELEASE_OCCUPANCY_IF_OWNED = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') ~= 'OCCUPIED' then return 0 end
            if redis.call('HGET', KEYS[1], 'activeAssignmentId') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'status', 'AVAILABLE')
            redis.call('HDEL', KEYS[1], 'activeAssignmentId')
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    public enum LocationUpdateResult { ACCEPTED, REJECTED_STALE_SEQUENCE }

    public enum OccupyResult { ACQUIRED, ALREADY_OWNED, STALE_RESERVATION, INELIGIBLE }

    private final StringRedisTemplate redis;
    private final SpatialIndex spatialIndex;

    public DriverOperationalStateStore(StringRedisTemplate redis, SpatialIndex spatialIndex) {
        this.redis = redis;
        this.spatialIndex = spatialIndex;
    }

    public LocationUpdateResult recordLocation(DriverId driverId, GeoPoint point, long sequenceNumber,
                                                Instant serverReceiveTime, Duration freshnessWindow) {
        long ttlSeconds = stateTtlSeconds(freshnessWindow);
        String spatialCell = GeoCell.indexOf(point).cellId();
        Long accepted = redis.execute(COMPARE_AND_SET_LOCATION, List.of(stateKey(driverId)),
                Double.toString(point.latitude()), Double.toString(point.longitude()),
                Long.toString(sequenceNumber), Long.toString(serverReceiveTime.toEpochMilli()),
                Long.toString(ttlSeconds), spatialCell);
        if (accepted == null || accepted == 0L) {
            return LocationUpdateResult.REJECTED_STALE_SEQUENCE;
        }

        Optional<DriverOperationalState> state = get(driverId);
        if (state.isPresent() && state.get().status() == DriverOperationalStatus.AVAILABLE
                && state.get().location().isPresent()) {
            // A newer update may have committed after this script. Index the current
            // snapshot rather than this caller's possibly superseded coordinates.
            spatialIndex.upsert(driverId, state.get().location().orElseThrow());
        } else {
            spatialIndex.remove(driverId);
        }
        return LocationUpdateResult.ACCEPTED;
    }

    public boolean setAvailable(Driver driver, Duration freshnessWindow) {
        Long changed = redis.execute(SET_AVAILABILITY, List.of(stateKey(driver.id())),
                DriverOperationalStatus.AVAILABLE.name(), driver.serviceType(), driver.accountStatus().name(),
                Long.toString(stateTtlSeconds(freshnessWindow)));
        if (changed == null || changed == 0L) {
            return false;
        }
        get(driver.id()).flatMap(DriverOperationalState::location).ifPresent(point -> spatialIndex.upsert(driver.id(), point));
        return true;
    }

    public boolean setOffline(Driver driver, Duration freshnessWindow) {
        Long changed = redis.execute(SET_AVAILABILITY, List.of(stateKey(driver.id())),
                DriverOperationalStatus.OFFLINE.name(), driver.serviceType(), driver.accountStatus().name(),
                Long.toString(stateTtlSeconds(freshnessWindow)));
        if (changed != null && changed == 1L) {
            spatialIndex.remove(driver.id());
            return true;
        }
        return false;
    }

    public OccupyResult consumeReservationAndMarkOccupied(DriverId driverId, UUID reservationToken,
                                                           AssignmentId assignmentId, Duration freshnessWindow) {
        Long result = redis.execute(CONSUME_RESERVATION_AND_OCCUPY,
                List.of(stateKey(driverId), reservationKey(driverId)),
                reservationToken.toString(), assignmentId.value().toString(),
                Long.toString(Instant.now().minus(freshnessWindow).toEpochMilli()),
                Long.toString(stateTtlSeconds(freshnessWindow)));
        if (result == null || result == 0L) return OccupyResult.STALE_RESERVATION;
        if (result == -1L) return OccupyResult.INELIGIBLE;
        spatialIndex.remove(driverId);
        return result == 2L ? OccupyResult.ALREADY_OWNED : OccupyResult.ACQUIRED;
    }

    public boolean markAvailableIfOwned(DriverId driverId, AssignmentId assignmentId, Duration freshnessWindow) {
        Long released = redis.execute(RELEASE_OCCUPANCY_IF_OWNED, List.of(stateKey(driverId)),
                assignmentId.value().toString(), Long.toString(stateTtlSeconds(freshnessWindow)));
        if (released == null || released == 0L) {
            return false;
        }
        get(driverId).flatMap(DriverOperationalState::location).ifPresent(point -> spatialIndex.upsert(driverId, point));
        return true;
    }

    public Optional<DriverOperationalState> get(DriverId driverId) {
        Map<Object, Object> fields = redis.opsForHash().entries(stateKey(driverId));
        if (fields.isEmpty()) return Optional.empty();

        DriverOperationalStatus status = fields.containsKey("status")
                ? DriverOperationalStatus.valueOf(fields.get("status").toString())
                : DriverOperationalStatus.OFFLINE;
        Optional<GeoPoint> location = (fields.containsKey("lat") && fields.containsKey("lng"))
                ? Optional.of(new GeoPoint(Double.parseDouble(fields.get("lat").toString()),
                                           Double.parseDouble(fields.get("lng").toString())))
                : Optional.empty();
        Optional<Instant> lastSeen = optionalField(fields, "lastSeen")
                .map(value -> Instant.ofEpochMilli(Long.parseLong(value)));
        long sequenceNumber = optionalField(fields, "seq").map(Long::parseLong).orElse(-1L);
        Optional<AssignmentId> activeAssignmentId = optionalField(fields, "activeAssignmentId")
                .map(UUID::fromString).map(AssignmentId::of);
        Optional<Driver.AccountStatus> accountStatus = optionalField(fields, "accountStatus")
                .map(Driver.AccountStatus::valueOf);

        return Optional.of(new DriverOperationalState(driverId, status, location,
                optionalField(fields, "spatialCell"), lastSeen, sequenceNumber, activeAssignmentId,
                optionalField(fields, "serviceType"), accountStatus));
    }

    private static Optional<String> optionalField(Map<Object, Object> fields, String name) {
        return Optional.ofNullable(fields.get(name)).map(Object::toString);
    }

    private static long stateTtlSeconds(Duration freshnessWindow) {
        return Math.max(1L, freshnessWindow.toSeconds() * TTL_MULTIPLE_OF_FRESHNESS);
    }

    private static String stateKey(DriverId driverId) { return STATE_KEY_PREFIX + driverId.value(); }

    private static String reservationKey(DriverId driverId) { return RESERVATION_KEY_PREFIX + driverId.value(); }
}
