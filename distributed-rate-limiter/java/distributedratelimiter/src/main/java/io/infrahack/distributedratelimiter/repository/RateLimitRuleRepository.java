package io.infrahack.distributedratelimiter.repository;

import java.util.List;

import io.infrahack.distributedratelimiter.model.RateLimitRule;

/** Source of truth for rate-limit rules (the control plane). Polled by {@code RuleCache}. */
public interface RateLimitRuleRepository {

    List<RateLimitRule> findEnabledRules();
}
