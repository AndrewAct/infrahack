# Refactoring ride-share dispatch: from a plausible demo to an executable correctness case study

This document explains how the module evolved, why the changes were necessary, and
what the current implementation can honestly claim. It is written for two uses:

- an engineering deep dive during an interview;
- the source material for a future article about making distributed-systems decisions
  executable rather than merely drawing them on a diagram.

[`DESIGN.md`](DESIGN.md) remains the canonical specification of the current system.
This document is the change narrative: **problem -> risk -> decision -> evidence ->
remaining limitation**.

## Executive summary

The first implementation had the right building blocks—Spring Boot, PostgreSQL, Redis,
Kafka, an outbox, idempotency keys, and optimistic concurrency—but several guarantees
existed only as intentions. Important operations crossed process or storage boundaries
using check-then-act sequences. Some retry loops had no ownership token or upper bound.
The documentation also occasionally described stronger semantics than the code provided.

The refactor kept the one-day, modular-monolith scope and strengthened the smallest set
of boundaries that decide correctness:

1. **Command identity:** one rider and one idempotency key identify one ride request;
   a payload fingerprint rejects accidental key reuse.
2. **Driver ownership:** Redis Lua scripts atomically validate and reserve a driver;
   offer acceptance transfers the exact reservation into occupied state, while a
   PostgreSQL partial unique index is the durable final fence.
3. **Location ordering:** a per-driver sequence number, enforced server-side, prevents
   older coordinates from replacing newer state; the spatial index follows the accepted
   Redis snapshot.
4. **Crash recovery:** matching, outbox publication, and payment reconciliation use
   expiring claims rather than permanent ownership or unbounded retry. Matching and
   outbox claims also carry owner tokens where a delayed worker could release newer work.
5. **Asynchronous effects:** assignment completion and its outbox event commit together;
   Kafka consumers commit their inbox marker and durable side effect together.
6. **Payment uncertainty:** retries reuse a stable operation ID, the fake provider keeps
   a durable idempotency ledger, and exhausted retries end in `UNKNOWN`, not a fabricated
   failure.
7. **Operational honesty:** searches, batches, deadlines, and retries are bounded;
   metrics describe the bounds; benchmark claims are separated from correctness tests.

The result is still an MVP. It is better because its claims now have concrete atomic
boundaries, failure behavior, and integration tests—not because every production concern
has been implemented.

## What was deliberately preserved

The refactor did not turn the lab into a fleet of microservices. It preserved the choices
that make the project readable and runnable:

- one deployable Spring Boot modular monolith;
- explicit SQL through `JdbcTemplate`;
- PostgreSQL for durable business truth;
- Redis for current, expiring operational state;
- Kafka for asynchronous facts and fan-out;
- a coarse grid index and approximate ETA seam rather than a GIS/routing system;
- simulated notification and payment adapters;
- no authentication, UI, real maps, surge pricing, or multi-region deployment.

`Driver` is now the consistent name for the mobile service provider. `Requester` and
`DispatchRequest` remain useful workflow terms for the rider and ride request without
forcing readers to translate an ambiguous abstraction throughout the code.

## Architecture after the refactor

```text
 rider / driver HTTP commands
             |
             v
 +---------------------------+
 | Spring Boot modular app   |
 | API -> domain services    |
 +-----+-----------+---------+
       |           |
       |           +--------------------+
       v                                v
 +---------------------+       +----------------------+
 | Redis               |       | PostgreSQL           |
 | latest driver state |       | requests / offers    |
 | grid membership     |       | assignments/payments |
 | reservations/leases |       | inbox / outbox       |
 +----------+----------+       +-----------+----------+
            ^                              |
            | atomic Lua                   | leased publisher
            |                              v
       matching path                    Kafka
                                           |
                                  +--------+--------+
                                  v                 v
                              payment          notification
                              consumer         consumer
```

The central storage rule is:

| Store | Question it answers | Examples |
|---|---|---|
| PostgreSQL | What durable business fact must survive? | request, offer, assignment, payment, inbox, outbox |
| Redis | What is true *right now* and may expire? | live location, availability, cell membership, reservation, matching lease |
| Kafka | What happened and who may react asynchronously? | assignment completed, payment result, notification trigger, telemetry |

This avoids two common category errors: using Kafka as a random-access location database,
or synchronously writing every coordinate into a durable profile row.

## Before-and-after review

