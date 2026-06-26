package io.infrahack.elevator.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AuditService {
    private final List<String> events = new ArrayList<>();

    void record(String action, String buildingId) {
        events.add(String.format("%s %s: %s", Instant.now(), action, buildingId));
    }

    public List<String> events() {
        return events;
    }
}
