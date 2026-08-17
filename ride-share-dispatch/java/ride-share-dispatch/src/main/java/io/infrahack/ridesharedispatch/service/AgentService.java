package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.Agent;
import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.exception.NotFoundException;
import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentOperationalStateStore;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Owns the split described in Agent's javadoc: registration writes the durable
 * PostgreSQL profile once; availability and location are high-frequency and go
 * straight to Redis, never touching Postgres on the hot path.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;
    private final AgentOperationalStateStore stateStore;
    private final DispatchProperties properties;
    private final DispatchMetrics metrics;

    public AgentService(AgentRepository agentRepository, AgentOperationalStateStore stateStore,
                         DispatchProperties properties, DispatchMetrics metrics) {
        this.agentRepository = agentRepository;
        this.stateStore = stateStore;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Agent register(String displayName, String serviceType) {
        Agent agent = new Agent(AgentId.newId(), displayName, serviceType, 5.00,
                Agent.AccountStatus.ACTIVE, Instant.now());
        agentRepository.insert(agent);
        return agent;
    }

    public Agent requireAgent(AgentId agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundException("Agent", agentId));
    }

    public void setAvailability(AgentId agentId, boolean available) {
        Agent agent = requireAgent(agentId);
        Duration freshnessWindow = Duration.ofSeconds(properties.locationFreshnessSeconds());
        boolean changed = available
                ? stateStore.setAvailable(agent, freshnessWindow)
                : stateStore.setOffline(agent, freshnessWindow);
        if (!changed) {
            throw new ConflictException("Agent %s is occupied and cannot change availability".formatted(agentId));
        }
    }

    public AgentOperationalStateStore.LocationUpdateResult recordLocation(
            AgentId agentId, GeoPoint point, long sequenceNumber, Instant clientTimestamp) {
        requireAgent(agentId);
        Instant serverReceiveTime = Instant.now();
        Duration freshnessWindow = Duration.ofSeconds(properties.locationFreshnessSeconds());

        var result = metrics.locationUpdateLatency().record(() ->
                stateStore.recordLocation(agentId, point, sequenceNumber, serverReceiveTime, freshnessWindow));

        metrics.locationUpdatesTotal().increment();
        if (result == AgentOperationalStateStore.LocationUpdateResult.REJECTED_STALE_SEQUENCE) {
            metrics.locationUpdatesStaleTotal().increment();
            log.info("stale location rejected agentId={} sequenceNumber={} clientTimestamp={}",
                    agentId, sequenceNumber, clientTimestamp);
        }
        return result;
    }
}
