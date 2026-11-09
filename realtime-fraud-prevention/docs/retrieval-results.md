# Historical retrieval benchmark — October 30, 2026

Provenance: **measured**. Codex ran the Java benchmark on the local Apple M5 Pro / 48 GiB machine. This completes the frozen static matrix for Part 2; the completed closing ladder is recorded in [load results](load-results.md).

## Environment and method

OpenSearch 3.9.0 / k-NN 3.9.0.0, JDK 25.0.5 server, 2 GiB heap, four active processors; Java 21 client with 2 GiB heap. One local primary shard, no replicas, refresh disabled while building, explicit frozen refresh/merge before timing. The final selected observations have one search segment each. Warm-cache sequential local HTTP requests; no cloud, containers, or replicas.

Seed 20261026; 200 standardized continuous synthetic attributes formed from eight latent centers plus independent Gaussian noise (sigma 0.65), a seeded random projection followed by tanh and L2 normalization, cosine search. The labels are not fed into retrieval filters. Query episode IDs 1000000..1000011 are disjoint from corpus c0..c99999, but drawn from the same synthetic cluster distribution. This is a geometry/cost model, not learned fraud semantics, measured online cross-key features, or a validation of real fraud relevance. The matrix's encoded 8D point is distinct from the streaming explainable 8D contract.

Every point uses m=16 / ef_construction=100 / K=10. Query breadth 100 is the main comparison, with 32/200 at D=256. Twelve held-out queries × three repetitions give 36 samples per mode (twelve independent query examples, not 36). Two warmup passes; alternate exact/ANN order and reverse query order in the middle repetition. Timers include request construction/JSON, local HTTP, server work and response parsing. The exact script and native ANN path are different implementations; speed ratios do not isolate algorithm complexity. Both use an HNSW-enabled physical index, so memory saved by choosing flat-only indexing was not measured.

Before timing, all exact top-10s agree with an independent cosine calculation over retained float32 vectors. A 1e-6 score tolerance handles boundary ties. Six exact-vector canaries exercise tenant, currency, future case time, same query account, incompatible version and incomplete-data exclusion. Synthetic fixture evidence cutoff equals case time; the full streaming contract retains separate cutoff fields. Empty/missing result sets fail rather than producing selective successful-query timings. Actual JDI breakpoints at Lucene approximateSearch/exactSearch prove every held-out ANN query/breadth used graph traversal with no exact fallback. A five-candidate filter control enters exactSearch only. The debugger is detached before warmup/timing.

## Raw runs and selected observations

Primary run: `java/results/retrieval-20261030T051228Z`. Corrected 100K/768D point: `java/results/retrieval-20261030T052153Z`. `aggregation-inputs.json` records the replacement; raw files were not overwritten. The first primary 100K/768D search reader still referenced three pre-merge segments, despite a newly committed merged segment. Explicit refresh after force-merge fixed that experiment condition. All other selected primary points already had one search segment. The corrected largest point has one graph-branch entry per query and no fallback. Its lower recall is the selected result; the old multi-segment result is not reported as the final matrix.

`samples.jsonl` retains every request latency, server took, result IDs, recall, repetition/query ID and wall timestamp. `oracle-*.json`, `queries-*.json`, `point-*.json`, `vectors-*.f32`, manifests and source SHA-256 retain inputs and branch/segment/resource metadata. Vectors are big-endian float32 in ascending c-ID order. The API store-size snapshots can include stale/unflushed or pre-refresh accounting; they are retained but not used as an isolated index-footprint comparison.

## Results

| N | D | Mode | Median ms | P95 ms | Mean Recall@10 | Worst query recall |
| --- | --- | --- | --- | --- | --- | --- |
| 10,000 | 8 | exact | 1.8087 | 2.6245 | 1.0000 | 1.00 |
| 10,000 | 8 | hnsw-100 | 1.3105 | 2.0072 | 1.0000 | 1.00 |
| 10,000 | 64 | exact | 1.5923 | 2.3834 | 1.0000 | 1.00 |
| 10,000 | 64 | hnsw-100 | 0.9178 | 1.3614 | 1.0000 | 1.00 |
| 10,000 | 256 | exact | 2.8870 | 4.7835 | 1.0000 | 1.00 |
| 10,000 | 256 | hnsw-100 | 1.0165 | 2.5271 | 1.0000 | 1.00 |
| 10,000 | 256 | hnsw-200 | 1.0156 | 2.5045 | 1.0000 | 1.00 |
| 10,000 | 256 | hnsw-32 | 0.9366 | 2.4176 | 0.9667 | 0.70 |
| 10,000 | 768 | exact | 6.1684 | 7.3605 | 1.0000 | 1.00 |
| 10,000 | 768 | hnsw-100 | 1.1226 | 1.3877 | 0.9917 | 0.90 |
| 100,000 | 8 | exact | 6.5327 | 7.1070 | 1.0000 | 1.00 |
| 100,000 | 8 | hnsw-100 | 0.9513 | 1.1439 | 1.0000 | 1.00 |
| 100,000 | 64 | exact | 8.0088 | 8.2510 | 1.0000 | 1.00 |
| 100,000 | 64 | hnsw-100 | 0.8089 | 0.9136 | 0.9833 | 0.90 |
| 100,000 | 256 | exact | 20.4171 | 26.8263 | 1.0000 | 1.00 |
| 100,000 | 256 | hnsw-100 | 1.0247 | 2.3821 | 0.9417 | 0.80 |
| 100,000 | 256 | hnsw-200 | 1.2289 | 2.4295 | 0.9917 | 0.90 |
| 100,000 | 256 | hnsw-32 | 1.1711 | 2.2054 | 0.6833 | 0.30 |
| 100,000 | 768 | exact | 53.2919 | 54.1545 | 1.0000 | 1.00 |
| 100,000 | 768 | hnsw-100 | 1.7580 | 2.2312 | 0.8583 | 0.70 |

The main point is 100K/256D: exact median 20.4171 ms; breadth-100 ANN median 1.0247 ms at 0.9417 mean Recall@10; breadth-200 ANN median 1.2289 ms at 0.9917 mean Recall@10 (worst query 0.90). Breadth 32 averages 0.6833 recall. Median ratios and means are computed from the retained observations, not independent trials of many new queries. Small-sample P99 is retained in aggregate.json; it should not be used as a production tail guarantee.

## Reproduce

From the companion Java directory, start the isolated node with `sh article-node.sh` in another terminal, then run `sh retrieval-check.sh`. Run a single corrected point with `sh retrieval-check.sh 100000 768` if needed. Aggregate with `uv run summarize-results.py retrieval PRIMARY --replacement CORRECTION`; without a replacement, pass only PRIMARY. The source uses the existing Maven project and adds no runtime dependencies. Full command/ownership/resource instructions are in the bilingual Java README.

## Development and scope disclosures

The earlier `retrieval-20261030T044603Z` trial completed seven points but an overly broad JDI method-entry observer timed out during the largest path control. It is not the final complete matrix. `retrieval-20261030T051027Z` stopped at a debug-listener reconnect race; bounded reconnect was added. These failures and their raw directories remain retained. The observer was narrowed to concrete branch breakpoints; this is an untimed path check. The final results do not use any failed trial timing.

No point locates the first corpus-size crossover below 10K, establishes a monotonic dimension rule, or measures model accuracy. All times are one machine / warm cache / one fixed synthetic distribution. The new seeded encoder tests and existing 26 event-time traces passed (118 oracle-matching streaming cases); the opt-in prior recovery test is skipped in the ordinary Maven suite and has its separately retained earlier evidence.
