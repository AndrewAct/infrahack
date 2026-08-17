# Ride-share dispatch interview guide

Use this guide to present the project as an engineering decision story, not as a list of
technologies. The canonical details live in [`DESIGN.md`](DESIGN.md); the history and
rationale live in [`REFACTORING_CASE_STUDY.md`](REFACTORING_CASE_STUDY.md).

## The 30-second version

I built a modular Spring Boot ride-share dispatch backend that matches riders to nearby
fresh, eligible drivers and carries the trip through offer, assignment, completion,
payment, and notification. PostgreSQL stores durable business truth, Redis stores live
location and temporary ownership, and Kafka distributes durable events through an
outbox. The main work was making retries and races explicit: idempotent ride creation,
atomic driver reservation, sequence-ordered locations, optimistic assignment updates,
idempotent consumers, and payment reconciliation after uncertain timeouts.

## The 90-second version

The workload has two very different shapes. Driver locations arrive frequently and are
ephemeral, while ride requests and assignments are lower-volume business records that
must survive. I therefore keep durable profiles, requests, assignments, payments, and
event ledgers in PostgreSQL; the latest driver state, geo cells, and short reservations
in Redis; and asynchronous facts in Kafka.

Matching is coarse-to-fine. It searches a bounded number of grid cells, removes stale or
ineligible drivers, ranks a small top-K with an approximate ETA seam, and attempts an
atomic Redis reservation. The reservation script revalidates eligibility at commit time,
so the geo index may be eventually consistent without becoming the authority.

The hardest lifecycle boundary is accepting an offer. A token-checked Lua operation
consumes the exact reservation and marks the driver occupied; PostgreSQL then creates the
assignment, with a partial unique index preventing two active assignments for one driver.
Completion uses OCC and writes an outbox event in the same transaction. Kafka delivery is
at-least-once, so each consumer writes an inbox marker together with its durable business
effect. Payment uses a stable operation ID because a timeout may mean the charge happened.

The project has 37 integration tests against real PostgreSQL, Redis, and Kafka, including
reservation races, stale offers and locations, consumer rollback, OCC conflicts, and a
timeout/retry test proving one modeled charge.

## A five-minute walkthrough

### 1. Start with the workload, not the stack

Use the explicit estimate from the design document: if 300,000 online drivers update
every five seconds, location ingestion is roughly 60,000 updates per second. Label that
as an assumption, not measured traffic. The insight is that live location dominates write
traffic and should not update a durable profile row on every heartbeat.

### 2. Explain state ownership

```text
PostgreSQL: durable truth and constraints
Redis:      current state, geo membership, expiring ownership
Kafka:      asynchronous history and fan-out
```

Then state the principle: not every datum deserves the same durability or consistency.

### 3. Walk the synchronous path

```text
driver location -> ordered Redis snapshot -> geo cell
ride POST       -> durable idempotent request
                -> bounded candidates
                -> final eligibility validation + reservation
                -> pending offer
accept offer    -> reservation-to-occupied handoff
                -> durable assignment
```

Emphasize that Kafka is not required before matching can return.

### 4. Walk the asynchronous path

```text
assignment COMPLETED + outbox row --same DB transaction-->
outbox publisher -> Kafka -> inbox/dedup -> payment + notification
```

Explain where duplicates can occur and why that is acceptable.

### 5. End with failure semantics

Choose three failures:

- worker dies after reservation -> TTL releases capacity;
- Kafka fails after DB commit -> unpublished outbox row remains;
- provider charges then times out -> retry the same payment operation ID.

This is more compelling than ending with a framework inventory.

## Five decisions worth defending

### 1. Modular monolith instead of microservices

The learning goal is distributed-systems semantics, not deployment count. One application
keeps local execution, integration tests, and code navigation manageable while package
boundaries isolate location, matching, lifecycle, payment, and notification logic. At
10x scale, location ingestion, matching, and event consumers are natural extraction
candidates because their scaling and failure profiles differ.

### 2. Redis for live driver state, PostgreSQL for business truth

Redis matches the high-write, TTL-based, low-latency nature of live locations and
reservations. PostgreSQL provides durable transactions, unique constraints, indexes, and
OCC for requests and assignments. Persisting every coordinate in the driver profile would
create write amplification, row contention, and the wrong durability cost.

