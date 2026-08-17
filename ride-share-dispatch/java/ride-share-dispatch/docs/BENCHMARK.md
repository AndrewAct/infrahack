# Ride-share dispatch — benchmark notes

## Current status

The correctness refactor has been verified by the integration suite, but the k6 workload
has **not** been rerun against this exact build. No current latency or throughput result is
claimed here.

An earlier implementation was exercised locally, but its measurements are intentionally
not carried forward: reservation validation, ownership transfer, recovery, bounded Redis
scans, outbox leasing, payment reconciliation, and HTTP checks changed materially. Those
numbers would be easy to quote and technically misleading.

## Functional validation

On August 17, 2026, the refactored packaged JAR was started against the normal Compose
stack and exercised over HTTP:

```text
health -> UP
register driver -> available -> location ACCEPTED
create ride -> offer returned
repeat identical Idempotency-Key -> same request ID
accept -> assignment CREATED
start -> IN_PROGRESS
complete -> COMPLETED
```

After asynchronous processing, PostgreSQL showed all six generated outbox rows published
(`DispatchRequestCreated`, `AgentReserved`, `AssignmentCreated`, `AssignmentStarted`,
`AssignmentCompleted`, `PaymentSucceeded`), one `SUCCEEDED` payment with one attempt, one
`SENT` notification, and one processed-event row for each consumer group. This validates
the execution path; it is not a throughput benchmark.

## Workload

`load-tests/dispatch-smoke.js` runs two concurrent traffic shapes:

- many driver location updates with monotonically increasing sequence numbers;
- a smaller arrival stream that creates a ride, replays the idempotency key, accepts the
  offer, starts the assignment, and completes it.

Default values are a smoke test, not a capacity benchmark. Override them explicitly:

```bash
AGENT_COUNT=200 \
LOCATION_VUS=100 \
DISPATCH_RATE=20 \
DURATION=10m \
k6 run load-tests/dispatch-smoke.js
```

Run from `java/ride-share-dispatch` while the Compose infrastructure and application are
up. Record the Git commit, JVM flags, Docker/resource limits, host hardware, exact command,
and whether the load generator shared the application host.

## Signals to record

Client-side:

- requests/second and iteration rate;
- p50, p95, p99, and max latency by endpoint;
- `http_req_failed`, validation/rejection rate, and match success rate;
- idempotent replay success and completed ride count.

Server-side:

- location accepted/stale counters and location timer;
- match timer, candidates examined, reservation conflicts/failures/timeouts;
- Redis command latency and hot-cell cardinality;
- Hikari active/pending connections and PostgreSQL CPU/WAL/lock waits;
- `outbox_pending`, publish failures, Kafka producer errors and consumer lag;
- payment due/unknown counts and notification duplicates;
- CPU, heap, GC pauses, thread pools, and container throttling.

## Experiment sequence

1. Run a one-minute smoke test and verify every threshold and asynchronous effect.
2. Hold driver/location traffic constant; increase dispatch rate in steps until latency or
   rejection bends.
3. Hold dispatch constant; raise location VUs to locate Redis/application hot-path limits.
4. Force contention by reducing available drivers and observe reservation conflicts.
5. Stop Kafka temporarily and verify online matching continues while outbox backlog grows,
   then recovers.
6. Add realistic datastore network latency before treating laptop results as deployment
   guidance.
7. Run a 30–60 minute soak near the desired operating point and inspect memory/backlog
   stability.

## Results template

| Item | Value |
|---|---|
| Date / commit | Not run |
| Host / container limits | Not run |
| Command | Not run |
| Sustained HTTP requests/s | Not run |
| Location updates/s | Not run |
| Ride requests/s | Not run |
| HTTP p95 / p99 | Not run |
| Error or rejection rate | Not run |
| Match success / conflict / timeout | Not run |
| Peak outbox backlog / Kafka lag | Not run |
| DB pool saturation | Not run |
| First limiting resource | Not run |

The goal is not an impressive number. It is a reproducible saturation curve and an
explanation of which bound protects the system when demand exceeds capacity.
