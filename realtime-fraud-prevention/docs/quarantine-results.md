# Conflict quarantine — October 29, 2026

Provenance: **measured** by Codex. Run [`kafka-cep-20261029T052948Z`](../java/results/kafka-cep-20261029T052948Z/) executed in temporary staging in Andrew's Medium workspace before installation into infrahack. It supersedes the earlier consumer's halt-on-content-conflict behavior; the older run remains valid evidence for that historical implementation.

## Contract and implementation

The Kafka-consuming indexer preserves create-only OpenSearch writes and the full-content comparison, including `knowledge_time`. A mismatch sends both the stored and incoming cases to `<case-topic>-conflicts` (or an explicit seventh CLI argument). The envelope includes `schema_version=case-index-conflict-v1`, reason, target index and source topic/partition/offset/key. The Kafka key and `conflict_id` are SHA-256 over target index plus retained source topic/partition/offset. No new production class or dependency was needed; `CaseIndexer` gained 71 lines and replaced 24 existing lines.

Synchronous publication with `acks=all` must succeed before the batch can commit its source offsets. The producer enables idempotence, caps metadata/buffer wait at 5 seconds and delivery at 10 seconds. Confirmed conflicts increment `quarantined`; detected conflicts separately increment `conflicts`, and successful isolation produces an error log. An unconfirmed publication leaves the source batch uncommitted. OpenSearch writes that succeeded before a failed batch are recognized on replay.

This is at-least-once quarantine, not an atomic transaction spanning Kafka and OpenSearch. Failure after quarantine acknowledgment but before source commit can create physical duplicates; their stable ID supports downstream deduplication. No deduplication consumer, automated adjudication, alert delivery service or metadata whitelist is added. Existing documents remain unchanged and searchable; retaining them does not establish which version is correct. Permanent mapping/validation failures still stop the batch. The source-coordinate identity assumes the same retained source topic/index lineage, not arbitrary topic deletion/recreation.

## Measured checks

Java 21, Flink 2.3.0, connector 5.1.0-2.3 RC1, Kafka 4.3.1, one isolated broker, one partition per topic, parallelism 1 and a 2 GiB test heap. Temporary OpenSearch 3.9.0 / k-NN 3.9.0.0 used separate data/logs on port 19200, a 512 MiB heap and two active processors. No service configuration changed, no load test ran. The test index was deleted and both temporary services stopped.

| Check | Result |
| --- | --- |
| Actual ALO output: same ID, equal features/vector, different observation time | First case created; second quarantined with both versions and exact source coordinates |
| Valid case following that conflict in the same source partition | Created successfully; source offset advanced through all three records |
| Quarantine broker rejects the payload (`max.message.bytes=128`) | Publication failed; quarantine-success count stayed zero; no source offset committed |
| Restore that topic's message limit and rerun the same group | Conflict quarantined, following case retained, source offset advanced through the batch |
| Quarantine acknowledged, then another bulk item fails permanently before commit | Conflict persisted but source offsets remained uncommitted |
| Reconsume that uncommitted conflict | Two physical quarantine records with identical ID/key/body; one logical identity; source offset advanced after the confirmed retry |
| Stored case after all conflicts/retries | Original content and document version 1 preserved |
| Existing regression checks | Seven JUnit methods pass; 26 event-time traces still match 118 oracle cases; original recovery/replay/indexing checks pass |

The acknowledgment-before-commit test uses a real later bulk-item failure, not a killed process. The failed-publication test uses a real broker rejection, not an outage. These provide focused evidence at the two required boundaries without expanding the broker-failure matrix.

Raw proof: [`kafka-summary.json`](../java/results/kafka-cep-20261029T052948Z/kafka-summary.json), [`tests.log`](../java/results/kafka-cep-20261029T052948Z/tests.log), [`source-sha256.txt`](../java/results/kafka-cep-20261029T052948Z/source-sha256.txt). The run retains `quarantine-actual-alo.json`, `quarantine-after-publication-recovery.json`, `quarantine-ack-before-commit-replay.json`, rejection details and OpenSearch startup/runtime logs.

## Run and article boundary

From `java/`, run `OPENSEARCH_ENDPOINT=http://localhost:9200 sh kafka-check.sh` against an available local OpenSearch service; the script owns its temporary broker. For the normal indexer CLI, create the dedicated quarantine topic first. The existing direct `project(values)` helper remains strict because it has no source coordinates or quarantine destination; Kafka consumption provides the new handling.

Article 1 can now describe and cite an executed ALO downstream consequence: conflict isolation allows later valid work to proceed while preserving both versions, and moving the offset requires a quarantine acknowledgment. Keep the explanation brief; no capacity, high-availability or automatic fraud-decision claim is added. The next resumed project action remains drafting Article 1.
