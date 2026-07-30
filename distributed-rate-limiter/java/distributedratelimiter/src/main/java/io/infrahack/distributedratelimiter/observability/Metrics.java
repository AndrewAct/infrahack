package io.infrahack.distributedratelimiter.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny in-process metrics registry exposing Prometheus text - no Micrometer dependency, mirroring
 * the other InfraHack Java modules.
 *
 * <ul>
 *   <li>{@code rate_limiter_decisions_total{rule,outcome}} - the business signal: which rule is
 *       rejecting traffic, and how often.</li>
 *   <li>{@code rate_limiter_store_errors_total} - Redis/Postgres unavailability, the leading
 *       indicator for "the failure policy is about to matter."</li>
 *   <li>{@code rate_limiter_http_requests_total{method,route,status}} and
 *       {@code rate_limiter_http_request_duration_seconds{route}} - the usual HTTP surface.</li>
 * </ul>
 */
public final class Metrics {

    private record Series(String name, String labels) {}

    private final Map<Series, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<Series, LongAdder> durationCount = new ConcurrentHashMap<>();
    private final Map<Series, DoubleAdder> durationSum = new ConcurrentHashMap<>();
    private final LongAdder storeErrors = new LongAdder();

    public void countDecision(String rule, String outcome) {
        increment("rate_limiter_decisions_total", labels("rule", rule, "outcome", outcome));
    }

    public void countStoreError() {
        storeErrors.increment();
    }

    public void countRequest(String method, String route, int status) {
        increment("rate_limiter_http_requests_total",
                labels("method", method, "route", route, "status", Integer.toString(status)));
    }

    public void observeDuration(String route, double seconds) {
        Series s = new Series("rate_limiter_http_request_duration_seconds", labels("route", route));
        durationCount.computeIfAbsent(s, k -> new LongAdder()).increment();
        durationSum.computeIfAbsent(s, k -> new DoubleAdder()).add(seconds);
    }

    public String scrape() {
        StringBuilder sb = new StringBuilder();

        sb.append("# HELP rate_limiter_decisions_total Rate limit decisions, by rule/outcome.\n");
        sb.append("# TYPE rate_limiter_decisions_total counter\n");
        sb.append("# HELP rate_limiter_http_requests_total Total HTTP requests, by method/route/status.\n");
        sb.append("# TYPE rate_limiter_http_requests_total counter\n");
        counters.forEach((s, v) -> sb.append(s.name()).append(s.labels()).append(' ').append(v.sum()).append('\n'));

        sb.append("# HELP rate_limiter_store_errors_total Token bucket store unavailability count.\n");
        sb.append("# TYPE rate_limiter_store_errors_total counter\n");
        sb.append("rate_limiter_store_errors_total ").append(storeErrors.sum()).append('\n');

        sb.append("# HELP rate_limiter_http_request_duration_seconds Request latency summary, by route.\n");
        sb.append("# TYPE rate_limiter_http_request_duration_seconds summary\n");
        durationCount.forEach((s, count) -> {
            DoubleAdder sum = durationSum.get(s);
            sb.append(s.name()).append("_count").append(s.labels()).append(' ').append(count.sum()).append('\n');
            sb.append(s.name()).append("_sum").append(s.labels()).append(' ')
                    .append(sum == null ? 0.0 : sum.sum()).append('\n');
        });

        return sb.toString();
    }

    private void increment(String name, String labels) {
        counters.computeIfAbsent(new Series(name, labels), k -> new LongAdder()).increment();
    }

    private static String labels(String... kv) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                b.append(',');
            }
            b.append(kv[i]).append("=\"").append(escape(kv[i + 1])).append('"');
        }
        return b.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
