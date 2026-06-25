package io.infrahack.parkinglot.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal thread-safe metrics sink: monotonic counters and last-write gauges.
 * Stands in for a Prometheus client — counters map to {@code Counter}, gauges
 * to {@code Gauge}. Thread-safe because entry/exit run concurrently and we don't
 * want lost increments to under-report revenue or traffic.
 */
public class MetricsCollector {
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public void increment(String name) {
        add(name, 1);
    }

    public void add(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    public long count(String name) {
        AtomicLong value = counters.get(name);
        return value == null ? 0 : value.get();
    }

    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new AtomicLong()).set(value);
    }

    public long gauge(String name) {
        AtomicLong value = gauges.get(name);
        return value == null ? 0 : value.get();
    }

    public Map<String, AtomicLong> counters() {
        return counters;
    }
}
