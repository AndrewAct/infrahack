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
