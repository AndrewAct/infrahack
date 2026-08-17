# Ride-share dispatch — design

## Problem

A rider creates a ride request at a location. Nearby drivers continuously publish their
position and availability. The backend must find an eligible driver, hold that driver
briefly, turn an accepted offer into one durable assignment, and finish the ride without
losing events or duplicating payment.

The implementation keeps reusable domain names: a rider is a `Requester`, a driver is a
`MobileAgent`, and a ride request is a `DispatchRequest`. The repository and module name
use **ride-share dispatch** so the scenario is obvious before those abstractions matter.

This is a learning system, not a complete ride-share product. Its goal is the smallest
production-shaped implementation in which concurrency, failure, and recovery decisions
can be executed and tested.

## Scope

In scope:

- driver profile, availability, heartbeat, and monotonically ordered live location;
- grid-based candidate discovery, bounded filtering/ranking, and atomic reservation;
- idempotent ride-request creation and recoverable synchronous matching;
- offer acceptance/rejection and assignment start/completion;
- optimistic concurrency control, transactional outbox, Kafka fan-out, and dedup inbox;
- simulated notification and an idempotent fake payment provider;
- Prometheus metrics, Testcontainers integration tests, and one k6 workload.

Out of scope: authentication and authorization, rider/driver applications, WebSockets,
real maps or traffic ETA, pricing/surge, fraud, real push/payment providers, multi-stop
routing, multi-region deployment, Kubernetes, and Terraform. Development credentials in
Compose are not production credentials.

## Requirements and bounds

Correctness takes priority over availability when ownership is uncertain. Work is also
bounded: matching explores at most `max-cell-search-rings`, ranks at most
`max-candidates`, observes `matching-timeout-ms`, and attempts one candidate at a time.
Reservations and offers expire. Outbox and payment workers claim finite batches with
leases. Kafka delivery retries are finite before a dead-letter topic.

The API remains synchronous for the first match because it makes the lab easy to run.
A retry of a still-`SEARCHING` request can repair a failed first attempt; a short database
matching lease prevents duplicate rematches for one request. The lease carries an owner
token, so a delayed old worker cannot release a lease that a newer worker acquired after
expiry.

## Traffic estimation

These are planning assumptions, not measurements:

```text
300,000 simultaneously online drivers
one location update every 5 seconds
= 60,000 location updates/second
```

At roughly 250 bytes per telemetry event before Kafka/filesystem overhead, that is about
15 MB/s or 1.3 TB/day uncompressed. If ride requests arrive at 1,000/s, live location
still dominates request count by about 60:1. That asymmetry is why location snapshots do
not update a PostgreSQL profile row. Historical telemetry could later be compacted in
batches into object storage and columnar files.

## Core invariants

1. An agent has at most one live reservation key.
2. One `(requester_id, idempotency_key)` creates at most one dispatch request.
3. One accepted offer creates at most one assignment, and one driver has at most one
   active durable assignment.
4. A sequence number less than or equal to the stored number cannot overwrite location.
5. An offline, stale, wrong-service, inactive-account, or occupied driver cannot pass
   final reservation validation.
6. Assignment completion and `AssignmentCompleted` outbox insertion commit together.
7. Duplicate Kafka delivery cannot duplicate the modeled durable consumer side effect.
8. Retrying one payment operation ID cannot create a second provider charge.
9. For one OCC version, at most one conditional state update succeeds.

These are local, enforceable guarantees. The system does **not** claim global exactly-once
execution across PostgreSQL, Redis, Kafka, and an external provider.

## Architecture

One deployable Spring Boot process contains explicit internal boundaries:

```text
 HTTP API
    |
    +---- AgentService ----------> Redis driver snapshot + cell membership
    |
    +---- DispatchRequestService -> PostgreSQL request + outbox
    |          |
    |          +---------------> MatchingService -> Redis reservation
    |
    +---- OfferService ----------> Redis ownership transfer + PostgreSQL assignment
    |
    +---- AssignmentService -----> PostgreSQL OCC + outbox
                                      |
                                      v
                                  OutboxPublisher
                                      |
                                      v
                                    Kafka
                                  /       \
                                 v         v
                           payment inbox  notification inbox
                                 |         |
                                 v         v
                            payment row  delivery row
```

