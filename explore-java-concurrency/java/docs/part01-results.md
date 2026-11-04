# Part 1 correctness result — October 24, 2026

## Build and run

Local runtime: Oracle JDK 25.0.2+10-LTS-69. Maven 3.9.9 was run with `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home`; without that setting, local Maven selects Java 27.

From `explore-java-concurrency/java/`:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn test
```

Surefire reported **6 tests, 0 failures, 0 errors, 0 skipped** in `PickupRaceTest`. The intentionally unsafe fixture is expected to return two successful claims: a `CyclicBarrier` forces both threads to read `AVAILABLE` before either writes `CLAIMED`. The corrected kitchen allows exactly one successful claim, rejects a claim at the expiry boundary without waiting for cleanup, and prevents later cleanup from revoking a completed claim. At the exact deadline, two rider tasks and a cleanup task cannot produce a successful claim.

The class file inspection command was:

```bash
javap -c -p target/classes/io/infrahack/explorejavaconcurrency/kitchen/SynchronizedKitchen.class
```

The compiled `claim` method contains `monitorenter` and `monitorexit`, including exit paths for early return and exception handling. This is an observation about this build; the Java Language Specification supplies the semantic guarantee. These tests do not measure lock throughput or prove that this run inflated a monitor or parked a thread.

## Limits

Part 1 models only meal claim and expiry. Container capacity, transfer, retained request outcomes, physical handoff, restart recovery, and overload are not implemented. The positive bug demonstration shows one forced interleaving, not a probability of failure under production traffic. Neither green tests nor one final map status establish durable exactly-once delivery.

## November 2, 2026 revision

Runtime and command are unchanged. Surefire reported **9 tests, 0 failures, 0 errors, 0 skipped** in `PickupRaceTest`, and 16 tests in the full suite (4 in `VersionedMealTest`, 3 in `LunchRushTest`). Ten further `PickupRaceTest` runs had no failures.

Reason for the revision: in a scratch copy, removing every `synchronized` block from `SynchronizedKitchen` left the original six tests green (one `mvn test` plus five `surefire:test` reruns). A loop with the shape of the original latch test, in one warmed JVM, saw double claims in 250 of 20,000 attempts without the lock and 0 of 20,000 with it. This describes that test shape on this machine, not a production failure rate. A lock-free per-meal CAS version of the same class also passed all six, because the decision then fit in one status and an immutable deadline.

Changes:

- `claim(mealId, riderId)` also requires an empty bag and returns `RIDER_BUSY` otherwise. Delivery that would empty a bag is out of scope.
- `secondRiderBlocksOnMonitorAndExactlyOneClaims` replaces the latch test. A `GateClock` holds the first rider inside the monitor until the contender's thread reports `Thread.State.BLOCKED`.
- `perMealCasCanFillOneBagTwice` is a positive demonstration: a barrier between the bag check and per-meal CAS lets one rider hold two meals. It shows this naive composition, not every CAS design.
- `monitorKeepsSecondMealOutOfTheSameBag` applies the gate to the bag rule.
- `stalledOwnerHoldsUpUnrelatedRiderEvenWhenInterrupted` shows the monitor's cost: an unrelated rider stays `BLOCKED` while the owner is held, including after `interrupt()`.

With every `synchronized` block removed from a scratch copy of the revised code, the three gated tests failed in three of three runs (9 tests, 3 failures). `twoRidersAndCleanupAtDeadlineCannotClaimExpiredFood` is a clock check: every order returns `EXPIRED` at a fixed deadline, so it does not exercise the lock. `BLOCKED` is a JVM state and does not show whether a run inflated a monitor or parked a thread.
