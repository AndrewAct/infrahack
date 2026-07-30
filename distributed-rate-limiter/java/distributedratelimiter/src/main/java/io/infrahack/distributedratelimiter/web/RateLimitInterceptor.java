package io.infrahack.distributedratelimiter.web;

import io.infrahack.distributedratelimiter.exception.RateLimitExceededException;
import io.infrahack.distributedratelimiter.model.RateLimitContext;
import io.infrahack.distributedratelimiter.model.RateLimitDecision;
import io.infrahack.distributedratelimiter.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards any {@code @RateLimited} controller method: builds a {@link RateLimitContext} from
 * request headers and checks it before the handler runs. Headers (not real auth) resolve identity
 * here because this demonstrates the embedded deployment mode; a real gateway would populate these
 * from its own authenticated session/JWT/mTLS identity, never trust caller-supplied headers as-is.
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_API_KEY = "X-Api-Key";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_TIER = "X-Tier";

    private final RateLimiterService rateLimiterService;

    public RateLimitInterceptor(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited annotation = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            return true;
        }

        RateLimitContext context = RateLimitContext.builder()
                .userId(request.getHeader(HEADER_USER_ID))
                .apiKey(request.getHeader(HEADER_API_KEY))
                .tenantId(request.getHeader(HEADER_TENANT_ID))
                .ip(request.getRemoteAddr())
                .endpoint(annotation.value())
                .tier(request.getHeader(HEADER_TIER))
                .build();

        RateLimitDecision decision = rateLimiterService.check(context);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision);
        }
        response.setHeader("X-RateLimit-Limit", Long.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        return true;
    }
}
