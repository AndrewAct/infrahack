package io.infrahack.contentmanagementsystem.service;

import java.util.HashMap;
import java.util.Map;

public class MetricsCollector {
    private final Map<String, Integer> counter = new HashMap<>();

    void increment(String name) {
        counter.merge(name, 1, Integer::sum);
    }

    public int count(String name) {
        return counter.getOrDefault(name, 0);
    }
}
