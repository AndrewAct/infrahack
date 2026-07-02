package io.infrahack.moviewatchlistdb.web;

import io.infrahack.moviewatchlistdb.observability.Metrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

public final class MetricsInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MetricsInterceptor.class);
    private static final String START_NANOS = MetricsInterceptor.class.getName() + ".startNanos";

    private final Metrics metrics;

    public MetricsInterceptor(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        Object start = request.getAttribute(START_NANOS);
        double seconds = start instanceof Long nanos
                ? (System.nanoTime() - nanos) / 1_000_000_000.0
                : 0.0;
        String route = routeFor(request.getRequestURI());
        metrics.observeDuration(route, seconds);
        metrics.countRequest(request.getMethod(), route, response.getStatus());
        log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                response.getStatus(), String.format("%.1f", seconds * 1000));
    }

    private static String routeFor(String path) {
        if (path.equals("/movies")) {
            return "/movies";
        }
        if (path.equals("/metrics")) {
            return "/metrics";
        }
        if (path.equals("/health") || path.equals("/health/ready")) {
            return path;
        }
        if (path.startsWith("/watchlists/") && path.contains("/movies")) {
            return path.split("/").length >= 5
                    ? "/watchlists/{id}/movies/{movieId}"
                    : "/watchlists/{id}/movies";
        }
        return path;
    }
}
