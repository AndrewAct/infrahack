package io.infrahack.contentmanagementsystem.service;

import io.infrahack.contentmanagementsystem.model.User;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AuditService {
    private final List<String> events = new ArrayList<>();
    void record(User user, String action, String contentId) {
        events.add(String.format("%s: , user: %s, action: %s, content: %s", Instant.now(), user.name(), action, contentId));
    }
    public List<String> events() { return events; }
}
