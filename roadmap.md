# InfraHack Roadmap

This roadmap records completed InfraHack milestones. Keep entries short, dated, and focused on shipped work.

- [x] Event dispatcher prototype (May 24, 2026)
- [x] Python task scheduler prototype (June 13, 2026)
- [x] Parking lot OOD (Java) — concurrency-safe allocation, pluggable assignment/pricing, optimistic-CAS exit, driver/admin roles, metrics + audit (June 25, 2026)
- [x] Movie watchlist DB (Java) — framework-free watch-list membership: value-typed IDs, atomic add via `ON CONFLICT`/concurrent-set, async-awaited persistence, in-memory ↔ Supabase Postgres behind one repo interface, JDK HttpServer, Prometheus `/metrics` + health, 22 tests (July 1, 2026)
- [x] Temporal key-value store (Python) — append-only history, TTL/tombstone visibility, binary-search time travel, 16 tests, and a 200k-operation benchmark (August 20, 2026)

- [x] Payment-retry Flink CEP (Java) — ordered features, business deduplication and independent-oracle correctness evidence (October 27, 2026)
- [x] Payment-retry Kafka/CEP integration (Java) — task recovery, replay and immutable OpenSearch projection (October 28, 2026)
- [x] Case conflict quarantine (Java) — acknowledged Kafka isolation before source-offset commit, with replay safety (October 29, 2026)
- [x] Historical case retrieval and load evidence (Java) — verified HNSW/exact matrix and 92,750-case pipeline ladder (October 30, 2026)
- [x] Java concurrency kitchen experiments — JDK 25 monitor/CAS correctness, CopyOnWrite snapshots, bounded platform/virtual-thread load, and 13 tests (October 24, 2026)

- [x] Java kitchen meal readiness — independent futures, atomic assigned-rider claims, and timeout/interruption tests (November 8, 2026)