### 3. Approximate discovery, authoritative commit

The grid index is allowed to be stale because it proposes candidates; it never grants
ownership. The Lua reservation operation reloads and validates current driver state. This
is a general design pattern: tolerate inconsistency in discovery when the commit boundary
is authoritative.

### 4. Constraints and conditional writes instead of distributed locks

The design uses the narrowest primitive for each invariant:

- unique constraint for idempotent request creation;
- Redis `SET NX PX` inside Lua for temporary ownership;
- `UPDATE ... WHERE version = ?` for durable state transitions;
- partial unique index for one active assignment per driver;
- owner-token leases for recoverable work claims.

A distributed lock would not automatically make Redis and PostgreSQL one transaction,
and it would add lease, fencing, and availability problems of its own.

### 5. At-least-once plus idempotent effects, not “exactly once”

An outbox can be published more than once if a worker crashes after Kafka acknowledges but
before `published_at` is committed. Kafka can also redeliver. Consumers therefore use an
event ID and durable inbox. The downstream effect must also have an idempotency boundary,
such as a notification uniqueness key or payment operation ID.

## The refactoring story

This is a useful answer to “tell me about a time you improved an existing system.”

### Situation

The first version had the expected infrastructure and happy path, but a review showed
that some claims were stronger than their atomic boundaries. Reservation eligibility,
offer acceptance, consumer deduplication, and recovery contained check-then-act gaps.
The name also described a technical mechanism rather than an understandable product
scenario.

### Task

Keep the implementation within a one-day-MVP spirit while turning the important claims
into code and deterministic tests. Avoid hiding problems behind more services or a large
resilience framework.

### Action

I made a failure/invariant table and changed only the boundaries that enforced it:

- renamed the module to `ride-share-dispatch` and mapped generic domain terms to the
  concrete rider/driver story;
- replaced multi-step reservation and acceptance checks with token-aware Lua scripts;
- added a durable partial unique index as the final driver-ownership fence;
- made request, outbox, and payment recovery claims expiring, with owner tokens on the
  matching and outbox paths where delayed release can race a newer worker;
- moved Kafka inbox markers into the same transaction as durable side-effect preparation;
- added bounded payment reconciliation with stable provider identity and `UNKNOWN`;
- added real-infrastructure tests for races, rollback, stale state, and uncertain timeout;
- rewrote documentation to say at-least-once and to separate tests from benchmarks.

### Result

The refactored suite passed 37 tests against real PostgreSQL, Redis, and Kafka containers,
and a local end-to-end path reached payment and notification through the outbox. More
importantly, each core invariant now points to a specific primitive and test. I also kept
the residual Redis/PostgreSQL handoff and Docker/Testcontainers portability issue explicit
instead of claiming the MVP is production complete.

### Reflection

The key lesson was that an individually atomic command does not make a multi-step decision
atomic. I now review distributed workflows by writing the bad interleaving first, then
choosing the smallest authoritative boundary and a recovery rule for every side effect.

## Question bank with concise answers

### Why not store live location in PostgreSQL?

It is high-frequency, ephemeral state with TTL and freshness semantics. Putting it in the
profile row would amplify durable writes and contention. PostgreSQL still stores durable
driver identity and business records.

### Why Redis?

It supports low-latency current-state reads, TTLs, sets for grid membership, and atomic Lua
operations for reservation. Redis is not treated as durable trip history.

### Why Kafka?

It carries facts for asynchronous fan-out, retry, and replay. Payment and notification do
not need to block assignment completion.

### Why is Kafka not the location query database?

Kafka answers “what happened?” It is not optimized to retrieve the latest state of a
small set of nearby drivers on the synchronous request path.

### What is the source of truth for availability?

Redis hot state is authoritative for online matching eligibility at that moment. A final
reservation Lua script validates it. PostgreSQL is authoritative for durable assignment
ownership and fences active duplicates.

### What if the geo index is stale?

It may miss a good candidate or return a stale one, affecting quality or latency. It
cannot allocate an invalid driver because reservation revalidates current state.

### How do two requests avoid claiming one driver?

They race on one Redis Lua operation containing `SET ... NX PX`. Exactly one token wins;
the loser tries its next bounded candidate. The database separately forbids two active
assignments for the driver.

### What is API idempotency?

