# Parking Lot — Design Deep-Dive

This follows the project deep-dive template: enough to defend every decision in a
senior-level data-modeling / OOD interview.

---

## Problem

Model a multi-level parking garage that:

- admits **different vehicle classes** (motorcycle, car, truck; electric or not),
- into **different spot classes** (motorcycle, compact, EV-with-charger, large),
- through **entry/exit gates**,
- **meters and charges** for time parked,
- serves a **driver** flow (park → pay → exit) and an **admin** flow (maintenance,
  occupancy reporting),

and stays correct under concurrent arrivals and exits.

## Current behavior (what exists now)

- Entry assigns a compatible spot using a pluggable strategy and issues a ticket.
- Vehicle→spot compatibility is a best-fit preference list; electric vehicles
  prefer charger (EV) bays, then fall back to their size class.
- Exit is a three-step barrier: `checkout` (freeze fee) → `pay` → `exit` (lift
  barrier, free spot). The gate only opens after payment.
- Pricing: free grace period, per-vehicle-type hourly rate billed by the started
  hour, per-24h cap. Lost ticket → flat penalty.
- Admin can take spots out of service / return them, and read an occupancy report.
- Observability: counters (entries, exits, rejections, revenue, lost tickets), an
  occupancy gauge, and an append-only audit trail.

## Out of scope (by design)

Reservations/pre-booking, payment-gateway integration (we model the state
machine, not a PSP), persistence/durability (in-memory repos stand in for a DB),
multi-currency, dynamic/surge pricing, license-plate OCR, and an HTTP/gRPC API.
Each is a clean extension point, called out under *Known gaps*.

---

## Architecture

Layered, dependencies point inward (services → model, never the reverse):

```
            ┌─────────────── service ───────────────┐
 driver →   │ ParkingService   (park/checkout/pay/exit/lost) │
 admin  →   │ AdminService     (maintenance, reports)        │
            │ PermissionService · MetricsCollector · Audit   │
            └───────┬───────────────┬───────────────┬────────┘
                    │               │               │
              strategy/         repository/        model/
         SpotAssignment*     TicketRepository   ParkingLot → Level → Spot
         Pricing*            (optimistic CAS)   ParkingTicket, Vehicle*, Money
```

- **`model`** owns the physical structure and the ticket. `ParkingLot` is the
  aggregate root; `ParkingLevel` owns availability.
- **`strategy`** isolates the two decisions most likely to change: *which spot*
  (`SpotAssignmentStrategy`) and *how much* (`PricingStrategy`).
- **`repository`** is the persistence seam and the optimistic-concurrency boundary.
- **`service`** orchestrates and owns cross-cutting invariants, permissions, and
  observability.

### Primary control flow — entry (`ParkingService.park`)

1. `requireDriver`.
2. Reserve the plate: `activeByPlate.putIfAbsent(plate, ticket)`. If present →
   `VehicleAlreadyParkedException` (no spot was touched).
3. `lot.claimSpot(vehicle, strategy)` → strategy walks the preference list and
   **atomically claims** a free spot (`ParkingLevel.tryClaim`, a single
   `pollFirst` off a lock-free deque). Empty → release the plate reservation,
   count `parking.rejected.full`, throw `NoAvailableSpotException`.
4. Bind spot to ticket, `save(ticket, version)`, emit metrics + audit.

### Primary control flow — exit (`checkout` → `pay` → `exit`)

- `checkout`: compute fee from `(entry, now)`, move PARKED→AWAITING_PAYMENT, CAS-save.
- `pay`: require `amount ≥ fee`, AWAITING_PAYMENT→PAID, CAS-save, add revenue.
- `exit`: require PAID; **CAS-save the PAID→CLOSED transition first, then release
  the spot.** Only the version winner reaches the release, so the spot returns to
  the free-list exactly once.

---

## Key decisions & alternatives rejected

| Decision | Why | Alternative rejected |
|---|---|---|
| **Per-spot-type lock-free free-list** (`ConcurrentLinkedDeque` per `SpotType` per level); claim = `pollFirst`, release = `addLast` | Concurrent claims can't collide — a spot is in ≤1 deque, so at most one thread polls it. No global lock, so throughput scales with free spots, not contention. | A single `synchronized` lot / one mutex: simple but serializes every arrival into one bottleneck. A counter + scan: the check-then-take is racy. |
| **Optimistic version (CAS) on the ticket**, save-before-release | A ticket is conceptually a DB row touched by independent exit terminals; you can't hold a JVM lock across them. CAS detects the double-exit race and frees the spot once. | A per-ticket JVM lock: works single-process only. State machine alone: `close()`'s status check is check-then-act and races. |
| **Strategy pattern for assignment & pricing** | The two volatile policies. NearestFirst vs BestFit is just inverted loop nesting behind one interface; pricing swaps without touching the gate flow. | Hard-coding nearest-spot + flat rate: every policy tweak edits core flow. |
| **Vehicle owns its `spotPreference()`**, electric prepends EV | Compatibility is data, not branching. Adding a vehicle/spot class is a list edit, not new `if`s scattered across the allocator. | A giant `switch (vehicleType, spotType)` in the allocator: O(n²) to maintain. |
| **`Money` as integer cents** | Exact; no binary-float drift on fees, caps, or revenue totals. | `double` dollars: rounding bugs in billing. |
| **Three-step exit (checkout/pay/exit)** | Mirrors the real barrier — payment must clear before the gate lifts; each step is an auditable, independently-failable transition. | One `exit()` that bills and opens at once: can't model payment failure or an unpaid car at the gate. |

