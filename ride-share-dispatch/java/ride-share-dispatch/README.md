# Ride-share dispatch — Java implementation

This directory contains the runnable Java implementation of the
[`ride-share-dispatch`](../..) InfraHack module. It models a rider requesting a trip, a
nearby driver receiving and accepting an offer, an assignment progressing to completion,
and payment and notification continuing asynchronously.

This is a compact infrastructure lab, not a production ride-share product. Its purpose is
to make the difficult decisions around ownership, retries, ordering, durability, partial
failure, and overload executable and testable.

The outer [module README](../../README.md) introduces the problem and the multi-language
layout. This README is the operating guide for the Java project: stack choices, source
layout, local startup, API walkthrough, tests, configuration, observability, and common
failures.

## Quick start

With JDK 25, Maven, and Docker available:

```bash
cd java/ride-share-dispatch
docker compose up -d --wait
mvn -DskipTests clean package
java -jar target/ride-share-dispatch.jar
```

Then, from another terminal:

```bash
curl -fsS http://localhost:8080/actuator/health
```

This path starts the service but deliberately skips the Docker-backed correctness suite.
Use [Run the test suite](#run-the-test-suite) for the intended build gate and its current
Testcontainers compatibility note. Use [Complete HTTP walkthrough](#complete-http-walkthrough)
to exercise a full ride.

## What runs in this project

One Spring Boot process contains several explicit internal modules:

```text
HTTP API
   |
   +--> agent service --------> Redis live driver state + grid cells
   |
   +--> dispatch/matching ----> Redis reservation + PostgreSQL request/offer
   |
   +--> offer/assignment -----> PostgreSQL durable lifecycle
   |                                  |
   |                                  +--> transactional outbox
   |                                             |
   +---------------------------------------------+--> Kafka
                                                         |
                                               +---------+----------+
                                               v                    v
                                            payment            notification
```

It is a modular monolith: one build, one JVM, one deployment unit, and clear code
boundaries that could be extracted later if traffic or ownership justified it. Splitting
the MVP into many services would make local execution and cross-boundary testing harder
without removing the underlying Redis/PostgreSQL/Kafka consistency problems.

## Technology stack and decisions

| Technology | Responsibility | Why it is used here | Important trade-off / rejected default |
|---|---|---|---|
| Java 25 | Runtime and language | Records and immutable value types keep domain messages explicit; modern JVM tooling supports a realistic service | Requires a Java 25 toolchain; this lab does not optimize for older runtimes |
| Spring Boot 4.1 | HTTP, validation, dependency wiring, scheduling, health | Small amount of framework code for a runnable service and familiar operational endpoints | One framework process is intentional; no microservice scaffolding |
| Maven | Reproducible Java build and dependency management | Matches the repository convention and Spring Boot BOM | The integration suite requires Docker because tests use Testcontainers |
| `JdbcTemplate` | Durable repositories and explicit SQL | Makes `ON CONFLICT`, OCC predicates, partial indexes, and outbox claims visible | JPA/Hibernate would reduce CRUD boilerplate but hide the SQL this lab exists to teach |
| PostgreSQL 16 | Durable business truth | Transactions, unique constraints, conditional updates, JSONB outbox payloads, and useful indexes | It does not receive every live coordinate; that write rate and TTL workload belong elsewhere |
| Redis 7 | Latest driver state, grid membership, reservations, leases | Low-latency current-state access, TTL, sets, and atomic Lua scripts | Redis state is ephemeral and cannot replace durable assignment history |
| Kafka 3.8, KRaft | Asynchronous event propagation | Decouples completion from payment/notification and demonstrates redelivery handling; the outbox is the recovery point before publication | Kafka is not on the synchronous matching path and the local single broker is not a production durability topology |
| Flyway | Schema creation and evolution | The database shape and constraints are versioned beside the code | The MVP currently has one initial migration rather than a long compatibility history |
| Micrometer + Actuator | Metrics and health endpoints | Gives Prometheus-compatible evidence for latency, conflicts, backlog, and failures | No Grafana dashboard or alert policy is included |
| JUnit + Spring Test + Testcontainers | Correctness and concurrency tests | Races and transaction behavior run against real PostgreSQL, Redis, and Kafka | Slower and dependent on a working Docker daemon, but mocks would not prove these invariants |
| k6 | Mixed HTTP workload | Generates frequent location updates plus lower-rate trip creation and lifecycle traffic | The script exposes behavior; it does not create a production capacity claim by itself |

The storage rule is deliberately simple:

```text
PostgreSQL = what durable business fact must not disappear?
Redis      = what is true right now and may expire?
Kafka      = what happened and who may react asynchronously?
```

See [DESIGN.md](docs/DESIGN.md) for the complete consistency and failure-mode analysis.

## Project layout

```text
ride-share-dispatch/
├── pom.xml
├── docker-compose.yml
├── src/main/java/io/infrahack/ridesharedispatch/
│   ├── api/                    HTTP contracts and error mapping
│   ├── domain/                 IDs, value objects, entities, and states
│   ├── service/                matching and lifecycle orchestration
│   ├── repository/             explicit PostgreSQL access
│   ├── infrastructure/redis/   live state, spatial cells, Lua ownership
│   ├── infrastructure/kafka/   outbox publisher and consumers
│   ├── config/                 typed bounds and infrastructure wiring
│   └── observability/          Micrometer counters, timers, and gauges
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/           Flyway schema
├── src/test/                   real-infrastructure integration tests
├── load-tests/                 k6 mixed workload
└── docs/                       design, benchmark, refactor, interview notes
```

Interfaces exist only at meaningful replacement seams: `SpatialIndex`, `EtaEstimator`,
and `PaymentProvider`. Repositories are concrete because there is one deliberate storage
implementation and the SQL is part of the lesson.

## Prerequisites

- JDK 25
- Maven (3.9 recommended)
- Docker with Compose v2
- `curl` for endpoint checks
- optional: `jq` for the copy/paste walkthrough
- optional: k6 for load testing

Confirm the local toolchain:

```bash
java -version
mvn -version
docker version
docker compose version
```

On macOS, select Java 25 for the current shell if necessary:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Bring up the application

Run all commands in this directory:

```bash
cd java/ride-share-dispatch
```

### 1. Start infrastructure

```bash
docker compose up -d --wait
docker compose ps
```

Compose starts three single-node development dependencies:

| Dependency | Address | Local purpose |
|---|---|---|
| PostgreSQL | `localhost:5432` | durable state, inbox, outbox, fake-provider ledger |
| Redis | `localhost:6379` | live state, cells, reservations, short claims |
| Kafka | `localhost:9092` | domain events and asynchronous consumers |

All published ports bind to `127.0.0.1`. Credentials and plaintext Kafka are intentionally
development-only. Do not expose this Compose stack to an untrusted network.

### 2. Build the executable JAR

To compile and package without running the Docker-backed tests:

```bash
mvn -DskipTests clean package
```

The JAR is written to:

```text
target/ride-share-dispatch.jar
```

### 3. Start the JVM

```bash
java -jar target/ride-share-dispatch.jar
```

Flyway applies the schema automatically during startup. No manual SQL command is needed.
For an edit/run development loop, this is equivalent:

```bash
mvn spring-boot:run
```

### 4. Verify health and metrics

From another terminal:

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/prometheus | head
```

Expected health status:

```json
{"status":"UP"}
```

Unauthenticated health output intentionally does not expose component details.

## Complete HTTP walkthrough

This walkthrough requires `jq`. Keep the same shell open so the generated IDs remain
available. The production-shaped defaults are intentionally short: location freshness is
30 seconds, the reservation is 30 seconds, and the offer is 20 seconds. Either run steps
2–5 without pausing, or restart the app with relaxed values for a manual demo:

```bash
DISPATCH_LOCATION_FRESHNESS_SECONDS=300 \
DISPATCH_RESERVATION_TTL_SECONDS=180 \
DISPATCH_OFFER_TTL_SECONDS=120 \
java -jar target/ride-share-dispatch.jar
```

The reservation remains longer than the offer so a still-valid offer does not naturally
outlive its ownership token.

### 1. Register a driver

```bash
AGENT_JSON=$(curl -fsS -X POST http://localhost:8080/agents \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Driver One","serviceType":"STANDARD"}')
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -r '.agentId')
printf 'agent=%s\n' "$AGENT_ID"
```

Registration writes the durable profile to PostgreSQL. It does not put frequently
changing coordinates in that row.

### 2. Make the driver available and publish a fresh location

```bash
curl -fsS -X POST "http://localhost:8080/agents/$AGENT_ID/availability" \
  -H 'Content-Type: application/json' \
  -d '{"available":true}'

curl -fsS -X POST "http://localhost:8080/agents/$AGENT_ID/location" \
  -H 'Content-Type: application/json' \
  -d '{
    "latitude": 37.0000,
    "longitude": -122.0000,
    "sequenceNumber": 1,
    "clientTimestamp": "2026-08-17T12:00:00Z"
  }'
```

The location response should be `{"result":"ACCEPTED"}`. Repeating sequence number `1`
returns `STALE` and cannot overwrite the accepted snapshot.

### 3. Create a ride request

```bash
REQUESTER_ID=$(uuidgen)
IDEMPOTENCY_KEY=demo-ride-001

RIDE_JSON=$(curl -fsS -X POST http://localhost:8080/dispatch-requests \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{
    \"requesterId\": \"$REQUESTER_ID\",
    \"serviceType\": \"STANDARD\",
    \"originLat\": 37.0001,
    \"originLng\": -122.0001,
    \"destLat\": 37.0200,
    \"destLng\": -122.0200
  }")

REQUEST_ID=$(printf '%s' "$RIDE_JSON" | jq -r '.requestId')
OFFER_ID=$(printf '%s' "$RIDE_JSON" | jq -r '.offer.offerId')
printf 'request=%s offer=%s\n' "$REQUEST_ID" "$OFFER_ID"
```

The synchronous path persists the request, searches bounded grid cells, filters and ranks
candidates, atomically reserves the driver in Redis, and returns a pending offer.

### 4. Replay the same logical command

Run the same request with the same `Idempotency-Key` and body:

```bash
REPLAY_JSON=$(curl -fsS -X POST http://localhost:8080/dispatch-requests \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{
    \"requesterId\": \"$REQUESTER_ID\",
    \"serviceType\": \"STANDARD\",
    \"originLat\": 37.0001,
    \"originLng\": -122.0001,
    \"destLat\": 37.0200,
    \"destLng\": -122.0200
  }")

test "$REQUEST_ID" = "$(printf '%s' "$REPLAY_JSON" | jq -r '.requestId')" \
  && echo 'idempotent replay returned the same request'
```

The first request returns HTTP `201`; a replay returns `200`. Reusing the key with a
different important payload returns a conflict rather than silently merging commands.

### 5. Accept, start, and complete the assignment

```bash
ASSIGNMENT_JSON=$(curl -fsS -X POST \
  "http://localhost:8080/offers/$OFFER_ID/accept")
ASSIGNMENT_ID=$(printf '%s' "$ASSIGNMENT_JSON" | jq -r '.assignmentId')

curl -fsS -X POST \
  "http://localhost:8080/assignments/$ASSIGNMENT_ID/start" | jq

curl -fsS -X POST \
  "http://localhost:8080/assignments/$ASSIGNMENT_ID/complete" | jq

curl -fsS \
  "http://localhost:8080/assignments/$ASSIGNMENT_ID" | jq
```

Completion and `AssignmentCompleted` are committed in the same PostgreSQL transaction.
Payment and notification happen later through the outbox and Kafka; payment success is
not required to preserve the completed assignment.

### 6. Inspect asynchronous effects

Allow the poller and consumers a moment, then query the local database:

```bash
docker compose exec postgres psql -U ridesharedispatch -d ridesharedispatch \
  -c "SELECT event_type, published_at IS NOT NULL AS published FROM outbox_events ORDER BY created_at;"

docker compose exec postgres psql -U ridesharedispatch -d ridesharedispatch \
  -c "SELECT operation_id, amount_cents, status, attempt_count FROM payments;"

docker compose exec postgres psql -U ridesharedispatch -d ridesharedispatch \
  -c "SELECT event_id, channel, status, attempt_count FROM notification_deliveries;"

docker compose exec postgres psql -U ridesharedispatch -d ridesharedispatch \
  -c "SELECT event_id, consumer_name, processed_at FROM processed_events ORDER BY processed_at;"
```

The logs should also show outbox publication, simulated notification, and payment outcome.
Publication and Kafka consumption are asynchronous, so an immediately empty query is a
reason to inspect backlog and logs—not to repeat the original ride command with a new ID.

## API summary

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/agents` | create durable driver profile |
| `POST` | `/agents/{agentId}/availability` | move hot state between offline and available when legal |
| `POST` | `/agents/{agentId}/location` | accept a monotonically ordered live location |
| `POST` | `/dispatch-requests` | idempotently create and attempt to match a ride |
| `GET` | `/dispatch-requests/{requestId}` | read ride request and latest offer |
| `POST` | `/offers/{offerId}/accept` | transfer reservation to assignment ownership |
| `POST` | `/offers/{offerId}/reject` | reject offer and release only its reservation |
| `POST` | `/assignments/{assignmentId}/start` | OCC transition to `IN_PROGRESS` |
| `POST` | `/assignments/{assignmentId}/complete` | OCC completion plus outbox event |
| `GET` | `/assignments/{assignmentId}` | read durable assignment |
| `GET` | `/actuator/health` | liveness/dependency health summary |
| `GET` | `/actuator/prometheus` | Prometheus exposition |

There is intentionally no authentication. IDs in paths or bodies are not proof of
identity. Authentication, authorization, rate limiting, and tenant isolation must be
added before any untrusted deployment.

## Run the test suite

The intended correctness gate is:

```bash
mvn clean install
```

Tests start PostgreSQL 16, Redis 7, and Kafka 3.8 once per test JVM through
Testcontainers, replace the application connection properties dynamically, and reset
database and Redis state before each test. Docker Compose does not need to be running for
the tests, but a compatible Docker daemon must be reachable.

The last verified suite result was **36 tests, 0 failures**. It covers:

- concurrent requests racing for one driver;
- idempotent request replay and conflicting key reuse;
- stale and out-of-order driver state;
- stale offer and wrong-token protection;
- one-writer OCC transitions and duplicate completion;
- completion/outbox atomicity and consumer rollback;
- duplicate Kafka delivery;
- payment timeout followed by same-operation retry;
- bounded search behavior in a hot spatial cell;
- HTTP validation and health information exposure.

### Current Testcontainers limitation

The POM currently pins Testcontainers `1.21.3`. A plain `mvn clean install` succeeds only
when Testcontainers can discover a compatible Docker daemon. Some recent Docker Engine
setups expose two independent failure modes:

1. no reachable socket, often shown as missing `/var/run/docker.sock`;
2. client/daemon API incompatibility after discovery.

The Spring messages about not finding nested `@Configuration` classes are informational;
Spring immediately finds `RideShareDispatchApplication`. The causal failure is later in
`DockerClientProviderStrategy`.

Check discovery first:

```bash
docker info
docker context show
docker context inspect
```

On a non-default macOS context, this may be needed in the Maven shell:

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
```

An environment-specific Docker API override may diagnose compatibility, but it is not the
intended permanent fix. The repository should upgrade the Testcontainers pin to a release
compatible with the active Docker Engine and verify the unmodified Maven command. Until
that is done, use this only to verify compilation and packaging without executing tests:

```bash
mvn -DskipTests clean install
```

That command is not a substitute for the integration correctness gate.

## Configuration and bounded work

Defaults live in [`application.yml`](src/main/resources/application.yml) and are bound to
[`DispatchProperties`](src/main/java/io/infrahack/ridesharedispatch/config/DispatchProperties.java).

| Property | Default | What it bounds |
|---|---:|---|
| `dispatch.reservation-ttl-seconds` | 30 | temporary driver ownership after matching |
| `dispatch.location-freshness-seconds` | 30 | maximum age of a matchable driver heartbeat |
| `dispatch.offer-ttl-seconds` | 20 | time available to accept an offer |
| `dispatch.max-cell-search-rings` | 3 | geographic expansion |
| `dispatch.max-candidates` | 5 | expensive ranking/reservation attempts |
| `dispatch.matching-timeout-ms` | 2000 | synchronous matching budget |
| `dispatch.outbox.batch-size` | 50 | events claimed per publisher poll |
| `dispatch.outbox.claim-ttl-seconds` | 15 | recovery of a dead publisher claim |
| `dispatch.payment.batch-size` | 50 | payments claimed per reconciliation pass |
| `dispatch.payment.max-attempts` | 6 | uncertain provider retry budget |
| `dispatch.payment.base-backoff-seconds` | 2 | exponential retry base |
| `dispatch.payment.claim-ttl-seconds` | 15 | recovery of a dead payment worker claim |
| `dispatch.kafka.consumer-concurrency` | 1 | listener concurrency per consumer container |
| `dispatch.kafka.retry-attempts` | 3 | bounded listener retries before recovery handling |

Spring Boot relaxed binding allows environment overrides. For example:

```bash
DISPATCH_MAX_CANDIDATES=10 \
DISPATCH_MATCHING_TIMEOUT_MS=3000 \
java -jar target/ride-share-dispatch.jar
```

Datastore addresses can be overridden with standard Spring properties such as
`SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, and
`SPRING_KAFKA_BOOTSTRAP_SERVERS`.

Bounds are part of correctness under overload. When capacity is not found within the
search and time budgets, the request remains durably `SEARCHING` and may be resumed by an
idempotent replay; the system does not perform unbounded expansion or infinite immediate
retry.

## Observability

Useful application metrics include:

```text
location_updates_total
location_updates_stale_total
location_update_latency
dispatch_requests_total
dispatch_idempotency_replays_total
matching_attempts_total
matching_candidates_examined
matching_reservation_conflicts_total
matching_failures_total
matching_timeouts_total
match_latency
active_reservations
assignment_completed_total
outbox_pending
outbox_publish_failures_total
notification_deliveries_total
notification_duplicate_events_total
payment_attempts_total
payment_success_total
payment_failures_total
payment_reconciliations_total
```

Micrometer may add Prometheus suffixes for units and counter samples. Search the endpoint
rather than assuming the final exported suffix:

```bash
curl -fsS http://localhost:8080/actuator/prometheus \
  | grep -E 'matching_|outbox_|payment_|location_'
```

Important logs include stale-location rejection, reservation conflict, OCC conflict,
outbox publication failure, uncertain payment outcome, and reconciliation. Logs and
metrics are designed to explain a load-test result; they are not evidence of a latency
SLO until the benchmark is actually run.

## Run the k6 workload

With infrastructure and the application running:

```bash
k6 run load-tests/dispatch-smoke.js
```

Example explicit workload:

```bash
AGENT_COUNT=200 \
LOCATION_VUS=100 \
DISPATCH_RATE=20 \
DURATION=10m \
k6 run load-tests/dispatch-smoke.js
```

The script combines many monotonically ordered driver updates with a smaller stream of
ride requests and lifecycle transitions. Record the Git commit, host, container limits,
JVM flags, and exact command before quoting results. The refactored build does not yet
claim a measured capacity result; use [BENCHMARK.md](docs/BENCHMARK.md) as the experiment
and results template.

## Troubleshooting

### Application cannot connect during startup

Run `docker compose ps` and wait for all health checks. Inspect a dependency with:

```bash
docker compose logs postgres
docker compose logs redis
docker compose logs kafka
```

### A ride remains `SEARCHING`

Confirm that at least one driver:

- is registered with the requested service type;
- is `AVAILABLE`, not `OFFLINE` or `OCCUPIED`;
- has a recent accepted location;
- is within the configured cell-ring bound;
- does not already have a live reservation or active assignment.

This state is a clean no-capacity outcome, not necessarily an application error.

### A location returns `STALE`

Sequence numbers are per driver and must strictly increase. Client timestamps do not
override sequence ordering.

### Completion succeeds but payment/notification is not visible yet

Check `outbox_pending`, application logs, Kafka health, and the `outbox_events` table.
Completion is synchronous and durable; publication and consumers are intentionally
asynchronous.

### A local port is already in use

The default app and Compose stack require ports `8080`, `5432`, `6379`, and `9092`.
Stop the conflicting process or override both the published port and corresponding Spring
connection property.

### Reset all local data

The following removes the module's Compose containers and their volumes:

```bash
docker compose down -v
```

This is destructive for the local development database. To stop while retaining the
existing containers and their current state, use:

```bash
docker compose stop
```

Resume those containers with `docker compose start`. `docker compose down` removes the
containers; because this minimal Compose file does not declare named data volumes, do not
use `down` when you intend to preserve reusable local state.

## Correctness boundaries to study

| Problem | Mechanism | Starting point |
|---|---|---|
| duplicate HTTP command | DB unique key + request fingerprint | [`DispatchRequestService`](src/main/java/io/infrahack/ridesharedispatch/service/DispatchRequestService.java) |
| two rides compete for one driver | eligibility-checking Redis Lua + `SET NX PX` | [`AgentReservationStore`](src/main/java/io/infrahack/ridesharedispatch/infrastructure/redis/AgentReservationStore.java) |
| out-of-order coordinates | sequence-checked Redis snapshot | [`AgentOperationalStateStore`](src/main/java/io/infrahack/ridesharedispatch/infrastructure/redis/AgentOperationalStateStore.java) |
| stale offer acceptance | exact reservation token + atomic occupied transition | [`OfferService`](src/main/java/io/infrahack/ridesharedispatch/service/OfferService.java) |
| concurrent durable transition | OCC version predicate | [`AssignmentService`](src/main/java/io/infrahack/ridesharedispatch/service/AssignmentService.java) |
| DB commit / Kafka dual write | transactional outbox + expiring publisher claim | [`OutboxPublisher`](src/main/java/io/infrahack/ridesharedispatch/infrastructure/kafka/OutboxPublisher.java) |
| duplicate Kafka delivery | transactional inbox and unique effect identity | [`PaymentEventConsumer`](src/main/java/io/infrahack/ridesharedispatch/infrastructure/kafka/PaymentEventConsumer.java) |
| provider timeout | stable operation ID + durable fake-provider ledger | [`PaymentService`](src/main/java/io/infrahack/ridesharedispatch/service/PaymentService.java) |

## Scope and honest limitations

The Java implementation demonstrates the requested infrastructure semantics, but it does
not include:

- authentication, authorization, privacy controls, or production secret management;
- a real routing/ETA provider, maps, surge pricing, pooling, or multi-stop routing;
- a real payment or push-notification provider;
- WebSocket driver/rider sessions;
- cancellation, refund, dispute, and support workflows;
- Redis high availability, Kafka multi-broker durability, or multi-region ownership;
- schema registry, CDC outbox, tracing backend, dashboards, or alerting;
- a current measured load-test result.

The design does not claim a global exactly-once transaction. It combines atomic operations
inside each store, idempotent identities, at-least-once event processing, durable fences,
and explicit recovery around cross-store boundaries.

## Read next

- [DESIGN.md](docs/DESIGN.md) — canonical architecture, invariants, consistency, failure
  modes, scaling, and challenge ladder.
- [REFACTORING_CASE_STUDY.md](docs/REFACTORING_CASE_STUDY.md) — what was wrong in the
  first implementation, what changed, and why the guarantees are stronger.
- [INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md) — presentation scripts, follow-up
  questions, defensible claims, and code-reading order.
- [BENCHMARK.md](docs/BENCHMARK.md) — honest validation record and load-test template.
- [V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql) — durable
  constraints, indexes, inbox, outbox, and fake-provider idempotency ledger.
