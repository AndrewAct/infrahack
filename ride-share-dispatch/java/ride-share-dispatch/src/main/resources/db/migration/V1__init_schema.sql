-- Durable business truth for ride-share-dispatch.
--
-- What does NOT live here: live coordinates, availability, spatial-cell membership,
-- and reservations. Those are hot, high-write-rate, ephemeral state and live in Redis
-- (see infrastructure/redis). This file only holds state that must survive a restart
-- and be queryable with strong consistency. See docs/DESIGN.md "Storage responsibility".

-- ============================================================================
-- agents: durable profile only. No lat/lng/status columns -- see module README.
-- ============================================================================
CREATE TABLE agents (
    agent_id        UUID PRIMARY KEY,
    display_name    VARCHAR(120) NOT NULL,
    service_type    VARCHAR(40)  NOT NULL,
    rating          NUMERIC(3, 2) NOT NULL DEFAULT 5.00,
    account_status  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================================
-- dispatch_requests: one row per logical dispatch command.
-- ============================================================================
CREATE TABLE dispatch_requests (
    request_id            UUID PRIMARY KEY,
    requester_id           UUID         NOT NULL,
    idempotency_key         VARCHAR(120) NOT NULL,
    -- Hash of the fields that define "the same logical request" (origin, destination,
    -- service type). Lets us tell "retry of C123" apart from "requester reused a key
    -- for a different command", which must be rejected, not silently merged.
    request_fingerprint    VARCHAR(64)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'SEARCHING',
    service_type           VARCHAR(40)  NOT NULL,
    origin_lat             DOUBLE PRECISION NOT NULL,
    origin_lng             DOUBLE PRECISION NOT NULL,
    dest_lat               DOUBLE PRECISION NOT NULL,
    dest_lng               DOUBLE PRECISION NOT NULL,
    matched_agent_id       UUID,
    matching_claimed_by    UUID,
    matching_claimed_until TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The atomic guard behind idempotent creation (see docs/DESIGN.md "Idempotency
    -- lifecycle"). A retried POST relies on this constraint, not an app-level
    -- SELECT-then-INSERT, to guarantee at most one DispatchRequest per logical command.
    CONSTRAINT uq_dispatch_requests_requester_key UNIQUE (requester_id, idempotency_key)
);

-- Requester-facing history/listing access pattern.
CREATE INDEX idx_dispatch_requests_requester_created
    ON dispatch_requests (requester_id, created_at DESC);

-- ============================================================================
-- dispatch_offers: one offer per (request, candidate agent) attempt.
-- ============================================================================
CREATE TABLE dispatch_offers (
    offer_id            UUID PRIMARY KEY,
    request_id          UUID NOT NULL REFERENCES dispatch_requests (request_id),
    agent_id            UUID NOT NULL REFERENCES agents (agent_id),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- Must match the Redis reservation value at accept time. A stale accept (reservation
    -- already expired and possibly reissued to someone else) fails this comparison.
    reservation_token   UUID NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispatch_offers_request ON dispatch_offers (request_id);
CREATE INDEX idx_dispatch_offers_agent ON dispatch_offers (agent_id);
CREATE UNIQUE INDEX uq_dispatch_offers_pending_request
    ON dispatch_offers (request_id) WHERE status = 'PENDING';

-- ============================================================================
-- assignments: the durable trip/job record. version drives optimistic concurrency
-- control on the CREATED -> IN_PROGRESS -> COMPLETED / CANCELLED transitions.
-- ============================================================================
CREATE TABLE assignments (
    assignment_id   UUID PRIMARY KEY,
    request_id      UUID NOT NULL REFERENCES dispatch_requests (request_id),
    -- UNIQUE: one accepted offer produces at most one assignment (invariant #3).
    offer_id        UUID NOT NULL UNIQUE REFERENCES dispatch_offers (offer_id),
    requester_id    UUID NOT NULL,
    agent_id        UUID NOT NULL REFERENCES agents (agent_id),
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_assignments_requester_created ON assignments (requester_id, created_at DESC);
CREATE INDEX idx_assignments_agent_created ON assignments (agent_id, created_at DESC);
-- Redis is the fast ownership gate; this partial unique index is the durable final fence.
-- Even if Redis state is lost or a lease expires at the worst possible moment, Postgres
-- still refuses two active assignments for the same agent.
CREATE UNIQUE INDEX uq_assignments_active_agent
    ON assignments (agent_id) WHERE status IN ('CREATED', 'IN_PROGRESS');

-- ============================================================================
-- payments: money is always integer cents, never floating point.
-- ============================================================================
CREATE TABLE payments (
    payment_id      UUID PRIMARY KEY,
    assignment_id   UUID NOT NULL REFERENCES assignments (assignment_id),
    -- Stable logical charge identity, e.g. "<assignmentId>:final-charge". A timeout+retry
    -- reuses this same id instead of minting a new logical charge (see FakePaymentProvider).
    operation_id    VARCHAR(160) NOT NULL UNIQUE,
    amount_cents    BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    attempt_count   INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_until     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_assignment ON payments (assignment_id);
CREATE INDEX idx_payments_due
    ON payments (next_attempt_at) WHERE status IN ('CREATED', 'PROCESSING');

-- Durable ledger for the fake provider. It represents provider-owned idempotency state,
-- so restarting this application cannot make the simulator "forget" a completed charge.
CREATE TABLE fake_payment_provider_charges (
    operation_id    VARCHAR(160) PRIMARY KEY,
    amount_cents    BIGINT NOT NULL,
    outcome         VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- notification_deliveries: simulated fan-out with dedup on (event, recipient, channel)
-- so a redelivered Kafka event cannot create a second delivery row.
-- ============================================================================
CREATE TABLE notification_deliveries (
    notification_id UUID PRIMARY KEY,
    event_id        UUID NOT NULL,
    recipient_id    UUID NOT NULL,
    channel         VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,

    CONSTRAINT uq_notification_dedup UNIQUE (event_id, recipient_id, channel)
);

-- ============================================================================
-- outbox_events: the transactional outbox. Written in the SAME transaction as the
-- durable state change it announces (e.g. assignment -> COMPLETED). A separate
-- poller publishes to Kafka and stamps published_at. See OutboxPublisher.
-- ============================================================================
CREATE TABLE outbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(60) NOT NULL,
    aggregate_id    UUID NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    claimed_by      UUID,
    claim_until     TIMESTAMPTZ
);

-- Partial index: the publisher only ever scans unpublished rows, and completed rows
-- vastly outnumber pending ones once the system has been running a while. Indexing the
-- rare state instead of the whole table keeps the index tiny.
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;

-- ============================================================================
-- processed_events: per-consumer dedup ledger for at-least-once Kafka delivery.
-- (event_id, consumer_name) because two different consumers legitimately both need
-- to process the same event once each.
-- ============================================================================
CREATE TABLE processed_events (
    event_id        UUID NOT NULL,
    consumer_name   VARCHAR(60) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, consumer_name)
);
