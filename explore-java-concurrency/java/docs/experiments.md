# Experiment contract

The first experiment slices are complete. Results are in [part01-results.md](part01-results.md), [part02-results.md](part02-results.md), and [part03-results.md](part03-results.md). Raw load CSV lives under `../results/`. Keep the scope of each measured or tested claim narrower than the broader kitchen requirements.

## Part 1: claim versus expiry

Use barriers or controlled steps to force a known read/check/write interleaving in the intentionally unsafe variant. Use an injectable clock for expiry at, before, and after the boundary. The corrected variant passes if at most one terminal outcome wins and no meal is claimed at or after expiry. Do not rely on a race appearing by chance.

## Part 2: stale work and contention

The implemented tests publish a transfer at version *v + 1*, then deliver an expiry task created for version *v*. The obsolete task is rejected. Two callers using the same observed snapshot produce exactly one committed move. A naive timestamp register demonstrates why timestamp order can choose an obsolete business action. No throughput comparison or retry-cost benchmark between locks and CAS has been run. A future comparison must perform the same business operation; any deadlock demonstration must be isolated and time-bounded.

## Part 3: snapshots and lunch rush

The snapshot test verifies that a `CopyOnWriteArrayList` iterator retains its creation-time array. Pinned source inspection identifies copying and writer synchronization; no collection-allocation benchmark has been run. The synthetic handoff experiment compares platform workers and virtual threads under a 20-request admission limit, five handoff permits, a 120 ms freshness window, fixed-seed 12–24 ms service times, and nominal 2 ms inter-arrival gaps.

The retained run reports fresh completions, expiry, rejection, p95 executor wait, p95 permit wait, sampled maximum queue depth, in-flight work, and maximum handoff concurrency. It includes warmup and three repetitions. It does not collect CPU profiles, memory usage, allocation rate, or p99; those require a longer, purpose-built follow-up if the article makes claims about them. The main table uses fair handoff acquisition; an earlier nonfair CSV is retained as exploratory evidence.
