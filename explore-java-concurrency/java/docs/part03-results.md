# Part 3 synthetic load result — October 24, 2026

## Environment and source

Apple M5 Pro MacBook Pro, 48 GB RAM, macOS 26.6.2 arm64, Oracle JDK 25.0.2+10-LTS-69. Maven 3.9.9 was explicitly run with JDK 25. The final suite has 13 passing tests: 6 in part 1, 4 in part 2, and 3 in part 3.

The source SHA-256 for the main fair run is `a13ac5f088401f5f1850f74e205eb0dcc56e59378e5b33015b91ab0d58004391` for `src/main/java/io/infrahack/explorejavaconcurrency/part03/LunchRushExperiment.java`.

## Reproduce

From `explore-java-concurrency/java/`:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn test
/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java -cp target/classes io.infrahack.explorejavaconcurrency.part03.LunchRushExperiment
```

The program runs four configurations: 2, 8, and 16 fixed platform workers, plus one virtual thread per accepted task. Each has a 100-request warmup. Three measured repetitions follow, each with 200 nominal arrivals 2 ms apart. Every repeat uses the same proposed 12–24 ms synthetic handoff-time stream across configurations. Freshness expires 120 ms after actual submission. The external admission gate caps in-flight requests at 20, and a fair timed semaphore caps handoffs at 5. Platform executor queues have capacity `20 - workers`; the virtual executor has no sampled platform work queue. Thread creation is included in each run; warmup warms code, not a reused executor.

Main raw output: [fair CSV](../results/part03-raw-20261024-fair.csv). Exploratory earlier output: [nonfair CSV](../results/part03-raw-20261024-nonfair.csv). The earlier source used `new Semaphore(HANDOFF_PERMITS)` rather than `new Semaphore(HANDOFF_PERMITS, true)`; it is not the source identified by the main-run digest and is excluded from the article table.

## Main result

Medians across the three fair runs, calculated separately for each column:

| Mode | Accepted | Rejected | Fresh | Expired | p95 executor wait (ms) | p95 permit wait (ms) | Fresh/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Platform 2 | 90 | 110 | 20 | 70 | 129.90 | 0.00 | 31.64 |
| Platform 8 | 135 | 65 | 135 | 0 | 53.29 | 19.23 | 235.65 |
| Platform 16 | 136 | 64 | 136 | 0 | 20.00 | 52.94 | 233.26 |
| Virtual | 150 | 50 | 150 | 0 | 0.06 | 59.09 | 262.29 |

All runs observed at most 20 admitted in-flight requests and 5 active handoffs. Platform 2 used at most 2 handoffs. Sampled queue maxima were 18, 12, and 4 for platform 2, 8, and 16 respectively. Rejected work was not submitted and consumed no simulated handoff time.

## Definitions and limits

Executor wait is arrival-to-task-start for accepted work. Permit wait is the time spent acquiring a handoff permit, sampled only on successful acquisition. Each p95 uses nearest rank; the p95 values cannot be added to obtain an end-to-end p95. Fresh completion is completion strictly before the deadline. Expired includes accepted work that times out before acquisition or finishes after expiry. Fresh/s divides fresh count by total time from the first arrival through draining the executor. `max_queue=-1` means no sampled platform executor queue. Queue maxima are sampled at submission rather than continuously observed.

This is a short synthetic blocking workload using `Thread.sleep`, not a measurement of real I/O, meal deterioration, or CPU-heavy tasks. Scheduling and actual inter-arrival gaps vary. Rejections make the subset of accepted service times differ among configurations. Three repetitions establish this run set rather than a universal thread ranking; no CPU, memory, allocation, or p99 claim is made. The current module has no retained idempotency record or durable recovery.
