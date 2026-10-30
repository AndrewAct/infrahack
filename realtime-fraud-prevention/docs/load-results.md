# Closing pipeline load and resource results — October 30, 2026

Provenance: **measured** by Codex on the same Apple M5 Pro / 48 GiB / 18-CPU Mac. Successful run: `pipeline-load-20261030T135956Z`. Java 21 shared application heap max 4 GiB; broker heap 1 GiB; OpenSearch 3.9.0/k-NN 3.9.0.0 on JDK 25, heap 2 GiB, four active processors. No cloud, containers, replication or node-failure test.

## Method and correctness

One isolated broker/partition per topic, Flink parallelism one, existing nested JSON validation/business dedup/ordered features/CEP, transactional case output, existing synchronous create-only CaseIndexer, one local OpenSearch node. Filesystem checkpoint storage under the per-run results directory; watermark allowance 45 s, checkpoint interval 1 s, index refresh 1 s. Generator, Flink MiniCluster, committed-case observer and indexer share one JVM. RSS/CPU for that process cannot be attributed to one operator.

The synthetic source declares an empty known history prefix and generates a fresh account per four-event episode, three declines then authorization. It compresses witness times to 0/1/2/3 ms relative to scheduled episode start. Device/IP patterns and amount are deterministic. This is a capacity shape, distinct from the 90-second explanatory episode in Part 1. No duplicate or failure is deliberately injected in the successful ladder. Fifty-second warmup at 100 events/s; three 60-second offered-rate stages 100/1000/5000 events/s; final nonmatching progress event advances the watermark, then drain. The producer accepted 371,000 payments plus the one control event.

Every committed case is checked independently against its four expected witness IDs, all eight expected raw features, completeness and transform values (without calling production Features.vector). All 92,750 cases are unique and correct. The OpenSearch indexer reports 92,750 creates, zero duplicates/conflicts/retries/quarantine, and search probes eventually see all 92,750. The source checkpoint offset reaches raw end offset 371001. Case-topic offsets include transaction control records; an offset lag is not a count of missing business cases.

A separate frozen 100K/D256 history is reconstructed from the static run's retained float32 vectors. It has its own warm baseline of 36 requests, actual branch verification for all twelve queries, then about two sequential held-out HNSW queries per second during the live ladder. That history does not change; online cases use their existing explainable 8D representation in a different index. No online 200-field feature join or enriched encoder is implied. Every returned historical ID list matched the frozen baseline throughout.

## Results

| Offered rate | Planned payments | Eventual verified cases from stage | Median emission-to-committed observation, s | Historical median / empirical P99, ms | Query samples |
| --- | --- | --- | --- | --- | --- |
| 100 | 6,000 | 1,500 | 46.132 | 2.013 / 6.769 | 119 |
| 1,000 | 60,000 | 15,000 | 49.505 | 1.368 / 5.130 | 118 |
| 5,000 | 300,000 | 75,000 | 40.023 | 1.457 / 5.032 | 119 |

Warmup contributes another 1,250 cases. The final offered stage ended with only 20,517 cases searchable; all 92,750 became visible about 21.567 seconds later. These are eventual completions after progress/drain, not proof of sustained 5,000 events/s backlog-free capacity. The highest measurement-stage generator scheduling lag was below 7.1 ms (maximum); warmup had a 79.4 ms maximum. Full scheduling observations are retained.

Unloaded historical-query median was 0.8420 ms (36 warm samples, P95 1.0480 ms). The 5,000 input-rate stage had a 1.4569 ms median, P95 2.9233 ms and empirical P99 5.0321 ms from 119 samples. Stage order, JIT/cache warmup and the cohosted environment prevent a monotonic load/latency conclusion.

At 100 events/s, median event-to-local-case emission was 45.158 seconds and local-case-to-committed-consumer observation 0.9745 seconds. Median terminal-emission-to-committed observation was 46.132 seconds. The timestamp boundaries differ, so medians of the components should not be algebraically added. At 1,000 events/s, the corresponding committed-observation delay was 49.5055 seconds. Final explicit watermark progress shortened the last stage's waiting; its 40.0225-second median cannot be read as a steady-state freshness improvement. Visibility is sampled by _search about every 100 ms, with one-second refresh and actual request timing recorded.

## Process resources

