package io.infrahack.elevator.service;

import java.util.HashMap;
import java.util.Map;

public class MetricsCollector {
    private final Map<String, Integer> counters = new HashMap<>();

    void increment(String metric) {
        counters.merge(metric, 1, Integer::sum);
    }

    public int count(String metric) {
        return counters.getOrDefault(metric, 0);
    }
}
