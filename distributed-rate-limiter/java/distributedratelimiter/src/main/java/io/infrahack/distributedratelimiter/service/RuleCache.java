package io.infrahack.distributedratelimiter.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.infrahack.distributedratelimiter.model.RateLimitRule;
import io.infrahack.distributedratelimiter.repository.RateLimitRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The in-memory snapshot every rate-limit check reads from. Refreshed on a timer from the control
 * plane ({@link RateLimitRuleRepository}); a failed refresh keeps serving the last good snapshot
 * instead of clearing it, so a Postgres outage degrades to "new rule edits don't propagate" rather
 * than "enforcement stops." This is deliberately poll-based rather than push-based (e.g. Postgres
 * LISTEN/NOTIFY): simpler to reason about for an MVP, at the cost of up to one interval of
 * propagation delay for new rules - a documented, not hidden, trade-off.
 */
public final class RuleCache {

    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);

    private final RateLimitRuleRepository repository;
    private final AtomicReference<List<RateLimitRule>> rules = new AtomicReference<>(List.of());

    public RuleCache(RateLimitRuleRepository repository) {
        this.repository = repository;
        refresh();
    }

    public List<RateLimitRule> rules() {
        return rules.get();
    }

    @Scheduled(fixedDelayString = "${rate-limiter.rule-refresh-interval-ms:10000}")
    public void refresh() {
        try {
            List<RateLimitRule> loaded = repository.findEnabledRules();
            rules.set(loaded);
            log.debug("Rule cache refreshed: {} enabled rules", loaded.size());
        } catch (RuntimeException e) {
            log.warn("Rule refresh failed, keeping last known {} rules", rules.get().size(), e);
        }
    }
}