| Area | Earlier weakness | Why it mattered | Current design |
|---|---|---|---|
| Project identity | `realtime-geo-dispatch` and an intermediate generic rename described implementation mechanisms | A reader could not tell which user problem the system solved | `ride-share-dispatch` names the scenario and `Driver` is used throughout the implementation |
| Reservation | Eligibility read and `SET NX` were separate | State could change between check and reservation | One Lua script validates status, assignment, service type, account status, freshness, then executes `SET NX PX` |
| Offer acceptance | Read reservation, then update other state | Two accepts or an expired/reissued reservation could race | Lua consumes the exact token and moves the driver to `OCCUPIED`; DB uniqueness fences durable ownership |
| Availability | A driver could be made available while occupied | The same driver could re-enter matching | Availability transition rejects `OCCUPIED` or an active assignment; completion releases only its own assignment |
| Eligibility | Service type and account status were not authoritative at reservation time | Candidate filtering alone cannot guarantee correctness | Hot state carries eligibility fields and reservation revalidates them atomically |
| Location index | A stale caller could update cell membership after losing the sequence race | Redis snapshot and cell index could disagree | Cell membership is updated from the sequence-winning Redis snapshot |
| Matching recovery | A committed `SEARCHING` request could be stranded after failure | A transient worker failure became permanent business state | Idempotent replay resumes matching under an expiring owner-token claim |
| Spatial search | Hot-cell membership could be read without a useful bound | One dense cell could dominate latency and memory | Incremental `SCAN`, ring bound, candidate cap, and oversample budget |
| Outbox publication | Row-lock reasoning did not cover network I/O after transaction exit | Another poller could race or a crash could strand work | Atomic expiring claim with `claimed_by`/`claim_until`; Kafka I/O occurs outside DB transaction |
| Consumer dedup | `processed_events` could commit before the side effect | A crash could permanently suppress an unperformed effect | Inbox marker and durable payment/notification preparation share one Postgres transaction |
| Payment retry | Provider memory and unbounded reconciliation obscured uncertain outcomes | Restart or timeout could duplicate or retry forever | Stable operation ID, durable provider ledger, claimed due batch, backoff+jitter, maximum attempts, `UNKNOWN` |
| API/operations | Weak validation, broad health details, stale benchmark implications | Invalid input and operational claims leaked through the edges | Validated DTOs, stable error envelope, protected health details, explicit metrics and unmeasured benchmark template |

## Deep dive 1: one driver has one owner

### The earlier race

A superficially reasonable implementation is:

```text
read driver state -> confirm AVAILABLE -> SET reservation NX
```

`SET NX` is atomic, but the whole decision is not. Between the read and the write, the
driver can become stale, occupied, suspended, or incompatible with the requested service.
The atomic write prevents two reservation keys, but it does not prove that the winner was
eligible when ownership was granted.

Offer acceptance had a second boundary problem:

```text
GET reservation -> update offer/assignment -> mark driver occupied
```

The reservation can expire and be reissued after `GET`. A stale accept must never consume
or delete the new owner's reservation.

### The current ownership protocol

Reservation is one Redis Lua operation:

```text
validate driver hash:
  status == AVAILABLE
  active_assignment_id is empty
  account_status == ACTIVE
  service_type matches
  last_seen is fresh
then:
  SET reservation:{driverId} <request+offer token> NX PX <ttl>
```

Acceptance uses the exact token stored on the durable offer. Its Lua operation verifies
that token still owns the reservation, rechecks freshness, deletes the reservation, and
marks the driver `OCCUPIED` with a deterministic assignment ID. A stale accept cannot
delete a reservation that has since been issued to another request.

PostgreSQL then commits the offer, matched request, and assignment. The partial unique
index

```sql
UNIQUE (driver_id) WHERE status IN ('CREATED', 'IN_PROGRESS')
```

is the durable backstop if Redis loses state or two cross-store paths interleave badly.
If the database transaction fails after Redis ownership transfer, compensation first
checks whether the deterministic assignment exists before releasing hot state. That
check prevents an uncertain client outcome from undoing a successful owner.

### Why both Redis and PostgreSQL?

Redis provides the low-latency, expiring online gate. PostgreSQL supplies a durable
business invariant. Neither alone handles the whole lifecycle:

- a database lock is a poor fit for geo candidate iteration and temporary offers;
- a Redis reservation is not a durable trip record and may disappear;
- a global distributed lock would add complexity without eliminating cross-store
  failure semantics.

The claim is not “an atomic transaction spans Redis and PostgreSQL.” It does not. The
claim is narrower and defensible: an atomic hot-state ownership protocol, a deterministic
handoff, compensation for the known gap, and a durable uniqueness fence.

## Deep dive 2: idempotency is not concurrency control

These solve different problems.

```text
same rider retries the same POST
    -> command idempotency

two legitimate ride requests compete for one driver
    -> resource concurrency control
```

Ride creation uses `(requester_id, idempotency_key)` as a durable unique key. The first
insert wins. A replay reads and returns the same request. A SHA-256 fingerprint of the
important payload distinguishes a retry from incorrect reuse of the same key for a
different origin, destination, or service type.