The modular monolith keeps a one-day build runnable and debuggable. Location/matching and
event consumers can be split into independent deployments later without changing their
storage contracts.

## State ownership

| Store | Responsibility |
|---|---|
| PostgreSQL | Durable business truth: profiles, requests, offers, assignments, payments, notification deliveries, consumer inbox, outbox |
| Redis | Current operational truth: latest location, sequence, freshness, availability, service/account eligibility, cell membership, active assignment, expiring reservation |
| Kafka | Facts that happened: fan-out, replay, telemetry, asynchronous module propagation |

Kafka answers “what happened?”, Redis answers “what is true right now?”, and PostgreSQL
answers “what business fact must survive?”. Kafka is not an online nearby-driver query
database, and Redis is not the ledger of completed rides or money.

## State machines

```text
Driver:      OFFLINE -> AVAILABLE -> (reserved by TTL key) -> OCCUPIED -> AVAILABLE
Request:     SEARCHING -> MATCHED | CANCELLED | EXPIRED
Offer:       PENDING -> ACCEPTED | REJECTED | EXPIRED
Assignment:  CREATED -> IN_PROGRESS -> COMPLETED
                         \-----------> CANCELLED
Payment:     CREATED -> PROCESSING -> SUCCEEDED | FAILED | UNKNOWN
```

`RESERVED` is not written to the durable profile. The reservation key is the temporary
hold. `UNKNOWN` means repeated provider calls could not establish the outcome; it is not
silently converted to `FAILED`.

## API

| Method | Endpoint | Meaning |
|---|---|---|
| POST | `/agents` | Register durable driver profile |
| POST | `/agents/{agentId}/availability` | Go available/offline; occupied drivers cannot go available |
| POST | `/agents/{agentId}/location` | Publish current position and sequence |
| POST | `/dispatch-requests` | Create/replay a ride request and attempt matching |
| GET | `/dispatch-requests/{requestId}` | Read request and latest offer |
| POST | `/offers/{offerId}/accept` | Accept live offer and create assignment |
| POST | `/offers/{offerId}/reject` | Reject live offer and release its reservation |
| POST | `/assignments/{assignmentId}/start` | OCC transition to `IN_PROGRESS` |
| POST | `/assignments/{assignmentId}/complete` | Durable idempotent completion |
| GET | `/assignments/{assignmentId}` | Read assignment |
| GET | `/actuator/health` | Health without public component details |
| GET | `/actuator/prometheus` | Prometheus exposition |

Request DTOs validate UUIDs, string lengths, latitude/longitude ranges, finite numbers,
nonnegative sequence numbers, and a bounded nonblank `Idempotency-Key`. Errors use one
stable JSON envelope. Identity is accepted from the request because auth is out of scope.

## Database schema and access patterns

Flyway creates eight business/infrastructure tables plus a durable fake-provider ledger.
UUIDs are distributed business IDs, money is `BIGINT` cents, time is `TIMESTAMPTZ`, and
states are explicit strings.

Important constraints and indexes:

- `UNIQUE(requester_id, idempotency_key)` is the idempotent command gate.
- `UNIQUE(assignments.offer_id)` prevents duplicate assignment creation per offer.
- a partial unique index on active assignments prevents two `CREATED`/`IN_PROGRESS`
  assignments for one driver even if Redis state is lost at an unfortunate time.
- a partial unique pending-offer index prevents concurrent pending offers for one request.
- requester and agent assignment-history indexes support time-ordered reads.
- partial due-payment and pending-outbox indexes serve worker scans.
- `(event_id, consumer_name)` deduplicates each independent Kafka projection.
- `(event_id, recipient_id, channel)` deduplicates notification deliveries.

Secondary indexes speed reads but amplify every write and consume memory. The migration
indexes known access paths rather than every column. Coordinate columns are `DOUBLE
PRECISION` because approximate geography tolerates it; money does not.

## Live location path

The client sends latitude, longitude, `sequenceNumber`, and optionally its timestamp. The
server records its own receive time. A Redis Lua script compares sequence number and
updates the hash atomically:

```text
new sequence <= stored sequence  -> ignore as stale/duplicate
new sequence > stored sequence   -> replace snapshot and refresh TTL
```

Client time is diagnostic only; clock skew makes it unsuitable as the ordering source.
The stored snapshot includes cell, status, service/account fields, last-seen time,
sequence, and optional active assignment. The cell index is updated when a driver crosses
a boundary. Going offline removes membership; lazy matching cleanup removes expired or
missing members that remain after crashes.

