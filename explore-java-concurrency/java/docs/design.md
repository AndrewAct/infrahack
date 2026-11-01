# Kitchen design contract

## Purpose and boundary

Model a central kitchen in one JVM so three articles can examine correctness, progress, and overload against the same business rules. The model is in-memory. It does not simulate a durable database, distributed lock, or transactional physical handoff.

## Implemented slices

The `kitchen` package protects one map of meal claim/expiry state with one private monitor. The `part02` package isolates a single immutable meal snapshot behind `AtomicReference`; a caller supplies the recalculated expiry after a move, and its container name is not a real container-membership transaction. The `part03` package uses synthetic handoff tasks with admission and resource limits; it measures waiting and freshness rather than integrating the full meal ledger. Container capacity/membership, deterioration across moves, replacement expiry scheduling, and retained idempotent outcomes remain requirements for a later integrated simulator.

## Actors and operations

- An order places a meal in a container if capacity allows.
- A rider attempts to claim a meal before it expires.
- An expiry task removes a meal that is no longer eligible.
- A transfer moves a meal between containers while preserving deterioration already accumulated.
- A retry repeats one request key after a missing or delayed response.

The first implementation defines a small operation result for each outcome, rather than collapse every conflict into `not found`. Time comes from an injectable clock. Part 1 evaluates eligibility at a clock sample inside the monitor; the logical claim linearizes at that decision, even if the thread pauses before committing state or returning. A sample at the exact expiry boundary is ineligible. Part 3 separately counts whether synthetic handoff completion beats its deadline. A rider arrival is an event, not necessarily a Java thread.

### Part 1 expiry result semantics

Design context retained at Andrew's request on 2026-11-01: `MealStatus` describes the meal's current state; `ExpiryResult` describes the outcome of this particular `expire()` call.

- `EXPIRED`: this call changed an eligible `AVAILABLE` meal to `EXPIRED` after reaching its deadline.
- `ALREADY_EXPIRED`: the meal was already marked `EXPIRED`; this call left its state unchanged. Either an earlier `expire()` or a `claim()` that detected the deadline may have marked it expired.
- Repeated expiry is idempotent with respect to state, even though the first and subsequent return values differ. The shared monitor makes the check and transition atomic, so competing expiry calls cannot both report a new transition.

This distinction lets a caller identify newly applied expiry transitions, for example when counting them; the current implementation does not include downstream expiry side effects. `PickupRaceTest.claimRejectsAtExpiryEvenWithoutCleanup` and `twoRidersAndCleanupAtDeadlineCannotClaimExpiredFood` cover the claim/cleanup interaction. Keep this context local to this module rather than treating it as a global preference for every result type.

## Invariants

1. One meal has at most one successful claim. Claim and expiry cannot both win.
2. A successful claim's freshness decision samples a time strictly before expiry inside the monitor; this is not a wall-clock completion guarantee.
3. No container exceeds capacity. Stable container membership and meal location agree.
4. Transfer changes future deterioration, not the amount already accrued.
5. Within the defined in-process retention window, a repeated request key returns or identifies its earlier outcome without another handoff. Reuse of a key with different parameters is an error.

## Concurrency decisions to test

Part 1 starts with one explicit synchronization boundary around the whole claim decision. Part 2 compares that baseline with a conditional versioned transition and describes what the CAS does not cover when multiple indexes or containers must change. An old expiry task must carry an expected meal version; its later arrival cannot overwrite a newer transfer merely because it arrived last. Part 3 places copy-on-write on a read-mostly configuration or listener list, not on the changing meal ledger, and introduces bounded intake and handoff capacity.

Before implementing a variant, state its linearization point, lock order if more than one lock exists, interrupt and timeout behavior, and cleanup after failed acquisition. Do not infer that a thread-safe collection makes a multi-object business operation atomic.

## Failure and scale limits

- A lost reply after a successful in-memory claim can be answered only while the request outcome remains in memory; a restart loses that evidence.
- A caller timeout does not prove that running kitchen work stopped.
- Stale expiry, transfer races, duplicate keys, full containers, and interrupted waits need explicit outcomes.
- Increased rider arrival, worker count, or virtual-thread count cannot increase a fixed handoff capacity on its own.

The article prose should distinguish Java API and memory-model guarantees from an observation of one JDK 25 implementation.
