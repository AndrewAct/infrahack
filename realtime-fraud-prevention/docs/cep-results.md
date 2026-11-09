# CEP correctness results — October 27, 2026

Provenance: **measured** by Codex on Andrew's local machine. Run [`cep-20261027T185721Z`](../java/results/cep-20261027T185721Z/) contains the final accepted source, test and CLI evidence. The run executed in `/private/tmp/fraud-cep-work/realtime-fraud-prevention/java` before installation into infrahack; the installed sources are checked against that run's SHA-256 manifest.

## Environment and method

macOS 26.6.2 / arm64; project inventory identifies Apple M5 Pro and 48 GB, not independently remeasured here. Homebrew JDK 21.0.13.0.2, Maven wrapper 3.9.9, Flink 2.3.0. Local single-source streaming MiniClusters, parallelism one, test JVM `-Xmx2g`; no brokers, OpenSearch, cloud, warmup, timing comparison or load test. Seed `20261026` generates nested synthetic payments; these are not labeled real fraud.

Twenty-six event-time traces vary arrival order, explicit watermark progress, duplicate/conflicting identities, key isolation, time boundaries and feature completeness. The randomized trace contains 1,152 unique payments across 64 keys, 298 exact duplicate deliveries, and 1,450 total deliveries. An ordinary-map ordered-scan oracle independently checks witness IDs, features, vector eligibility and rejection IDs. The processing-time control uses the same pattern with arrival order, without upstream event-time reordering or an event-time comparator.

Source hashes: [`source-sha256.txt`](../java/results/cep-20261027T185721Z/source-sha256.txt), whose SHA-256 is `3f7b52cd3855b33985df19df43a2cbe5d88df53b5ddc361e88a362cf98fa2267`. The [runtime manifest](../java/results/cep-20261027T185721Z/manifest.txt) and [test log](../java/results/cep-20261027T185721Z/tests.log) record the actual environment and execution.

## Command and result

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
sh run.sh
```

The script uses `./mvnw -B -ntp -Devidence.dir=<run-directory> -Dmdep.outputFile=target/classpath.txt verify dependency:build-classpath`, then runs `PaymentCepJob` on `ordered-input.jsonl` with coverage start `2026-10-26T14:50:00Z` and run ID `cep-cli-v1`.

Verbatim JUnit summary:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

Two integration test methods execute the 26 event-time fixtures and one processing-time control; four managed-state test methods cover expiry, stalled progress, feature-history expiry and buffer restoration. The [event-time summary](../java/results/cep-20261027T185721Z/cep-summary.json) records 118 expected and 118 actual cases across the fixtures; the [randomized actual output](../java/results/cep-20261027T185721Z/seeded-randomized-actual.json) contains 100 of those cases.

| Controlled case | Expected and measured outcome |
| --- | --- |
| Ordered FFFS | One case; three decline IDs plus authorization ID |
| FFSF arrival, F3 admitted before its timestamp is finalized | Same logical case, ID, witness and features as ordered input, excluding local observation time |
| F3 delivered at its watermark | No case; one late rejection |
| Explicit progress with 2 / 10 / 45 s tolerance | 0 / 0 / 1 cases, with 1 / 1 / 0 late rejections |
| Processing-time FFFS / FFSF | 1 / 0 cases; this is a different matching policy |
| Four declines then authorization | One case with the last three declines |
| One decline delivered three times, then authorization | No case; two duplicate rejections |
| Fresh event ID for the same immutable attempt | One business attempt; one duplicate rejection |
| Witness duration 119999 / 120000 / 120001 ms | 1 / 0 / 0 cases |
| Same timestamp, event after S in tie order | Excluded from S's feature snapshot |
| Event exactly ten minutes before S | Excluded; event one millisecond later included |
| Account / tenant / card / namespace isolation | Four qualifying keys; the fifth, with only two failures, does not match |
| Attempt or event-ID conflict | Rejected; pending case remains inspectable but incomplete and without a vector |
| Missing device, mixed currency, insufficient startup coverage | Pattern case emitted as incomplete, without a vector |
| Seeded randomized trace | 100 cases equal to scan oracle; 298 duplicates rejected |

Across event-time fixtures the side outputs account for 301 duplicate, 3 late, 2 conflict and 4 invalid records. The [CLI log](../java/results/cep-20261027T185721Z/cli.log) separately shows a complete case generated from four nested payment observations, with four attempts, three declines, 0.75 failure fraction, 30-second median gap, 90-second witness and an automatically computed vector.

## Real managed-state lifecycle

Flink's keyed operator test harness runs the actual production operators and heap managed-state backend. These are serialized operator snapshot sizes, not heap/RSS measurements, per-event cost estimates or whole-job checkpoint benchmarks.

| Dedup observation | Retained attempts | Serialized snapshot bytes |
| --- | ---: | ---: |
| W = 119999, original event at t = 0 | 1 | 5,871 |
| Restore, W = 120000, old redelivery and equal-W event | 0; both rejected late | 2,438 |
| W stalled at -1, one unique event | 1 | 5,883 |
| Same W, 24 unique events | 24 | 81,476 |
| Resume to W = 120000 | 23 | 78,201 |
| Resume to W = 120023 | 0 | 2,438 |

Raw files: [dedup expiry](../java/results/cep-20261027T185721Z/dedup-expiry.json), [stalled watermark](../java/results/cep-20261027T185721Z/stalled-watermark.json), [independent history expiry](../java/results/cep-20261027T185721Z/feature-history-expiry.json). History retains t=0 at W=599999 and removes it at W=600000. A separate snapshot/restoration test restores pending F2/F3/S after F1 was finalized, then verifies S still observes four attempts and three declines.

## Interpretation and limits

This establishes a runnable bounded event-time CEP/feature slice under the documented single-source contract. It demonstrates why useful matching depends on admission and ordering, and why a time horizon does not impose a byte bound when watermarks stall. It does not establish real fraud precision, sustainable throughput, multi-partition correctness or end-to-end exactly-once recovery. Full-job CEP checkpoint/restart, Kafka case-topic transactions, live/replay admission changes, persistent original `knowledge_time`, create-only OpenSearch indexing and the second article's retrieval/capacity experiments remain future work. The earlier 105-document retrieval smoke still supplies no HNSW traversal evidence.