Redis snapshot TTL is memory reclamation. Matchability uses the stricter configurable
`last_seen` cutoff. PostgreSQL is never on this hot path. A best-effort Kafka telemetry
stream could be added after the snapshot update; matching must not wait for it.

## Spatial search and matching

`SpatialIndex` hides the MVP grid implementation. `RedisGridSpatialIndex` calculates a
coarse cell, scans that cell and bounded neighbor rings, and stops after a bounded
oversample budget. It uses incremental `SCAN`, not `SMEMBERS`, so a hot cell cannot force
one unbounded response.

```text
origin cell
  -> bounded neighboring cells
  -> load current driver snapshots
  -> discard missing/stale/offline/occupied/wrong-service/inactive drivers
  -> approximate distance/ETA rank
  -> top K
  -> atomic eligibility recheck + reservation attempt
```

Discovery is deliberately approximate and eventually consistent. The authoritative Lua
reservation step repeats status, active-assignment, service, account, and freshness
checks in the same operation as `SET NX PX`. A stale index can create false candidates,
but cannot commit an ineligible driver. Losers try the next ranked candidate until the
candidate list or matching deadline is exhausted.

Straight-line distance is not road travel time. `EtaEstimator` is a seam; the MVP uses a
distance approximation. Production would use cheap cells to obtain a small top-K set,
then apply road-network ETA, traffic, direction, and driver preferences only to that set.

## Reservation and ownership transfer

Reservation identity is the offer token, not merely the request ID:

```text
Lua reserve:
  validate current driver eligibility
  SET reservation:{driverId} token NX PX reservationTtl

Lua accept:
  require exact token and fresh eligible snapshot
  delete reservation
  set status=OCCUPIED and activeAssignmentId=deterministicAssignmentId
```

The offer TTL is validated to be no longer than the reservation TTL. Reject/expiry uses a
compare-and-delete script so an old offer cannot delete a newer reservation after its own
key expires. Acceptance uses an assignment ID deterministically derived from the offer,
which makes concurrent/retried accepts converge.

Redis ownership is transferred before the database transaction. If the database
transaction fails and no assignment exists, compensation makes the driver available only
when the provisional assignment ID still owns the state. This ordering favors safety:
failure may temporarily reduce capacity, but does not knowingly allocate one driver twice.
PostgreSQL's active-assignment unique index is the durable final fence. A production system
would add a periodic Redis/PostgreSQL ownership reconciler; the MVP repairs completion on
an idempotent repeated complete call.

## Idempotency lifecycle

`Idempotency-Key` is scoped by requester. The service hashes the logical payload, attempts
`INSERT ... ON CONFLICT DO NOTHING`, then reads the winner:

```text
same requester + same key + same hash       -> original request
same requester + same key + different hash  -> 409 conflict
```

The unique constraint, not a `SELECT` pre-check, decides the race. Request creation and
`DispatchRequestCreated` outbox insertion share one transaction. Matching happens after
commit, outside a database transaction. If it fails or the process dies, the durable
request remains `SEARCHING`; replay tries matching again after acquiring a short matching
lease and expiring any stale pending offer.

Idempotency and concurrency control solve different problems:

```text
same logical POST repeated                 -> idempotency
two valid riders compete for one driver    -> reservation/CAS concurrency control
```

`ON CONFLICT DO NOTHING` solves only the unique insert race. It does not reserve a driver,
verify payload equivalence, coordinate Redis, or provide global exactly-once execution.

## Assignment and optimistic concurrency

Start and completion issue a conditional update:

```sql
UPDATE assignments
SET status = ?, version = version + 1
WHERE assignment_id = ? AND version = ?;
```

One row means success; zero means a stale writer or illegal concurrent transition. OCC is
attractive when contention is normally low and no database lock should be held during
network I/O. `SELECT ... FOR UPDATE` is reasonable for short, high-contention,
single-database transactions, but must never be held while calling Redis, Kafka, or a
payment provider.

Completion and its outbox row are one transaction. A repeated completion returns the
same completed assignment, creates no second outbox event, and retries best-effort Redis
availability repair. Payment success is not a precondition: a completed ride remains
completed even if charging later fails or is uncertain.

