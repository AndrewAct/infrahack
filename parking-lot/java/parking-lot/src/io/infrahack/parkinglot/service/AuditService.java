package io.infrahack.parkinglot.service;

import io.infrahack.parkinglot.model.User;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only audit trail of who did what. Backed by a thread-safe list so
 * concurrent gate operations don't drop or interleave events. In production this
 * is a durable log/event stream; here it's the in-memory equivalent.
 */
public class AuditService {
    private final List<String> events = new CopyOnWriteArrayList<>();

    public void record(User actor, String action, String subject) {
        String who = actor == null ? "system" : actor.name();
        events.add(String.format("%s user=%s action=%s subject=%s", Instant.now(), who, action, subject));
    }

    public List<String> events() {
        return events;
    }
}