Multiple transport attempts for one logical command return one resource. Here the scope
is `(requester_id, idempotency_key)`, protected by a unique constraint and payload hash.

### What does `ON CONFLICT DO NOTHING` solve—and not solve?

It atomically resolves concurrent duplicate inserts. It does not reserve a driver, order
location updates, or prove that reused keys have equivalent payloads; those require
different mechanisms.

### What is OCC?

The update includes the version read by the caller. One writer increments it; a stale
writer affects zero rows and gets a conflict. OCC is attractive at low contention because
it avoids holding a DB lock during application or network work.

### OCC versus `SELECT FOR UPDATE`?

OCC detects a race and asks a caller to retry or fail. `SELECT FOR UPDATE` serializes
writers by holding a row lock inside a transaction. Pessimistic locking can be reasonable
for short, high-contention DB-only workflows, but not across Redis/Kafka/provider I/O.

### What does the transactional outbox solve?

It prevents durable state from committing without a recoverable record of the event that
must be published. It does not prevent duplicate publication.

### Why can Kafka redeliver?

The consumer may finish business work and fail before its offset is committed, or the
producer/publisher may retry after an uncertain acknowledgement. Delivery and processing
acknowledgement are separate failure points.

### How does the consumer tolerate duplicates?

It inserts `(event_id, consumer_name)` into a unique inbox table in the same transaction
as durable side-effect preparation. Only the first insert performs that preparation.

### Why does a payment timeout not mean failure?

The provider may have completed the charge before the response was lost. The application
must retry or reconcile the same logical operation, not create a new charge.

### What happens if Kafka is unavailable?

Driver updates and synchronous matching can continue while PostgreSQL and Redis are
healthy. Durable lifecycle events accumulate in the outbox. Payment and notification lag
until publication/consumption resumes.

### What happens if Redis is unavailable?

Live location, candidate lookup, reservation, and offer handoff degrade or fail. Durable
requests, assignments, payment records, inbox, and outbox remain in PostgreSQL. The MVP
does not attempt a dangerous fallback allocation from stale durable data.

### Why is straight-line distance not a real ETA?

Road topology, traffic, turn restrictions, and pickup access matter. The MVP uses cheap
distance to bound and rank a small candidate set behind an `EtaEstimator` seam; a real
routing call belongs only on that top-K set.

### Why not index every database column?

Every secondary index consumes storage and increases write amplification. The schema only
indexes demonstrated access patterns and rare pending work.

### Why integer cents for money?

Binary floating point cannot exactly represent many decimal amounts. Integer cents make
comparison, persistence, and idempotency checks deterministic for the MVP.

## Strong claims, weak claims, and claims to avoid

| Avoid saying | Better statement |
|---|---|
| “The system is exactly once.” | “Kafka is at-least-once; inbox keys and idempotent downstream identities make the modeled effects effectively once.” |
| “Redis and PostgreSQL update atomically.” | “The handoff is not a cross-store transaction; token validation, deterministic IDs, compensation, and a DB uniqueness fence bound the failure modes.” |
| “It always finds the nearest driver.” | “It performs bounded approximate discovery and ranking; the abstraction can replace straight-line distance with road ETA for top-K.” |
| “The benchmark proves it handles 60K QPS.” | “60K updates/s is a sizing assumption. The refactored build does not yet publish measured capacity.” |
| “A timeout means payment failed.” | “The outcome is uncertain until reconciliation using the same operation ID.” |
| “A TTL solves worker ownership.” | “A TTL guarantees eventual release; an owner token prevents an expired worker from releasing a newer lease.” |
| “Testcontainers makes tests environment-free.” | “It makes dependency versions reproducible once Docker is reachable; the daemon and socket remain host prerequisites. The project pins `1.21.4` for recent Docker Engine compatibility.” |

## A debugging story: `mvn clean install` and misleading logs

The Spring messages saying a test class has no nested `@Configuration` are informational;
Spring subsequently finds `RideShareDispatchApplication`. The real failure in the shown
stack trace occurs when `AbstractIntegrationTest` starts Testcontainers and cannot find a
usable Docker daemon.

There are two layers to diagnose separately:

1. **Discovery:** is Docker running, and does `DOCKER_HOST` point to the active context's
   socket rather than assuming `/var/run/docker.sock`?
