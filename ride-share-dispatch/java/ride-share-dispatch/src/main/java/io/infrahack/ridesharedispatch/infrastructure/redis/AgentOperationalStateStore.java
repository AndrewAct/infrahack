package io.infrahack.ridesharedispatch.infrastructure.redis;

import io.infrahack.ridesharedispatch.domain.Agent;
import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.AgentOperationalState;
import io.infrahack.ridesharedispatch.domain.AgentOperationalStatus;
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

/** Hot agent state. Lua scripts enforce transitions that must be atomic with reservations. */
@Component
public class AgentOperationalStateStore {

    private static final String STATE_KEY_PREFIX = "agent:state:";
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
     * Consumes the exact reservation token and marks the agent occupied in one Redis
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

    public AgentOperationalStateStore(StringRedisTemplate redis, SpatialIndex spatialIndex) {
        this.redis = redis;
        this.spatialIndex = spatialIndex;
    }

    public LocationUpdateResult recordLocation(AgentId agentId, GeoPoint point, long sequenceNumber,
                                                Instant serverReceiveTime, Duration freshnessWindow) {
        long ttlSeconds = stateTtlSeconds(freshnessWindow);
        String spatialCell = GeoCell.indexOf(point).cellId();
        Long accepted = redis.execute(COMPARE_AND_SET_LOCATION, List.of(stateKey(agentId)),
                Double.toString(point.latitude()), Double.toString(point.longitude()),
                Long.toString(sequenceNumber), Long.toString(serverReceiveTime.toEpochMilli()),
                Long.toString(ttlSeconds), spatialCell);
        if (accepted == null || accepted == 0L) {
            return LocationUpdateResult.REJECTED_STALE_SEQUENCE;
        }

        Optional<AgentOperationalState> state = get(agentId);
        if (state.isPresent() && state.get().status() == AgentOperationalStatus.AVAILABLE
                && state.get().location().isPresent()) {
            // A newer update may have committed after this script. Index the current
            // snapshot rather than this caller's possibly superseded coordinates.
            spatialIndex.upsert(agentId, state.get().location().orElseThrow());
        } else {
            spatialIndex.remove(agentId);
        }
        return LocationUpdateResult.ACCEPTED;
    }

    public boolean setAvailable(Agent agent, Duration freshnessWindow) {
        Long changed = redis.execute(SET_AVAILABILITY, List.of(stateKey(agent.id())),
                AgentOperationalStatus.AVAILABLE.name(), agent.serviceType(), agent.accountStatus().name(),
                Long.toString(stateTtlSeconds(freshnessWindow)));
        if (changed == null || changed == 0L) {
            return false;
        }
        get(agent.id()).flatMap(AgentOperationalState::location).ifPresent(point -> spatialIndex.upsert(agent.id(), point));
        return true;
    }

    public boolean setOffline(Agent agent, Duration freshnessWindow) {
        Long changed = redis.execute(SET_AVAILABILITY, List.of(stateKey(agent.id())),
                AgentOperationalStatus.OFFLINE.name(), agent.serviceType(), agent.accountStatus().name(),
                Long.toString(stateTtlSeconds(freshnessWindow)));
        if (changed != null && changed == 1L) {
            spatialIndex.remove(agent.id());
            return true;
        }
        return false;
    }

    public OccupyResult consumeReservationAndMarkOccupied(AgentId agentId, UUID reservationToken,
                                                           AssignmentId assignmentId, Duration freshnessWindow) {
        Long result = redis.execute(CONSUME_RESERVATION_AND_OCCUPY,
                List.of(stateKey(agentId), reservationKey(agentId)),
                reservationToken.toString(), assignmentId.value().toString(),
                Long.toString(Instant.now().minus(freshnessWindow).toEpochMilli()),
                Long.toString(stateTtlSeconds(freshnessWindow)));
        if (result == null || result == 0L) return OccupyResult.STALE_RESERVATION;
        if (result == -1L) return OccupyResult.INELIGIBLE;
        spatialIndex.remove(agentId);
        return result == 2L ? OccupyResult.ALREADY_OWNED : OccupyResult.ACQUIRED;
    }

    public boolean markAvailableIfOwned(AgentId agentId, AssignmentId assignmentId, Duration freshnessWindow) {
        Long released = redis.execute(RELEASE_OCCUPANCY_IF_OWNED, List.of(stateKey(agentId)),
                assignmentId.value().toString(), Long.toString(stateTtlSeconds(freshnessWindow)));
        if (released == null || released == 0L) {
            return false;
        }
        get(agentId).flatMap(AgentOperationalState::location).ifPresent(point -> spatialIndex.upsert(agentId, point));
        return true;
    }

    public Optional<AgentOperationalState> get(AgentId agentId) {
        Map<Object, Object> fields = redis.opsForHash().entries(stateKey(agentId));
        if (fields.isEmpty()) return Optional.empty();

        AgentOperationalStatus status = fields.containsKey("status")
                ? AgentOperationalStatus.valueOf(fields.get("status").toString())
                : AgentOperationalStatus.OFFLINE;
        Optional<GeoPoint> location = (fields.containsKey("lat") && fields.containsKey("lng"))
                ? Optional.of(new GeoPoint(Double.parseDouble(fields.get("lat").toString()),
                                           Double.parseDouble(fields.get("lng").toString())))
                : Optional.empty();
        Optional<Instant> lastSeen = optionalField(fields, "lastSeen")
                .map(value -> Instant.ofEpochMilli(Long.parseLong(value)));
        long sequenceNumber = optionalField(fields, "seq").map(Long::parseLong).orElse(-1L);
        Optional<AssignmentId> activeAssignmentId = optionalField(fields, "activeAssignmentId")
                .map(UUID::fromString).map(AssignmentId::of);
        Optional<Agent.AccountStatus> accountStatus = optionalField(fields, "accountStatus")
                .map(Agent.AccountStatus::valueOf);

        return Optional.of(new AgentOperationalState(agentId, status, location,
                optionalField(fields, "spatialCell"), lastSeen, sequenceNumber, activeAssignmentId,
                optionalField(fields, "serviceType"), accountStatus));
    }

    private static Optional<String> optionalField(Map<Object, Object> fields, String name) {
        return Optional.ofNullable(fields.get(name)).map(Object::toString);
    }

    private static long stateTtlSeconds(Duration freshnessWindow) {
        return Math.max(1L, freshnessWindow.toSeconds() * TTL_MULTIPLE_OF_FRESHNESS);
    }

    private static String stateKey(AgentId agentId) { return STATE_KEY_PREFIX + agentId.value(); }

    private static String reservationKey(AgentId agentId) { return RESERVATION_KEY_PREFIX + agentId.value(); }
}