One-second ps samples, plus heap/node snapshots; 100% CPU represents one core. Sampled RSS includes JVM native memory and other shared work, and can exceed Xmx. Whole-run peaks include preparation and drain, while per-stage figures are separately retained.

| Process | Sampled whole-run peak RSS, MiB | Highest-stage median ps CPU % |
| --- | --- | --- |
| OpenSearch | 2038.2 | 1.1 |
| Kafka | 837.0 | 7.4 |
| shared application | 4333.6 | 218.3 |

Swap usage was zero before and after the completed run. The OpenSearch process retained four inactive indexes from earlier failed trials during this ladder (two 100K historical indexes, one partial 26,799-case projection and one empty projection). Queries targeted explicit fresh indexes, so membership was unaffected; its process RSS/cache footprint includes that background. A wrapper fallback-cleanup case-normalization bug was identified afterward and fixed. All remaining test indexes were explicitly deleted, and the isolated node was stopped. The final successful run's own two indexes were removed by its normal Java finally block. This is a resource observation on the recorded process state, not an isolated flat-versus-HNSW memory comparison.

## Failed trials and regression

- `pipeline-load-20261030T052008Z`: sampler AdminClient request/default API timeout configuration mismatch; stopped before meaningful input. Fixed the client config.
- `pipeline-load-20261030T052453Z`: stream job exited during asynchronous checkpoint failure while the first driver kept producing; invalid as a successful load result. Job-result failure capture now stops immediately.
- `pipeline-load-20261030T052856Z`: immediate failure capture confirms repeated asynchronous checkpoint failure. A real Admission snapshot regression then reproduces the default memory-backed limit: 13,167,916 bytes exceeds max 5,242,880. Changing only the experiment checkpoint storage to filesystem makes the same fixture restore and reject a redelivery. Red/green JUnit XML retained with the diagnosis.
- `pipeline-load-20261030T135248Z`: filesystem checkpoint run with 2 GiB application heap passes the old checkpoint-size boundary, then OOMs during the 5,000 tier. Partial checked cases and resource samples are retained as capacity evidence, excluded from completed-ladder timing. A reconstructed used wrapper matches its recorded source SHA-256.
- `pipeline-load-20261030T135956Z`: same input/policies and filesystem checkpoints, application heap 4 GiB; all 92,750 cases pass and become searchable. ExitOnOutOfMemoryError makes future failed trials terminate promptly.

Filesystem checkpoint storage changes snapshot persistence, not the heap state backend or the dedup rule. The 5 MiB failure is demonstrated with the actual state operator, not a mock. The toy standalone streaming runner's default checkpoint storage is not silently changed by this experiment-only setting.

## Raw evidence and reproduction

From the companion Java directory: start `sh article-node.sh` in another terminal; run `sh load-check.sh results/retrieval-<UTC>` using the measured static vector directory. `LOAD_HEAP=2g sh load-check.sh ...` reproduces the smaller application budget. Aggregate with `uv run summarize-results.py load results/pipeline-load-<UTC>`. The workload and maximum heap are recorded in environment.txt/source hashes. The used successful wrapper is preserved as load-check-used.sh; the installed wrapper's only later change normalizes the lowercase cleanup index suffix.

Successful raw files: `load-summary.json`, `aggregate.json`, `schedule.jsonl`, `committed.jsonl`, `visibility.jsonl`, `topic-lag.jsonl`, `historical-baseline.jsonl`, `historical-queries.jsonl`, `historical-path.json`, `process-samples.txt`, `process-roles.json`, heap snapshots, environment, logs and source hashes. Final regression suite: ten JUnit methods, nine passed, the opt-in historical Kafka failure test skipped; the existing 26 event-time fixtures still match 118 oracle cases. Their separate earlier recovery evidence is retained. The new large-state snapshot regression passes.

## Limits

One short ladder after warmup, one host, one source partition, compressed intra-episode time, mostly fresh keys, fixed synthetic payloads and no intentional failover in the successful load. The run is shorter than the ten-minute feature-history horizon, so memory has not reached the sustained retention regime. Periodic ps and search polling have finite resolution. CPU is shared with client/observer work. Inactive trial indexes and the warm node affect memory/cache state. Historical lookups are a separate frozen contention workload, not current online enriched-feature inference. No production fraud-quality, sustained throughput ceiling, model-serving or availability conclusion follows.
