package io.infrahack.distributedratelimiter.web;

import io.infrahack.distributedratelimiter.model.RateLimitContext;
import io.infrahack.distributedratelimiter.model.RateLimitDecision;
import io.infrahack.distributedratelimiter.service.RateLimiterService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The out-of-process "rate limiter API": what a gateway or backend service calls over the network
 * to ask "should this request be allowed?" (the sidecar/centralized-service deployment mode,
 * versus {@code @RateLimited} + {@link RateLimitInterceptor} for the embedded/library mode - both
 * share the same {@link RateLimiterService}, so enforcement is identical either way).
 *
 * <p>Always returns 200 with an {@code allowed} field rather than a 429: this endpoint answers a
 * question, it doesn't enforce HTTP semantics on behalf of the caller's own protocol. The calling
 * gateway decides what "rejected" means for its own request (429, gRPC status, a queued retry...).
 */
@RestController
@RequestMapping("/v1/rate-limit")
public class RateLimitCheckController {

    record CheckPayload(String userId, String apiKey, String tenantId, String ip,
                        String endpoint, String tier, Integer cost) {}

    record CheckResponse(boolean allowed, long limit, long remaining, long retryAfterSeconds, String rule) {

        static CheckResponse from(RateLimitDecision decision) {
            return new CheckResponse(decision.allowed(), decision.limit(), decision.remaining(),
                    decision.retryAfterSeconds(), decision.ruleName());
        }
    }

    private final RateLimiterService rateLimiterService;

    public RateLimitCheckController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckPayload payload) {
        RateLimitContext context = RateLimitContext.builder()
                .userId(payload.userId())
                .apiKey(payload.apiKey())
                .tenantId(payload.tenantId())
                .ip(payload.ip())
                .endpoint(payload.endpoint())
                .tier(payload.tier())
                .cost(payload.cost() == null ? 1 : payload.cost())
                .build();
        return CheckResponse.from(rateLimiterService.check(context));
    }
}