## Transactional outbox and Kafka

The dual-write problem is avoided as follows:

```text
BEGIN
  UPDATE assignment -> COMPLETED using OCC
  INSERT outbox event AssignmentCompleted
COMMIT

bounded outbox worker:
  atomically claim rows with lease + SKIP LOCKED
  publish to Kafka outside DB transaction
  mark published if this worker still owns claim
```

No database lock is held during Kafka I/O. If a publisher crashes, the claim expires. If
Kafka accepts an event but the publisher dies before marking it, the event is published
again. This is intentional at-least-once publication.

Kafka consumers use separate payment and notification groups. Each handles only relevant
events and inserts `(event_id, consumer_name)` with `ON CONFLICT DO NOTHING`. Crucially,
the inbox marker and durable side effect commit in one PostgreSQL transaction. A failure
rolls both back so redelivery can retry. The payment consumer creates a durable payment in
that transaction, then performs provider I/O outside it; the scheduler recovers a crash in
between.

At-least-once delivery plus an idempotent transactional consumer produces an
effectively-once modeled database side effect. It does not turn arbitrary external calls
into globally exactly-once operations.

## Notification

`AssignmentCompleted` produces a notification-delivery row and logs simulated delivery.
The inbox transaction plus notification unique constraint prevents duplicate rows. A real
foreground app would receive updates over a persistent WebSocket/stream; a background app
would use an external mobile push provider. Neither is implemented.

## Payment

Completion asynchronously creates the operation `<assignmentId>:final-charge`. The fake
provider stores its own durable idempotency ledger keyed by this operation ID. Reusing the
ID returns the prior charge result and cannot add a second charge; reusing it with another
amount is rejected.

A provider may perform the charge and then simulate a timeout. The application leaves the
payment `PROCESSING`, schedules the same operation ID with exponential backoff and stable
jitter, and later learns the ledger's result. A bounded claim batch prevents concurrent
workers from processing the same attempt. After `max-attempts`, the state becomes
`UNKNOWN` and emits `PaymentUncertain`; manual/provider reconciliation is then required.

This teaches the critical rule: **timeout does not mean the operation failed**. Minting a
new payment identity on retry is the double-charge bug.

## Consistency model

| Data | Expectation |
|---|---|
| Driver profile | Durable |
| Ride request and offer | Durable |
| Assignment state | Strong business consistency through constraints/OCC |
| Payment | Durable, strongly identified, idempotent provider operation |
| Active driver ownership | Atomic Redis handoff plus durable DB uniqueness fence |
| Live location | Eventually consistent and fresh enough |
| Cell candidate index | Eventually consistent, commit-time validation authoritative |
| Notification | At-least-once processing with durable dedup |
| Telemetry/analytics | Asynchronous |

Not every datum deserves the same consistency or durability. Approximate discovery is
safe when authoritative validation occurs at commitment.

## Failure modes

| Failure | Behavior |
|---|---|
| Duplicate API request | Unique idempotency key returns original resource; different payload conflicts |
| Two rides want one driver | Atomic Lua eligibility + `SET NX PX`; only one reservation wins |
| Candidate index is stale | Candidate may be examined, but final Lua validation rejects it and lazy cleanup removes it |
| Out-of-order location | Sequence CAS ignores it |
| Driver stops heartbeats | `last_seen` cutoff excludes it; snapshot TTL eventually removes it |
| Matcher dies after reserve | Reservation TTL eventually releases capacity; no durable offer may exist |
| Request commit succeeds before match | Request remains `SEARCHING`; replay rematches under a lease |
| Offer DB insert fails after reserve | Exact-token reservation is released |
| Accept Redis step succeeds, DB fails | Assignment-ID ownership compensation runs; DB active-owner constraint remains final fence |
| Completion commits, Redis is down | Ride stays completed; driver remains conservatively occupied until repair/replay |
| DB commit succeeds, Kafka publish fails | Outbox remains pending and is retried |
| Kafka redelivers | Transactional inbox and business unique constraints deduplicate |
| Consumer dies after inbox claim | Inbox and side effect roll back together, or durable payment exists for scheduler recovery |
| Kafka unavailable | HTTP location/matching and durable lifecycle continue; outbox grows and async payment/notification lag |
| Redis unavailable | Location, availability, matching, and ownership transfer fail; durable requests/assignments/payments remain readable in PostgreSQL |
| Payment times out | Same provider operation ID is reconciled; bounded retries can end `UNKNOWN` |
| Concurrent durable update | OCC version makes at most one writer succeed |
| Hot geographic cell | MVP scans a bounded sample; production adds cell+hash sub-shards, ownership, and dynamic repartitioning |

