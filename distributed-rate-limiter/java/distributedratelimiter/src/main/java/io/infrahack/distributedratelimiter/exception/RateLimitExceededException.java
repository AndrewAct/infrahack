package io.infrahack.distributedratelimiter.exception;

import io.infrahack.distributedratelimiter.model.RateLimitDecision;

/** Thrown by {@code RateLimitInterceptor} so {@code ApiExceptionHandler} owns the 429 mapping. */
public final class RateLimitExceededException extends DomainException {

    private final RateLimitDecision decision;

    public RateLimitExceededException(RateLimitDecision decision) {
        super("rate_limited", "Rate limit exceeded for rule '%s'".formatted(decision.ruleName()));
        this.decision = decision;
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
