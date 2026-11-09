# Kafka, CEP recovery and indexing — October 28, 2026

Provenance: **measured** by Codex on Andrew's local machine. Run [`kafka-cep-20261029T045834Z`](../java/results/kafka-cep-20261029T045834Z/) completed October 28 at 23:59 America/Chicago (October 29 UTC), in temporary staging in the Medium workspace before installation into infrahack. This completes the article-sized streaming integration. Source hashes and raw evidence travel with the module.

## Method

Java 21.0.13.0.2, Maven wrapper 3.9.9, Flink 2.3.0, tested Kafka connector 5.1.0-2.3 RC1, Kafka 4.3.1. One isolated local Kafka broker, one partition per topic, parallelism 1, test JVM heap capped at 2 GiB. OpenSearch 3.9.0 / k-NN 3.9.0.0 ran temporarily on 127.0.0.1:19200 with separate data/log directories, a 512 MiB heap and two active processors; its wrapper used Java 25. Neither service changed Andrew's persistent service configuration. The temporary broker and OpenSearch process were stopped; the test index was deleted.

The source uses real nested synthetic payment records and periodic Kafka-source watermarks with 45-second disorder tolerance. Publish F1, F3, F2, duplicate F2, plus a payment on a different account to advance event time. Wait for a completed whole-job checkpoint after the three declines are finalized, with no S yet. Publish S and another progress-driving payment. A test-only operator forwards the case to the Kafka sink, waits until an uncommitted reader sees it, and fails before its next checkpoint barrier passes. Restore once. Retained source-observer files verify that S is reread but F1/F2/F3 are not; producing the correct case therefore requires restored pending CEP state.

Repeat this scenario with exactly-once and at-least-once sinks. Independently scan the fixed logical fixture with the existing oracle. Finally consume the retained raw topic with a bounded fresh source and a separate replay namespace. Compare full logical case content excluding namespace-derived identity and local observation time; separately compare witness IDs, all eight features, completeness and vectors against the oracle. Verify tenant/account Kafka keys. This is task recovery within a local MiniCluster, not JobManager process loss or broker/node loss.

## Results

Seven JUnit methods passed, with zero failures/errors/skips. The original 26 event-time traces still produced 118 expected and actual cases; the processing-time control and four managed-state methods also passed.

| Check | Measured result |
| --- | --- |
| Exactly-once after failure | Two physical case records; one visible to a committed reader |
| At-least-once after failure | Two physical case records; both visible to a committed reader |
| Pending FFF restoration | Case completed correctly without rereading its three failures |
| Bounded raw replay | One committed case, logically equal to live output under this fixture's equivalent admission |
| Index acknowledgment before consumer offset commit | Reconsumption recognized the existing identical case and committed the correct next offset |
| Index rebuild with another consumer group | Identical content including recorded `knowledge_time`; stored document version remained 1 |
| Same ID, altered features | One counted conflict; original content/version unchanged |
| Partial bulk: valid create followed by invalid field type | First item created; consumer group offsets remained uncommitted; retry recognized the created item |
| Search visibility | One expected case visible through `_search` after an explicit test refresh |

Raw proof: [`kafka-summary.json`](../java/results/kafka-cep-20261029T045834Z/kafka-summary.json), [`tests.log`](../java/results/kafka-cep-20261029T045834Z/tests.log), [`source-sha256.txt`](../java/results/kafka-cep-20261029T045834Z/source-sha256.txt), [`manifest.txt`](../java/results/kafka-cep-20261029T045834Z/manifest.txt), [`opensearch.log`](../java/results/kafka-cep-20261029T045834Z/opensearch.log). The run also retains source inputs, completed-prefix checkpoint markers, restored input IDs, committed/uncommitted Kafka rows, raw-replay rows, the accepted v2 mapping, stored case and search response. The refresh establishes functional visibility; it is not a measured freshness latency.

## Rerun and article use

```sh
OPENSEARCH_ENDPOINT=http://localhost:9200 sh kafka-check.sh
```

Run from `java/`; OpenSearch must already be available. Omit `OPENSEARCH_ENDPOINT` for the Kafka/CEP-only check. The script owns its temporary broker and rejects occupied ports. The measured run used port 19200 for its disposable OpenSearch instance; the command above also works against an available local service with an isolated test index.

Article 1 needs one worked witness, the event-time comparison and the one-versus-two committed-output table. Link the full fixture inventory and setup here. The practical payoff is an immutable, versioned, as-of feature snapshot a downstream scorer can consume. Article 2 owns representation compatibility, historical eligibility, exact-versus-HNSW and the brief cost/load comparison. Those are the feature/retrieval responsibilities relevant to inference infrastructure; no model-serving component is added just to connect the story to that role.

Raw replay can change admission under other watermark schedules; this fixture does not prove arbitrary live/replay equivalence. `knowledge_time` is the emitting run's observation time, persisted with the committed case. Index rebuilds reuse that case log; independent raw recomputation uses a separate namespace and new observation time. Exactly-once is demonstrated at the Kafka boundary; the OpenSearch projection converges through create-only retry checks. There is no new HNSW traversal, throughput, freshness SLO, model-quality, multi-partition or high-availability claim. Keep graph/capacity experiments in Article 2.