The safe response to uncertain driver ownership is reduced availability, not a second
allocation. Some cross-store repairs are best effort in the MVP and are listed below.

## Backpressure and overload

Configurable bounds are validated at startup: cell rings, candidates, matching timeout,
reservation/offer TTL, outbox batch/claim TTL, payment batch/attempts/backoff/claim TTL,
and Kafka concurrency/retry count. Search never expands forever. No capacity within the
bound leaves the request `SEARCHING` for a later idempotent retry. Payment retry uses
exponential backoff and deterministic jitter. Kafka poison messages exhaust a small retry
budget and go to a dead-letter topic instead of blocking a partition forever.

At higher load, admission control should return 429 before exhausting HTTP threads or DB
connections. Separate pools and deployment units for location and durable commands are a
future step; a giant resilience framework is unnecessary for this lab.

## Observability

Prometheus exposes counters/gauges for accepted/stale locations, requests and idempotent
replays, match attempts/candidates/conflicts/failures/timeouts, active reservations,
completed assignments, pending outbox and publish failures, notification delivery and
duplicates, and payment attempts/success/failure/reconciliation. Location and matching
timers publish histograms for percentile analysis.

Structured key/value logs identify stale location, reservation conflict/expiry, replay,
OCC conflict, Kafka failure, payment uncertainty, and reconciliation. Health details are
not publicly disclosed by default. Production would add trace IDs, alert thresholds,
consumer lag, Redis command latency, and Hikari pool saturation.

## Scaling at 10x and 100x

At 10x, separate the hot location/matching deployment from durable lifecycle APIs;
partition Kafka by agent/assignment identity; add Redis Cluster cell ownership; batch or
sample telemetry; horizontally scale leased outbox/payment workers; and use a real routing
service for top-K ETA.

At 100x, route by authoritative geographic region. A region owns drivers physically active
there and requests are routed to it. Within a region, split hot cells by `cell + hash`
sub-shards and dynamically repartition ownership. Keep regional failure isolation,
cross-region durable replication, and an explicit handoff protocol when a driver crosses
regions. This avoids a global distributed lock for every match.

Failover must choose a single operational owner before matching resumes. Durable business
data can replicate across regions with clearly stated RPO/RTO; live locations can be
reconstructed from fresh heartbeats.

## Trade-offs and alternatives rejected

- **Modular monolith over microservices:** one-day build, one local process, direct tests;
  boundaries still show future split points.
- **Redis over PostgreSQL for location:** high write rate, TTL, cell sets, and low latency;
  durable profile rows stay cold.
- **Kafka over Redis for events:** Kafka provides replay/fan-out; Redis serves current state.
- **No Kafka gate in matching:** consuming an event before matching adds latency and makes
  Kafka availability a dependency of the online decision.
- **OCC over pessimistic locks:** low expected contention and short DB transactions.
- **Unique/conditional writes over pre-checks:** correctness belongs in atomic storage
  primitives, not check-then-act application code.
- **No general distributed lock:** constraints, CAS, Redis atomic scripts, leases, and
  partition ownership are narrower and easier to reason about.
- **No global exactly-once claim:** retries and partial failure cross system boundaries;
  stable identities and idempotent effects are the honest contract.
- **Grid over sophisticated GIS:** enough to teach the pipeline behind a replaceable
  `SpatialIndex` interface.

## Known MVP gaps

- Reject/expiry does not proactively enqueue rematching; a repeated idempotent create can
  trigger it while the request remains `SEARCHING`.
- There is no scheduled cross-store reconciler for completed assignments whose Redis
  availability repair keeps failing, or for provisional occupancy after a process crash
  at the narrow Redis-before-DB boundary.
- Cancellation endpoints and automatic request expiry are not exposed.
- Kafka location telemetry, historical object storage, route insertion, road ETA, auth,
  and real external providers are intentionally absent.
- The outbox has no retention/archival job, and the dead-letter topic has no operator UI.
- The load test is a behavior probe, not a capacity claim; the refactored build has not
  been re-benchmarked.

