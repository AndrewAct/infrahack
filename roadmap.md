# InfraHack Roadmap

This roadmap records completed InfraHack milestones. Keep entries short, dated, and focused on shipped work.

- [x] Event dispatcher prototype (May 24, 2026)
- [x] Python task scheduler prototype (June 13, 2026)
- [x] Parking lot OOD (Java) — concurrency-safe allocation, pluggable assignment/pricing, optimistic-CAS exit, driver/admin roles, metrics + audit (June 25, 2026)
- [x] Movie watchlist DB (Java) — framework-free watch-list membership: value-typed IDs, atomic add via `ON CONFLICT`/concurrent-set, async-awaited persistence, in-memory ↔ Supabase Postgres behind one repo interface, JDK HttpServer, Prometheus `/metrics` + health, 22 tests (July 1, 2026)
- [x] Ride-share dispatch (Java, Spring Boot/Java 25) — rider request → nearby driver matching with atomic Redis eligibility/reservation, idempotent request creation, monotonic location CAS, durable assignment ownership + OCC, transactional outbox → Kafka with transactional consumer dedup, and bounded idempotent payment recovery; 36 Testcontainers-backed tests (August 17, 2026)
