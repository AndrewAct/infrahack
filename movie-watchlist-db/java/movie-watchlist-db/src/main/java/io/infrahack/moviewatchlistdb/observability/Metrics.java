package io.infrahack.moviewatchlistdb.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny in-process metrics registry that exposes Prometheus text — no Micrometer dependency.
 *
 * <p>Two families:
 * <ul>
 *   <li>{@code watchlist_requests_total} — a counter labeled by method/route/status, so you can see
 *       the ratio of 201/404/409 at a glance (the health signal for this service).</li>
 *   <li>{@code watchlist_request_duration_seconds} — a summary (count + sum) per route, enough to
 *       compute average latency; upgrade to a histogram if you need real p95/p99.</li>
 * </ul>
 * {@link LongAdder}/{@link DoubleAdder} are used because they scale better than {@code AtomicLong}
 * under many concurrent writers (the metric path must never become the bottleneck it measures).
 */
public final class Metrics {

    private record Series(String name, String labels) {}

    private final Map<Series, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<Series, LongAdder> durationCount = new ConcurrentHashMap<>();
    private final Map<Series, DoubleAdder> durationSum = new ConcurrentHashMap<>();

    /** Count one completed request. */
    public void countRequest(String method, String route, int status) {
        Series s = new Series("watchlist_requests_total",
                labels("method", method, "route", route, "status", Integer.toString(status)));
        counters.computeIfAbsent(s, k -> new LongAdder()).increment();
    }

    /** Record one request's latency in seconds. */
    public void observeDuration(String route, double seconds) {
        Series s = new Series("watchlist_request_duration_seconds", labels("route", route));
        durationCount.computeIfAbsent(s, k -> new LongAdder()).increment();
        durationSum.computeIfAbsent(s, k -> new DoubleAdder()).add(seconds);
    }

    /** Render the current metrics in Prometheus text exposition format. */
    public String scrape() {
        StringBuilder sb = new StringBuilder();

        sb.append("# HELP watchlist_requests_total Total HTTP requests, by method/route/status.\n");
        sb.append("# TYPE watchlist_requests_total counter\n");
        counters.forEach((s, v) ->
                sb.append(s.name()).append(s.labels()).append(' ').append(v.sum()).append('\n'));

        sb.append("# HELP watchlist_request_duration_seconds Request latency summary, by route.\n");
        sb.append("# TYPE watchlist_request_duration_seconds summary\n");
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
        if (kv.length == 0) {
            return "";
        }
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