## Interview challenge ladder

**Why is location not in the profile row?** Its high-rate ephemeral writes would create
WAL, cache, and index pressure on durable business data.

**Why Redis?** It provides low-latency hashes/sets, TTL, and atomic scripts for current
operational state.

**Why Kafka?** Durable asynchronous fan-out, replay, and independent consumer groups.

**Why is Kafka not the location query database?** A log is optimized for ordered streams,
not random nearby-current-state queries.

**What is the source of truth for availability?** Redis is the online operational source;
PostgreSQL active-assignment uniqueness is the durable safety fence.

**What if the geo index is stale? Why may discovery be eventually consistent?** Staleness
only changes candidates. Atomic commit-time eligibility decides ownership.

**How do two requests avoid claiming one driver?** One Redis Lua operation revalidates
eligibility and executes `SET NX PX`; the DB also rejects two active assignments.

**What is OCC?** A conditional update using the version originally read; zero updated rows
means someone else changed it.

**OCC versus `SELECT FOR UPDATE`?** OCC avoids held locks when conflicts are uncommon.
Row locks can suit a short, hot, database-only transaction.

**Why not distributed locks?** Narrow storage constraints/CAS solve each invariant with
fewer leases, failure modes, and fencing-token requirements.

**What does `ON CONFLICT DO NOTHING` solve? What does it not solve?** It atomically elects
one unique insert. It does not compare payloads, reserve drivers, or coordinate stores.

**What is API idempotency?** Multiple transport attempts for one logical command converge
on one result.

**Why does timeout not imply failure?** The remote side may have committed before the
response was lost.

**How does payment retry avoid duplicate charge?** Every retry reuses
`assignmentId:final-charge`, which is unique locally and in the provider ledger.

**Command ID, resource ID, event ID?** The idempotency key identifies an intended command;
the request/assignment ID identifies durable state; each emitted fact gets its own event ID.

**Why can Kafka redeliver?** Crashes, rebalances, and a failure between processing and
offset commit can replay a record.

**How do consumers tolerate duplicates?** Inbox insertion and the durable projection share
one transaction, with downstream business uniqueness as another fence.

**What does the transactional outbox solve?** The DB-state/Kafka dual-write gap. It does
not guarantee a single Kafka publication.

**What happens if Kafka is unavailable?** Online Redis matching and PostgreSQL lifecycle
continue; outbox and asynchronous effects lag.

**What happens if Redis is unavailable?** Live driver operations and matching stop safely;
durable business reads and already-created records remain.

**How are out-of-order coordinates handled?** Per-driver sequence CAS; server receipt time
drives freshness.

**How are stale drivers removed?** Eligibility cutoff excludes them, key TTL reclaims
snapshots, and matching lazily removes dead cell members.

**What if a worker crashes after reservation?** The reservation expires automatically.

**Why is nearest geometric distance not always best?** Roads, direction, traffic, pickup
constraints, and driver suitability affect travel time.

**Why coarse candidates before expensive ETA?** It avoids calling costly routing for the
whole fleet while preserving good candidates.

**How would a hot cell be sharded?** Add hash sub-shards beneath the cell, assign owners,
sample bounded candidates across shards, and repartition dynamically.

**How would this evolve across regions?** One authoritative operational region per driver,
regional request routing, explicit ownership handoff, and durable replication.

**What workload dominates QPS and why?** Location, because every online driver reports
every few seconds while rides are much less frequent.

**Which data needs strong consistency?** Request identity, assignment ownership/state,
money identity/result, and state-event atomicity.

**Which data may be eventual?** Location snapshots, candidate membership, notification,
telemetry, and asynchronous publication.

**Why not floating point for money?** Binary fractions and repeated rounding do not model
exact cents; integer minor units do.

**Why not index every column?** Each index adds storage, cache pressure, and write
amplification; index observed access paths.

**UUID versus BIGINT?** UUIDs support decentralized creation and hide row counts, at the
cost of larger, less cache-friendly indexes. BIGINT is smaller and ordered but needs an ID
allocation strategy.

**What changes at 10x?** Split hot paths, partition cells/events, batch telemetry, and
scale leased workers.

**What changes at 100x?** Geographic ownership, hot-cell sub-sharding, regional isolation,
explicit failover, and cross-region durable replication.
