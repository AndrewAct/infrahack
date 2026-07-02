package io.infrahack.passwordresetworkflow.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny in-process metrics registry that exposes Prometheus text — no Micrometer dependency.
 *
 * <p>Three families:
 * <ul>
 *   <li>{@code password_reset_http_requests_total} — counter by method/route/status.</li>
 *   <li>{@code password_reset_http_request_duration_seconds} — summary (count + sum) per route,
 *       enough for average latency; upgrade to a histogram for real p95/p99.</li>
 *   <li>{@code password_reset_outcomes_total} — counter by step/outcome (request, verify, reset ×
 *       ok/invalid_code/expired/...), the business-level signal: an attack or a bug shows up here
 *       (e.g. a spike of {@code verify/invalid_code}) before it shows in HTTP status ratios.</li>
 * </ul>
 * {@link LongAdder}/{@link DoubleAdder} scale better than {@code AtomicLong} under many concurrent
 * writers (the metric path must never become the bottleneck it measures).
 */
public final class Metrics {

    private record Series(String name, String labels) {}

    private final Map<Series, LongAdder> httpCounters = new ConcurrentHashMap<>();
    private final Map<Series, LongAdder> outcomeCounters = new ConcurrentHashMap<>();
    private final Map<Series, LongAdder> durationCount = new ConcurrentHashMap<>();
    private final Map<Series, DoubleAdder> durationSum = new ConcurrentHashMap<>();

    /** Count one completed HTTP request. */
    public void countRequest(String method, String route, int status) {
        Series s = new Series("password_reset_http_requests_total",
                labels("method", method, "route", route, "status", Integer.toString(status)));
        httpCounters.computeIfAbsent(s, k -> new LongAdder()).increment();
    }

    /** Count one business outcome, e.g. ("verify", "invalid_code"). */
    public void countOutcome(String step, String outcome) {
        Series s = new Series("password_reset_outcomes_total",
                labels("step", step, "outcome", outcome));
        outcomeCounters.computeIfAbsent(s, k -> new LongAdder()).increment();
    }

    /** Record one request's latency in seconds. */
    public void observeDuration(String route, double seconds) {
        Series s = new Series("password_reset_http_request_duration_seconds", labels("route", route));
        durationCount.computeIfAbsent(s, k -> new LongAdder()).increment();
        durationSum.computeIfAbsent(s, k -> new DoubleAdder()).add(seconds);
    }

    /** Render the current metrics in Prometheus text exposition format. */
    public String scrape() {
        StringBuilder sb = new StringBuilder();

        sb.append("# HELP password_reset_http_requests_total Total HTTP requests, by method/route/status.\n");
        sb.append("# TYPE password_reset_http_requests_total counter\n");
        httpCounters.forEach((s, v) ->
                sb.append(s.name()).append(s.labels()).append(' ').append(v.sum()).append('\n'));

        sb.append("# HELP password_reset_outcomes_total Business outcomes, by step/outcome.\n");
        sb.append("# TYPE password_reset_outcomes_total counter\n");
        outcomeCounters.forEach((s, v) ->
                sb.append(s.name()).append(s.labels()).append(' ').append(v.sum()).append('\n'));

        sb.append("# HELP password_reset_http_request_duration_seconds Request latency summary, by route.\n");
        sb.append("# TYPE password_reset_http_request_duration_seconds summary\n");
        durationCount.forEach((s, count) -> {
            DoubleAdder sum = durationSum.get(s);
            sb.append(s.name()).append("_count").append(s.labels())
                    .append(' ').append(count.sum()).append('\n');
            sb.append(s.name()).append("_sum").append(s.labels())
                    .append(' ').append(sum == null ? 0.0 : sum.sum()).append('\n');
        });

        return sb.toString();
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
