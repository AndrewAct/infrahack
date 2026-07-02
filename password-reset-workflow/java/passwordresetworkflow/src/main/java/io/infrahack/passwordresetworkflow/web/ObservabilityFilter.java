package io.infrahack.passwordresetworkflow.web;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import io.infrahack.passwordresetworkflow.observability.Metrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One place for per-request observability: assigns a request id (caller-supplied
 * {@code X-Request-Id} or a fresh one), echoes it in the response, times the request,
 * updates the metrics counters, and writes one summary log line. A filter rather than an
 * interceptor so the whole request is a single try/finally — no cross-callback state.
 */
public final class ObservabilityFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(ObservabilityFilter.class);

    // All routes are static paths. Anything else becomes "other" so scanners probing random
    // URLs cannot mint unbounded label values (metric cardinality is a resource too).
    private static final Set<String> KNOWN_ROUTES = Set.of(
            "/password-reset/request", "/password-reset/verify", "/password-reset/reset",
            "/auth/login", "/health", "/health/ready", "/metrics");

    private final Metrics metrics;

    public ObservabilityFilter(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = requestIdOf(request);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            double millis = (System.nanoTime() - start) / 1_000_000.0;
            String route = routeFor(request.getRequestURI());
            metrics.observeDuration(route, millis / 1000.0);
            metrics.countRequest(request.getMethod(), route, response.getStatus());
            log.info("rid={} {} {} -> {} ({} ms)", requestId, request.getMethod(),
                    request.getRequestURI(), response.getStatus(), String.format("%.1f", millis));
        }
    }

    private static String requestIdOf(HttpServletRequest request) {
        String fromCaller = request.getHeader(REQUEST_ID_HEADER);
        return (fromCaller == null || fromCaller.isBlank())
                ? UUID.randomUUID().toString().substring(0, 8)
                : fromCaller;
    }

    private static String routeFor(String path) {
        return KNOWN_ROUTES.contains(path) ? path : "other";
    }
}