## Correctness invariants

1. **One vehicle per spot.** A spot is in exactly one free-list iff FREE; claim
   removes it, release re-adds it. *Tested:* `ConcurrencyTest.concurrentEntriesNeverDoubleAssignASpot`.
2. **One active ticket per plate.** Enforced by `activeByPlate.putIfAbsent`.
   *Tested:* `duplicatePlateIsRejected`.
3. **A spot is freed exactly once.** CAS gate on PAID→CLOSED before release.
   *Tested:* `concurrentExitFreesSpotExactlyOnce`, `doubleExitIsRejected`.
4. **No exit without payment.** `exit` requires PAID. *Tested:* `cannotExitBeforePaying`.
5. **Availability count == size of free-lists.** Occupancy is derived from the
   deques, never a drifting counter.

## Failure modes

| Event | Behavior |
|---|---|
| Lot full for this class | `NoAvailableSpotException`, `parking.rejected.full++`, audited; no partial state. |
| Duplicate entry (same plate) | `VehicleAlreadyParkedException`; no spot consumed. |
| Concurrent last-spot race | Exactly one claim wins (lock-free poll); losers are rejected. |
| Concurrent double-exit | One wins CAS; loser gets `StaleObjectException`; spot freed once. |
| Exit before pay | `PaymentRequiredException`. |
| Underpayment | `PaymentRequiredException`; fee not marked paid. |
| Lost ticket | Flat penalty, PARKED→LOST→AWAITING_PAYMENT; normal pay/exit. |
| Spot taken out of service while occupied | Marked OUT_OF_SERVICE; on the driver's exit it is **not** re-added to the free pool, so admin intent survives. |

## Scaling limits & 10x / 100x

- **Today:** single JVM, in-memory. The allocator is lock-free per (level, type);
  contention only on the hot deque for a popular spot type.
- **Bottleneck:** `lot.occupancy()` is O(levels × types); fine for reads, but
  don't call it on every entry — `park` sets the gauge after the hot path.
- **10x (one big garage):** shard free-lists further (per level already does this);
  the metric write is the only shared-ish state and it's an `AtomicLong`.
- **100x (many garages, multiple app servers):** the in-memory repo becomes a
  row-versioned DB table; `activeByPlate` becomes a unique constraint on
  `(plate, active)`; spot claim becomes a conditional `UPDATE ... WHERE status='FREE'`
  (the DB's compare-and-swap). **The optimistic-version design already maps to
  this 1:1** — that's why it's there, not a JVM lock.

## Tests & validation

- `ParkingServiceTest` (8) — assignment/fallback, EV preference, truck-fit,
  duplicate, full→reopen, pay-gate, double-exit, permissions.
- `PricingTest` (5) — grace, hourly round-up, per-type rate, daily cap, multi-day cap.
- `ConcurrencyTest` (2) — 200 drivers vs 50 spots (no double-assign, clean
  rejections, lot reports full); double-exit frees exactly once.
- Run: `./scripts/test.sh` → `Total tests run: 15, Passes: 15`.

## Observability

- Counters: `parking.entry`, `parking.exit`, `parking.rejected.full`,
  `parking.rejected.duplicate`, `parking.lost_ticket`, `parking.revenue.cents`,
  `parking.spot.out_of_service`.
- Gauge: `parking.occupied`.
- Audit: append-only `who/action/subject@timestamp` for every gate and admin op.
- These map directly to Prometheus counters/gauges and a structured audit log.

## Known gaps (honest)

- `StaleObjectException` from a concurrent double-exit surfaces to the loser; a
  production API would translate it to an idempotent 200 ("already exited").
- No reservation/hold concept; no payment-gateway failure/retry modeling.
- Repos are in-memory (no durability); occupancy is eventually-consistent with
  the gauge by design (set after the hot path).
- Single currency; pricing is static (no surge / time-of-day).

## Interview challenge ladder

- **L1 — problem:** admit mixed vehicle classes into mixed spot classes, meter,
  charge, stay correct under concurrency.
- **L2 — why this architecture:** policies (assignment, pricing) isolated behind
  strategies; availability owned by levels; services own invariants.
- **L3 — alternatives rejected:** global lock, float money, switch-based fit
  matrix, one-step exit (see table).
- **L4 — critical path:** the entry and exit flows above, step by step.
- **L5 — what breaks under concurrency:** last-spot race → lock-free poll;
  double-exit → CAS-before-release; duplicate plate → atomic reservation.
- **L6 — where it's guaranteed:** `ParkingLevel.tryClaim`, `ParkingService.exit`,
  `InMemoryTicketRepository.save`, and the three concurrency assertions.
- **L7 — 100x:** free-list → conditional SQL UPDATE; version → row version;
  plate reservation → unique constraint. The model is unchanged.
- **L8 — what I'd do differently:** make double-exit idempotent at the API edge;
  add a reservation TTL; pull pricing config out to a hot-swappable source.
