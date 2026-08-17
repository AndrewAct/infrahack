# Ride-share dispatch

A compact backend showing how a rider's trip request is matched to a nearby available
driver, then carried through offer, assignment, completion, payment, and notification.
It is an executable distributed-systems lab: the interesting part is what happens under
retries, races, stale locations, worker crashes, and unavailable infrastructure.

The code uses the reusable names `Requester`, `MobileAgent`, and `DispatchRequest`.
In the ride-share scenario, those mean **rider**, **driver**, and **ride request**.
Authentication, mobile apps, maps, surge pricing, and a real payment provider are outside
this one-day MVP.

Implementation: `java/ride-share-dispatch/` — Java 25, Spring Boot, Maven, PostgreSQL,
Redis, Kafka, Flyway, JdbcTemplate, Micrometer, Testcontainers, and k6.
The root `python/` directory is intentionally reserved for a possible second-language
implementation; it is not part of the current Java build.

## What it demonstrates

- Repeated ride creation is idempotent through a durable unique key and payload hash.
- Competing rides cannot reserve the same driver: eligibility and `SET NX PX` happen in
  one Redis Lua operation.
- Offer acceptance atomically consumes its exact reservation and marks the driver
  occupied; a PostgreSQL partial unique index is the durable final ownership fence.
- Older or duplicate location sequence numbers cannot overwrite newer positions.
- Assignment state changes use optimistic concurrency control.
- Completion and its event are committed together through a transactional outbox.
- Kafka consumers combine at-least-once delivery with a transactional inbox/dedup row.
- Payment retries reuse one stable provider operation ID; timeout does not mean failure.

## Architecture

```text
 Riders / drivers
        |
        v
 +---------------------+        +---------------------------+
 | Spring Boot HTTP API|------->| PostgreSQL                |
 | modular monolith    |        | requests, offers, trips,  |
 +----+-----------+----+        | payments, inbox, outbox   |
      |           |             +-------------+-------------+
      |           |                           | outbox poll
      v           v                           v
 +---------+  +----------------+         +---------+
 | Redis   |  | Matching       |         | Kafka   |
 | live    |<-| cells -> filter|         | events  |
 | drivers |  | -> rank -> CAS |         +----+----+
 +---------+  +----------------+              |
                                     +--------+--------+
                                     v                 v
                                  Payment         Notification
```

PostgreSQL is durable business truth. Redis serves current, expiring driver state and
reservations. Kafka records what happened for asynchronous fan-out. Matching never waits
for a Kafka consumer.

## Run locally

Requirements: Java 25, Maven, Docker Compose.

```bash
cd java/ride-share-dispatch
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"   # macOS; omit if Java 25 is active
docker compose up -d --wait
mvn -DskipTests package
java -jar target/ride-share-dispatch.jar
```

Check the app:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus
```

The infrastructure is local-only and uses development credentials from
`docker-compose.yml`. Stop it with `docker compose down`.

## Run tests

The integration suite starts real PostgreSQL, Redis, and Kafka containers:

```bash
cd java/ride-share-dispatch
mvn test
```

If Docker is not discoverable, the suite fails while `AbstractIntegrationTest` starts its
containers. Verify that the daemon is running and, on macOS, that `DOCKER_HOST` points to
the socket shown by `docker context inspect`. The project currently pins Testcontainers
`1.21.3`, which also has a known compatibility gap with some recent Docker Engine setups;
see the debugging section in
[`INTERVIEW_GUIDE.md`](java/ride-share-dispatch/docs/INTERVIEW_GUIDE.md). An API-version
override is only an environment-specific workaround, not the intended repository fix.

Current verified result: **36 tests, 0 failures**. The suite includes a repeated
20-thread driver-reservation race, HTTP contract checks, crash-recovery paths, stale
location/offer rejection, outbox/Kafka dedup, OCC, and payment timeout/retry.

## Run the load test

With the application running:

```bash
cd java/ride-share-dispatch/load-tests
k6 run dispatch-smoke.js
```

Tune `AGENT_COUNT`, `LOCATION_VUS`, `DISPATCH_RATE`, and `DURATION` through k6 environment
variables. Inspect p95/p99 HTTP latency, rejection rate, matching conflicts/timeouts,
Redis latency, DB pool usage, outbox backlog, and Kafka lag. See
[`BENCHMARK.md`](java/ride-share-dispatch/docs/BENCHMARK.md); historical results are not
presented as measurements of the refactored build.

## Read next

- [`Java implementation README`](java/ride-share-dispatch/README.md) — technology choices,
  project layout, bring-up, complete HTTP walkthrough, tests, configuration, metrics, and
  troubleshooting.
- [`DESIGN.md`](java/ride-share-dispatch/docs/DESIGN.md) — invariants, state ownership,
  algorithms, failure semantics, scaling, trade-offs, and interview challenge ladder.
- [`REFACTORING_CASE_STUDY.md`](java/ride-share-dispatch/docs/REFACTORING_CASE_STUDY.md) —
  what was weak in the first implementation, what changed, why it is safer, the evidence,
  and an article-ready narrative.
- [`INTERVIEW_GUIDE.md`](java/ride-share-dispatch/docs/INTERVIEW_GUIDE.md) — 30-second,
  90-second, and five-minute explanations, follow-up questions, defensible claims, and a
  code-reading path.
- [`V1__init_schema.sql`](java/ride-share-dispatch/src/main/resources/db/migration/V1__init_schema.sql)
  — constraints, conditional ownership, outbox, inbox, and provider idempotency ledger.
- [`MatchingService.java`](java/ride-share-dispatch/src/main/java/io/infrahack/ridesharedispatch/service/MatchingService.java)
  and [`AgentReservationStore.java`](java/ride-share-dispatch/src/main/java/io/infrahack/ridesharedispatch/infrastructure/redis/AgentReservationStore.java)
  — coarse-to-fine matching and the atomic reservation boundary.
- [`OfferService.java`](java/ride-share-dispatch/src/main/java/io/infrahack/ridesharedispatch/service/OfferService.java)
  — reservation-to-assignment ownership transfer and compensation.
- [`PaymentService.java`](java/ride-share-dispatch/src/main/java/io/infrahack/ridesharedispatch/service/PaymentService.java)
  — bounded retry, uncertainty, and stable payment identity.
