package io.infrahack.ridesharedispatch.infrastructure.redis;

import io.infrahack.ridesharedispatch.domain.AgentId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The correctness-critical primitive of the whole module: at most one in-flight
 * reservation per agent, ever. See docs/DESIGN.md "Reservation algorithm".
 *
 * <p>This is deliberately NOT:
 * <pre>
 *   if (agent.isAvailable()) { reserve(agent); }
 * </pre>
 * That is a check-then-act race -- two matching attempts can both observe "available"
 * before either writes, and both would believe they won the agent. Instead every
 * reservation attempt is one Lua operation that validates current operational state,
 * freshness, account and service eligibility, then performs {@code SET NX PX}. Redis
 * serializes the script, so eligibility validation and acquisition have no race window.
 *
 * <p>The TTL is what makes this safe against a crashed matching worker: if the process
 * dies after winning the reservation but before the offer is accepted or explicitly
 * released, the key expires on its own and the agent becomes reservable again. No
 * separate cleanup job is required.
 */
@Component
public class AgentReservationStore {

    private static final String RESERVATION_KEY_PREFIX = "reservation:";

    /**
     * Release must only delete the key if it still holds the caller's own token.
     * A plain DEL would be another check-then-act race: between "GET says it's mine"
     * and "DEL", the key could have expired and been re-won by a different requester,
     * and a bare DEL would then evict that new, legitimate reservation. The script
     * makes the compare-and-delete atomic.
     */
    private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private static final RedisScript<Long> RESERVE_IF_ELIGIBLE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') ~= 'AVAILABLE' then return -1 end
            if redis.call('HEXISTS', KEYS[1], 'activeAssignmentId') == 1 then return -1 end
            if redis.call('HGET', KEYS[1], 'serviceType') ~= ARGV[2] then return -1 end
            if redis.call('HGET', KEYS[1], 'accountStatus') ~= 'ACTIVE' then return -1 end
            local lastSeen = tonumber(redis.call('HGET', KEYS[1], 'lastSeen') or '-1')
            if lastSeen < tonumber(ARGV[3]) then return -1 end
            local acquired = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[4])
            if acquired then return 1 else return 0 end
            """, Long.class);

    private final StringRedisTemplate redis;

    public AgentReservationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public enum ReserveResult { ACQUIRED, CONFLICT, INELIGIBLE }

    /** Final commit-time validation and reservation are one Lua operation. */
    public ReserveResult tryReserveEligible(AgentId agentId, UUID token, Duration ttl,
                                             Instant now, Duration freshnessWindow,
                                             String requiredServiceType) {
        long freshnessCutoff = now.minus(freshnessWindow).toEpochMilli();
        Long result = redis.execute(RESERVE_IF_ELIGIBLE,
                List.of(stateKey(agentId), reservationKey(agentId)),
                token.toString(), requiredServiceType, Long.toString(freshnessCutoff),
                Long.toString(ttl.toMillis()));
        if (result != null && result == 1L) return ReserveResult.ACQUIRED;
        if (result != null && result == -1L) return ReserveResult.INELIGIBLE;
        return ReserveResult.CONFLICT;
    }

    /** Safe release: a no-op if the reservation already expired or was won by someone
     *  else. Returns true only if this call actually removed its own reservation. */
    public boolean release(AgentId agentId, UUID token) {
        Long deleted = redis.execute(COMPARE_AND_DELETE, List.of(reservationKey(agentId)), token.toString());
        return deleted != null && deleted == 1L;
    }

    private static String reservationKey(AgentId agentId) {
        return RESERVATION_KEY_PREFIX + agentId.value();
    }

    private static String stateKey(AgentId agentId) {
        return "agent:state:" + agentId.value();
    }
}