The uniqueness constraint is the correctness boundary. A `SELECT` before `INSERT` would
only improve an error message; it could not prevent two concurrent inserts.

The idempotency key is a **command identity**. The created request UUID is a **resource
identity**. Every outbox message has a separate **event identity**. Keeping these three
identities distinct makes retries and deduplication explainable.

## Deep dive 3: ordered current location and an approximate index

Drivers send `sequenceNumber` with each update. Redis accepts an update only when:

```text
incoming sequenceNumber > stored sequenceNumber
```

The server also records receive time. Client timestamps are useful telemetry but are not
trusted for ordering because client clocks drift and can be manipulated.

The accepted Redis snapshot is authoritative for updating grid membership. This detail
matters: if two requests arrive concurrently, the loser of the sequence comparison must
not later move the driver's cell using its stale coordinates.

Candidate discovery remains intentionally approximate:

```text
origin cell -> bounded neighboring rings -> bounded sample
            -> load current state -> discard stale/ineligible
            -> approximate distance/ETA -> top K
            -> authoritative reservation
```

An eventually consistent index may omit a candidate or return a stale candidate. That is
acceptable because discovery affects match quality, while the reservation boundary
decides correctness. Stale index entries are lazily removed.

## Deep dive 4: recovery requires ownership, not just a TTL

A TTL prevents a dead worker from holding a claim forever, but it does not identify the
current owner. Consider:

```text
worker A gets lease -> A pauses -> lease expires
worker B gets new lease -> A wakes and blindly deletes lease
```

A has just released B's valid claim. The refactor stores an owner token and releases only
when the token matches. This pattern is used for matching and outbox claims.

A ride request is durably created before matching begins. If matching fails or the worker
dies, the request remains `SEARCHING`. A replay of the same idempotent command returns the
same resource and may safely resume matching after acquiring the lease. The retry path
does not create another logical ride.

Recovery is bounded: search rings, candidate count, matching time, lease duration, and
offer duration all have configuration ceilings. No request expands forever looking for
capacity.

## Deep dive 5: outbox and inbox solve different halves

### Producer: state plus outbox

Assignment completion performs this in one PostgreSQL transaction:

```text
UPDATE assignment -> COMPLETED using expected version
INSERT AssignmentCompleted outbox event
COMMIT
```

If Kafka is unavailable afterward, completion remains durable and the event remains
pending. The publisher leases a bounded batch, sends outside the database transaction,
and marks a row published only after Kafka acknowledges it. A crash can cause a duplicate
publish; it cannot silently erase the pending row.

### Consumer: inbox plus durable preparation

Kafka is configured and documented as at-least-once. A consumer transaction inserts
`processed_events(event_id, consumer_name)` and prepares its durable side effect in the
same database transaction. If preparation fails, the inbox marker rolls back too, so the
redelivery is not suppressed.

The external effect is still a separate boundary. Notifications are simulated as durable
delivery rows. Payment uses a stable provider operation ID so retrying external I/O is
safe when the provider supports idempotency.

The system therefore does **not** promise global exactly-once delivery. The useful claim
is “at-least-once messages plus idempotent business effects where the downstream boundary
supports them.”

## Deep dive 6: timeout does not mean payment failure

The operation ID is deterministic:

```text
<assignmentId>:final-charge
```

A simulated provider can record the charge and then time out before the application sees
the response. Retrying with a new ID could double-charge. Retrying with the same ID lets
the provider return the existing result.

The fake provider ledger is durable in PostgreSQL, so restarting the application does not
erase the provider's memory of a charge. The reconciliation loop:

- claims a bounded due batch;
- increments attempt count;
- uses exponential backoff with deterministic jitter;
- retries the same operation ID;
- finishes in `SUCCEEDED` or `FAILED` for known outcomes;
- moves to `UNKNOWN` after the configured maximum for unresolved outcomes.

Assignment completion does not roll back when payment fails. The completed ride is
historical truth; payment failure is a new problem to reconcile.

## Deep dive 7: bounded work and observable behavior

The earlier implementation could describe backpressure without consistently enforcing
it. The refactor makes the important ceilings executable:

- maximum grid rings;
- maximum candidates and per-cell oversampling;
- matching deadline;
- reservation, offer, matching-claim, outbox-claim, and payment-claim TTLs;
- outbox and payment batch sizes;
- Kafka consumer concurrency and retry count;
- payment maximum attempts and backoff.

Metrics include location update counts/latency, stale updates, idempotency replays,
matching attempts/conflicts/timeouts, candidate counts, active reservations, assignment
completion, outbox backlog/failures, notification duplicates, and payment attempts,
results, and reconciliation.

