# Payment retry CEP design

The engineering question is whether event-time finalization preserves the intended payment witness and feature cutoff under out-of-order delivery. This slice uses Java 21 and Flink 2.3.0, with synthetic nested commerce events. It implements the agreed single-card contract rather than introducing account-wide joins or model inference.

## Flow and ownership

`timestamped JSON → validation → keyBy(tenant, account, card namespace, card) → admission/dedup → ordered features → CEP → case JSON`

`PaymentCep.build` consumes strings whose record timestamps equal `occurred_at`. The caller owns the source watermark policy. The JSONL demo uses bounded disorder; tests explicitly emit watermarks and finish bounded input to distinguish finalization from a missing match. IDs and synthetic card references are metadata, never numeric vector coordinates. The full nested source is preserved in fixtures; canonical payload comparison excludes transport `event_id` and `emitted_at` but includes event time, source sequence and business facts.

`KafkaPaymentCepJob` supplies the Kafka source and transactional case sink around this same pipeline. Kafka record keys encode tenant/account; the operators retain the full account/card key. `CaseIndexer` consumes committed cases synchronously and projects them to OpenSearch with create-only bulk operations. It bounds request items/bytes and retry rounds, and commits only fully handled batches. Same-ID/different-content cases preserve the stored document and publish both versions plus source coordinates to a Kafka quarantine topic before offset commit. If publication is unconfirmed, or another permanent indexing error occurs, the batch stops without advancing. Detailed evidence is in [the integration results](kafka-results.md) and [the quarantine follow-up](quarantine-results.md).

Admission rejects `occurred_at <= currentWatermark` before changing any ledger or feature state. The attempt ledger is scoped to the full key; an event-ID ledger also catches reuse under a different attempt. Equal business content is a duplicate, changed content is a conflict. Each accepted attempt registers an event-time cleanup timer at `t + 120000`; duplicate aliases share its immutable time and cannot extend retention. After cleanup, immutable redeliveries are late. Changed key/time after ledger expiry is outside this bounded conflict guarantee.

The feature operator buffers unique events in managed state until its timestamp timer fires. Within a timestamp it sorts by `(source_sequence, event_id)`. It updates the ten-minute history and computes S's snapshot before processing later equal-time positions. Timer output retains the original timestamp and precedes the forwarded watermark, allowing CEP to consume the same ordered logical stream without late emissions. History uses `(S.time - 600000, S.time]` through S's ordered position and has independent expiry timers.

CEP uses `times(3).consecutive().next(success)`, `skipPastLastEvent`, a 120-second window and a deterministic comparator. This starts candidates at successive failures, so FFFFS yields the latest FFFS. All terminal authorization outcomes enter the stream; authorizations must break a failure run. Flink 2.3.0's exact timeout boundary is validated at 119999, 120000 and 120001 milliseconds. [Flink CEP documentation](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/libs/cep/) describes strict contiguity, comparators and event-time buffering.

The output contains a deterministic SHA-256 case ID from run/tenant/rule/terminal event, ordered witness IDs, eight features, cutoff/history/completeness/version metadata and, for complete snapshots, a float32-scaled vector. The vector is the existing explainable L2 baseline; the later enriched encoder/cosine experiment is separate. Missing device/IP telemetry, mixed currency, insufficient declared source coverage or a known conflict suppress the vector, not the CEP witness. The source coverage assertion is trusted fixture metadata, not inferred proof that the source lost no data.

## Decisions and limits

- Two separate keyed operators make admission and ordering explicit. A single operator might reduce state copying, but would conceal the pre-mutation late boundary and complicate independent testing.
- Cleanup scans the retained ledger; ordered snapshots scan the ten-minute history. This favors hand-readable correctness code. Timestamp-bucket cleanup and incremental statistics require a capacity question before optimization.
- The two-minute horizon is not a byte bound. Retention includes watermark lag; stalled progress grows dedup and ordering state. Snapshots retain nested canonical business strings, so test snapshot sizes are not a universal bytes-per-payment estimate.
- Conflict handling is conservative for not-yet-emitted snapshots. It marks the key incomplete until both conflicting facts age out. It does not revise finalized cases or introduce an implicit correction contract.
- A single-source, parallelism-one MiniCluster isolates time semantics. Tests do not establish multi-partition watermark behavior, hot-key capacity or production availability.
- Operator-harness restoration proves managed ledger/history/timer serialization. The added Kafka integration test separately verifies whole-job task recovery with a pending CEP pattern, restored source positions and committed output. It does not test JobManager process loss or broker/node loss.
- `knowledge_time` is assigned at local emission and persisted in the committed case log. An index rebuild preserves it by consuming those bytes; independent raw recomputation uses a separate namespace and observation time. No first-observation registry is needed for this study.
- Quarantine provides continuation, not adjudication. Full comparison includes observation metadata; no field whitelist or automatic version selection is added. Its publication and the source offset commit are separate operations, so replay can duplicate quarantine records. A deterministic ID from target index and retained source coordinates makes that repetition identifiable; producer idempotence alone does not deduplicate a restarted application. Existing OpenSearch documents remain searchable pending any separately defined review/correction policy.
- The JSONL runner remains bounded and reads its small input into memory. The Kafka entry point can run continuously or against a bounded source-offset snapshot. The necessary isolated-broker setup was reused; historical smoke prototypes remain historical evidence rather than being migrated wholesale.

## Acceptance

The independent oracle uses ordinary maps, an ordered scan and its own feature arithmetic; it does not call the production admission, feature, comparator or CEP code. Compare full ordered witnesses, integer features exactly, fractional features within 1e-10 (far below one millisecond), float32 vector coordinates within 1e-7, complete/incomplete state, and rejected record IDs. Compare reordered and ordered logical documents after removing local observation time. Named golden assertions check the latest-three witness, strict duration boundary and equal-time/history cutoff.

Managed-state tests assert entry retention at W=119999, deletion at W=120000, late rejection after restore/expiry, stalled-watermark growth, removal of exactly due entries, ten-minute history expiry and restoration of pending feature buffers. Serialized operator-snapshot byte counts establish storage evidence only. Test inputs and raw results are retained with the source hash; no timing or fraud-quality conclusion is drawn.

## Next slice

The article-sized streaming integration is complete. Article 1 can be drafted from the existing event-time and recovery evidence. Article 2 adds the seeded/versioned representation, frozen-corpus exact/HNSW comparison and one brief memory/CPU/load benchmark. Fixed graph settings and the existing single-machine setup keep that work focused. Model serving, generalized recovery infrastructure, cross-key joins and multi-node/capacity tuning remain deferred.