2. **Compatibility:** does the pinned Testcontainers client support the installed Docker
   Engine API?

The failing build pinned Testcontainers `1.21.3`. An API override confirmed that Docker
Engine 29's minimum API, rather than Spring configuration, was the blocker. The durable
fix was to upgrade to Testcontainers `1.21.4`, whose release targets recent Docker Engine
changes, and return the normal build to `mvn clean install`. `DOCKER_HOST` may still be
needed when the active macOS context uses a non-default socket; that is discovery, not API
compatibility.

This is a useful interview lesson: identify the first causal error, ignore framework
bootstrap noise, distinguish environment discovery from version compatibility, and do not
turn a local workaround into an undocumented build requirement.

## Scaling discussion

### At 10x

- isolate location ingestion and matching worker pools from ordinary API traffic;
- pipeline/batch Redis work and measure hot-cell cardinality;
- partition Kafka by stable aggregate key while preserving per-key order;
- move outbox publication toward CDC or a higher-throughput leased poller;
- add real routing ETA only after coarse top-K retrieval;
- add operational tooling for poison events and payment reconciliation.

### At 100x / multi-region

- route a driver and nearby request to one authoritative geographic region;
- assign cell ownership rather than taking a global lock;
- sub-shard hot cells by `cell + hash(driver_id)` and support dynamic repartitioning;
- replicate durable business state across regions according to recovery objectives;
- make failover explicitly transfer epochs/ownership so two regions cannot allocate the
  same driver;
- batch telemetry into object storage/columnar formats for analytics.

The goal is not globally synchronous matching. It is geographically scoped ownership
with explicit failover.

## Code reading order

Read these files in order when preparing for a deep dive:

1. [`DESIGN.md`](DESIGN.md) — invariants and complete architecture.
2. [`V1__init_schema.sql`](../src/main/resources/db/migration/V1__init_schema.sql) — durable constraints, inbox, outbox, and payment ledger.
3. [`DriverOperationalStateStore.java`](../src/main/java/io/infrahack/ridesharedispatch/infrastructure/redis/DriverOperationalStateStore.java) — ordered location and state transitions.
4. [`DriverReservationStore.java`](../src/main/java/io/infrahack/ridesharedispatch/infrastructure/redis/DriverReservationStore.java) — atomic eligibility and ownership.
5. [`MatchingService.java`](../src/main/java/io/infrahack/ridesharedispatch/service/MatchingService.java) — bounded coarse-to-fine matching.
6. [`OfferService.java`](../src/main/java/io/infrahack/ridesharedispatch/service/OfferService.java) — Redis-to-PostgreSQL ownership handoff.
7. [`AssignmentService.java`](../src/main/java/io/infrahack/ridesharedispatch/service/AssignmentService.java) — OCC and completion outbox.
8. [`OutboxPublisher.java`](../src/main/java/io/infrahack/ridesharedispatch/infrastructure/kafka/OutboxPublisher.java) — leased at-least-once publication.
9. [`PaymentService.java`](../src/main/java/io/infrahack/ridesharedispatch/service/PaymentService.java) — stable identity, retry, and uncertainty.
10. [`DriverReservationConcurrencyTest.java`](../src/test/java/io/infrahack/ridesharedispatch/DriverReservationConcurrencyTest.java), [`ConsumerAtomicityTest.java`](../src/test/java/io/infrahack/ridesharedispatch/ConsumerAtomicityTest.java), and [`PaymentServiceIdempotencyTest.java`](../src/test/java/io/infrahack/ridesharedispatch/PaymentServiceIdempotencyTest.java) — evidence for the hardest claims.

## Final preparation checklist

Before using this project in an interview:

- be able to draw the three storage responsibilities without notes;
- explain one bad race interleaving and the exact atomic boundary that fixes it;
- distinguish command ID, resource ID, lease/reservation token, and event ID;
- say “at-least-once” and explain the duplicate path;
- explain why `UNKNOWN` is a more honest payment state than `FAILED` after timeout;
- state one residual weakness in the Redis/PostgreSQL ownership handoff;
- do not quote load capacity until [`BENCHMARK.md`](BENCHMARK.md) contains a reproducible
  result for the current commit;
- verify the local Docker/Testcontainers compatibility before a live demo.