These metrics do not prove production capacity. They make a load test diagnosable. Actual
capacity numbers belong only in [`BENCHMARK.md`](BENCHMARK.md) after a repeatable run.

## Evidence map

| Invariant or claim | Implementation boundary | Test evidence |
|---|---|---|
| One logical command creates at most one request | DB unique key + fingerprint | `DispatchRequestIdempotencyTest` |
| One reservation winner for one driver | Redis Lua `SET NX PX` | `DriverReservationConcurrencyTest` |
| Wrong owner cannot release a reservation | token-checked Lua delete | `DriverReservationConcurrencyTest`, `OfferServiceTest` |
| Occupied driver cannot re-enter matching | state transition checks + active assignment | `DriverOwnershipTest` |
| Older location cannot overwrite newer state | sequence-checked Redis update | `LocationOrderingTest` |
| Stale/wrong-service driver cannot commit a match | final reservation/accept validation | `MatchingServiceTest`, `OfferServiceTest` |
| One offer creates at most one assignment | deterministic ID + unique `offer_id` | `OfferServiceTest` |
| One OCC version has one winner | conditional `UPDATE ... WHERE version = ?` | `AssignmentServiceOccTest` |
| Completion and event are one durable operation | DB transaction + outbox insert | `AssignmentServiceOccTest` |
| Failed consumer work does not poison dedup | inbox + preparation transaction | `ConsumerAtomicityTest` |
| Duplicate event has one modeled effect | inbox and notification/payment uniqueness | `KafkaEventFlowTest` |
| Timeout and retry do not double-charge | stable operation ID + provider ledger | `PaymentServiceIdempotencyTest` |
| Search work is bounded in a hot cell | incremental scan + oversample cap | `SpatialIndexBoundTest` |
| Expired worker cannot release a new lease | owner-token matching claim | `DispatchRecoveryTest` |

The suite was last run successfully as **37 tests with 0 failures** against real
PostgreSQL, Redis, and Kafka containers. A separate end-to-end run exercised driver
creation, location, idempotent request replay, offer acceptance, assignment start and
completion, outbox publication, notification, and payment. This is correctness evidence,
not a throughput benchmark.

The verification pass also exposed a build-tooling problem rather than an application
failure: Testcontainers `1.21.3` negotiated an obsolete Docker API against Docker Engine
29. A temporary API flag proved the diagnosis; the repository then upgraded to `1.21.4`,
the compatible patch release, and removed that workaround from the normal build path.

## What remains intentionally incomplete

The current implementation should not be described as production complete. Known gaps
include:

- no authentication, authorization, tenant isolation, or abuse prevention;
- no real road-network ETA, mapping provider, or sophisticated spatial index;
- no WebSocket/push provider and no real payment provider;
- no cancellation/refund/dispute workflow;
- no CDC-based outbox, dead-letter operations UI, or automated reconciliation console;
- no multi-region ownership, Redis failover design, or hot-cell sub-sharding;
- no measured benchmark for the refactored build.

## Why the current version is better

“Better” here has a precise meaning:

- guarantees are attached to atomic database constraints, conditional updates, or Lua
  operations rather than check-then-act prose;
- leases contain owner identity, not only expiration;
- cross-store gaps are acknowledged and fenced rather than called transactions;
- retries preserve logical identity and have bounds;
- message delivery semantics are described as at-least-once;
- uncertain external outcomes remain uncertain;
- hot-path work has explicit ceilings and metrics;
- tests use real infrastructure for the races they claim to prove;
- documentation separates verified behavior, design intent, and known gaps.

The main lesson is that production-shaped code is not defined by how many infrastructure
products it names. It is defined by whether ownership, identity, ordering, durability,
recovery, and overload have explicit semantics at each boundary.

## Possible blog structure

This case study can become an article with the following arc:

1. **Hook:** `SET NX` was atomic, but the reservation decision was not.
2. **Context:** a small ride-share dispatch lab and its three storage responsibilities.
3. **Race:** show the old reservation and acceptance interleavings.
4. **Repair:** Lua reservation, deterministic ownership transfer, durable DB fence.
5. **Identity:** contrast idempotency key, resource ID, lease token, and event ID.
6. **Failure:** walk through worker death, Kafka duplication, and payment timeout.
7. **Bounds:** explain why a hot geo cell and unbounded retry are correctness-adjacent
   operational risks.
8. **Evidence:** show the concurrency, rollback, recovery, and timeout tests.
9. **Honest ending:** discuss the remaining Redis/Postgres boundary and what 10x/100x
   evolution would require.

The strongest headline is not “I built Uber.” It is: **I built a compact lab where the
hard distributed-systems claims can be raced, failed, retried, measured, and defended.**
