# Part III meal readiness and claim validation — November 8, 2026

Provenance: **measured correctness tests**, run by Codex in a temporary copy at `/private/tmp/part03-ready-meals-work/infrahack/explore-java-concurrency/java/`. The original InfraHack repository was at `9b703366418c18077e6d4fe4c235af505b84a5bc`. Sandbox approval rejected writes to that repository; this evidence does not claim the original files were modified. The retained patch can apply these changes there.

## Design and invariant

One object fixes a meal ID, assigned rider, monotonic deadline, private readiness future, and monitor-protected state. Readiness is signalled before a waiter competes for a shared counter permit. The final ownership and freshness decision occurs under the monitor after both waits. At most one request claims a given meal, and no different rider succeeds. Waiting holds no meal monitor; waiting for preparation holds no counter place. Only an acquired counter permit is released.

A future is notification rather than ownership authority. Timeout/interruption does not cancel the shared readiness signal, and later interruption cannot undo a committed claim. Preparation must have an execution path independent of blocked pickup workers. This slice omits the cross-meal rider-bag ledger, a global registry, physical handoff, order cancellation, and restart recovery. A logical claim-time deadline does not promise that the response returns by that deadline.

## Reproduce

From `explore-java-concurrency/java/` with JDK 25:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn -Dtest=ReadyMealTest test
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn test
```

Logs are stored as gzip-compressed originals with their bytes preserved. The recorded run used `-o` because Maven dependencies were already cached. Environment: Oracle JDK 25.0.2+10-LTS-69, Maven 3.9.9, macOS 26.6.2 aarch64. No dependency or build-file change was needed.

## Observed results

[Final test log, gzip](../results/part03-readiness-20261108/tests.log.gz): **26 tests, zero failures/errors/skips**, comprising Part I 9, Part II 4, existing Part III 3, and new readiness cases 10.

The new cases cover B claiming first through a single counter permit while A waits on a different meal (both platform and virtual waiters); wrong-rider rejection and repeated readiness; duplicate requests gated at the final decision; expiry during readiness and permit waiting; actual timed-out waits at both stages; and interruption at both stages followed by a successful retry without a cancelled shared future or leaked permit. Injectable clock samples make expiry independent of short scheduling delays. B's correctness test runs B on the caller thread, so it establishes independent meal progress with execution capacity available, not progress through a saturated worker pool.

Three deliberate source mutations were rejected:

| Mutation | Failing cases | Evidence |
| --- | ---: | --- |
| Take counter capacity before waiting for readiness | 2 | [Log](../results/part03-readiness-20261108/capacity-before-readiness.log.gz) |
| Remove the final freshness check | 1 | [Log](../results/part03-readiness-20261108/missing-final-expiry-check.log.gz) |
| Remove the claim monitor | 1 | [Log](../results/part03-readiness-20261108/missing-claim-monitor.log.gz) |

The monitor control fails when the contender finishes instead of reaching `BLOCKED`; that failure establishes detection of monitor removal, rather than a standalone observed double-claim result. Two timeout-only tests were subsequently refined to use an injected clock before the final full-suite run; the three control-tested methods were unchanged. Broken variants were discarded.

## Source and measurement boundary

[Manifest](../results/part03-readiness-20261108/manifest.json) records final source hashes, execution command, environment, provenance, and control outcomes.

- `src/main/java/io/infrahack/explorejavaconcurrency/part03/ReadyMeal.java`: `b85da38415f7f82182247a5fe777732bd11a5a1bb1033f373a19e64c3568b727`
- `src/test/java/io/infrahack/explorejavaconcurrency/part03/ReadyMealTest.java`: `ba361bc510a251f99c477fdcf7de8c0017efd14eec0950d63a3c1e3c85004b7d`
- `src/main/java/io/infrahack/explorejavaconcurrency/part03/LunchRushExperiment.java`: `a13ac5f088401f5f1850f74e205eb0dcc56e59378e5b33015b91ab0d58004391`
- `pom.xml`: `ca8a1797d1b15295f9faf33c75ba6e0ebabaeadfbe323130e0bd2cdb9257b960`

The original `LunchRushExperiment.java` is byte-identical. Its older load CSV and reported article table remain unchanged and assume meals are ready; no readiness-plus-load benchmark was run. This validation measures correctness only, with no throughput, latency, or thread-ranking conclusion for the new slice.
