# InfraHack Roadmap

This roadmap records completed InfraHack milestones. Keep entries short, dated, and focused on shipped work.

- [x] Event dispatcher prototype (May 24, 2026)
- [x] Python task scheduler prototype (June 13, 2026)
- [x] Parking lot OOD (Java) — concurrency-safe allocation, pluggable assignment/pricing, optimistic-CAS exit, driver/admin roles, metrics + audit (June 25, 2026)
- [x] Movie watchlist DB (Java) — framework-free watch-list membership: value-typed IDs, atomic add via `ON CONFLICT`/concurrent-set, async-awaited persistence, in-memory ↔ Supabase Postgres behind one repo interface, JDK HttpServer, Prometheus `/metrics` + health, 22 tests (July 1, 2026)
- [x] Temporal key-value store (Python) — append-only history, TTL/tombstone visibility, binary-search time travel, 16 tests, and a 200k-operation benchmark (August 20, 2026)
