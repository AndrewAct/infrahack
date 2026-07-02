# Movie Watchlist DB

Movie Watchlist DB is a Spring Boot backend for a small but realistic movie
catalog and watch-list service. It supports searching a movie catalog by actor,
director, release year, and genre, and lets users add or remove catalog movies
from a watch list with correct persistence, duplicate handling, and HTTP status
codes.

The project started as a debugging-style take-home, but the real goal is deeper:
use a compact domain to practice Spring Boot, JDBC, PostgreSQL schema design,
repository boundaries, API contracts, concurrency-safe persistence, and the
tradeoffs behind MVP vs production-ready data modeling.

The reference implementation lives in:

```text
java/movie-watchlist-db/
```

See [java/movie-watchlist-db/README.md](java/movie-watchlist-db/README.md) for
exact build, run, API, and Supabase setup commands.

## Project Goals

- Learn and reinforce Spring Boot by building the actual HTTP API with Spring
  MVC and embedded Tomcat.
- Keep persistence explicit with JDBC, HikariCP, and PostgreSQL so SQL behavior,
  indexes, query shape, and concurrency semantics stay visible.
- Support the core watch-list workflow: add a movie, reject duplicates, remove a
  movie, and list a watch list's movies.
- Support catalog search by actor, director, release year, and genre.
- Run locally with no database through in-memory repositories, while also
  supporting Supabase Postgres through the transaction pooler.
- Keep the project small enough to reason about in interviews, but real enough
  to discuss failure modes, scaling limits, and future design evolution.

## Current Scope

Implemented:

- `GET /movies`
- `GET /movies?actor=Anne`
- `GET /movies?director=Nolan`
- `GET /movies?releaseYear=2014`
- `GET /movies?genre=sci-fi`
- `GET /movies?category=thriller`
- `POST /watchlists/{watchListId}/movies`
- `GET /watchlists/{watchListId}/movies`
- `DELETE /watchlists/{watchListId}/movies/{movieId}`
- `GET /health`
- `GET /health/ready`
- `GET /metrics`

Out of scope for now:

- User authentication and authorization.
- Watch-list CRUD beyond the seeded sample list.
- External movie ingestion from IMDb/TMDB/etc.
- Full-text search or typo-tolerant search.
- Normalized people/credits/genre tables.
- OpenAPI generation.
- Dockerized deployment.

## Architecture

```text
Spring Boot application
  -> Spring MVC controllers
  -> service layer
  -> repository interfaces
  -> in-memory repositories or PostgreSQL JDBC repositories
  -> Supabase Postgres
```

The key boundary is the repository interface layer. The service layer depends on
interfaces, not on Postgres-specific implementations. This lets the same domain
logic run against:

- an in-memory store for local zero-config development and fast tests;
- a Postgres-backed store for Supabase persistence.

The project intentionally does not use JPA/Hibernate. That choice keeps the SQL
visible: `INSERT ... ON CONFLICT`, array containment with `genres @> ARRAY[...]`,
GIN indexes, and JDBC parameter binding are all part of the learning surface.

## Data Model

The MVP schema uses:

- `movies`
- `watchlists`
- `watchlist_movies`

Movies include:

- `id`
- `title`
- `release_year`
- `director`
- `actors TEXT[]`
- `genres TEXT[]`

Watch-list membership is stored as a join table:

```sql
watchlist_movies (
  watchlist_id UUID,
  movie_id UUID,
  added_at TIMESTAMPTZ,
  PRIMARY KEY (watchlist_id, movie_id)
)
```

This is a deliberate design choice. A JSON array of movie IDs on `watchlists`
would be simpler at first, but it would make duplicate prevention, removal, and
large-list operations weaker. The join table gives an indexed, atomic membership
model.

## Design Logic

### Watch-list Operations

Adding a movie follows this sequence:

```text
read -> validate watch list exists -> validate movie exists -> mutate atomically -> await -> respond
```

The Postgres implementation uses:

```sql
INSERT INTO watchlist_movies (watchlist_id, movie_id)
VALUES (?, ?)
ON CONFLICT DO NOTHING
```

The composite primary key prevents duplicates. The affected row count tells the
repository whether this request inserted a new membership or hit an existing
membership. That maps cleanly to:

- `201 Created` for a new add;
- `409 Conflict` for a duplicate.

### Catalog Search

Movie search is exposed through query parameters:

```http
GET /movies?actor=Anne&director=Nolan&releaseYear=2014&genre=sci-fi
```

This is read-only filtering, so query parameters are a natural fit. The service
builds a `MovieSearchCriteria`, normalizes genre/category input through
`GenreNormalizer`, and delegates to the repository.

Genres are stored as normalized values such as:

```text
sci-fi
thriller
comedy
drama
```

That avoids mismatches such as `Sci Fi`, `SCI_FI`, and `sci-fi` becoming
different logical genres.

Postgres genre search uses array containment:

```sql
genres @> ARRAY[?]::text[]
```

The schema adds a GIN index:

```sql
CREATE INDEX IF NOT EXISTS idx_movies_genres ON movies USING GIN (genres);
```

GIN indexes are appropriate here because a single `genres` column contains
multiple searchable values.

## Key Tradeoffs

### JDBC Instead of JPA

JDBC is more verbose than JPA, but it makes the database behavior explicit. That
is useful for this project because the important parts are not plain CRUD:

- atomic inserts with `ON CONFLICT`;
- array containment with `TEXT[]`;
- GIN indexing;
- exact HTTP status mapping from persistence outcomes;
- Supabase transaction-pooler-friendly short statements.

JPA would reduce boilerplate for simple entity persistence, but for Postgres
array operators and custom search queries it would often fall back to native SQL
anyway.

### `TEXT[]` for Actors and Genres

For an MVP, arrays keep the schema compact and easy to seed. They are good
enough for catalog search in this project.

The tradeoff is that arrays are not a full production catalog model. A larger
system would likely normalize this into:

```text
people
movie_credits
genres
movie_genres
```

That would support actor pages, aliases, billing order, roles, genre metadata,
and better analytical queries.

### Fixed Optional-Filter SQL

The current Postgres search query uses optional predicates. It works and is
testable, but it is not the most maintainable shape as filters grow. A future
production version should move toward dynamic SQL generation or named
parameters, so only the predicates actually requested by the user appear in the
final SQL.

### In-Memory and Postgres Implementations

The in-memory repositories make tests fast and local development easy. The risk
is behavioral drift between the in-memory and Postgres implementations. The
mitigation is contract-style tests and keeping repository behavior small and
explicit.

## Correctness Invariants

- A movie must exist in the catalog before it can be added to a watch list.
- A watch list must exist before movies can be added to or removed from it.
- A movie can appear at most once in a given watch list.
- Duplicate adds must be detected atomically under concurrency.
- Removing a movie that is not in the list must not report false success.
- API responses should be sent only after the persistence operation completes.
- Genre search should use normalized genre values.
- Supabase connection settings should preserve the transaction pooler URL and
  credentials flow.

## Failure Modes

- Missing watch list: return `404`.
- Unknown movie: return `404`.
- Duplicate add: return `409`.
- Bad UUID or malformed JSON: return `400`.
- Database pool exhaustion: fail visibly rather than hang indefinitely.
- Supabase schema not initialized: repository calls fail until `schema.sql` and
  `seed.sql` are applied.
- Maven using the wrong JDK: Java 25 code fails with `release version 25 not
  supported` until `JAVA_HOME` points to JDK 25.

## Observability

The application exposes:

- `GET /health` for liveness.
- `GET /health/ready` for readiness.
- `GET /metrics` for a small Prometheus-style metrics surface.

Current metrics are intentionally lightweight: request counts and request
duration by route/status. A production service would add query-level timing,
slow query analysis, `pg_stat_statements`, traces, and dashboards.

## Testing Strategy

The Java module test suite covers:

- Spring Boot HTTP contract tests on a random embedded Tomcat port.
- Watch-list service behavior for add/remove/duplicate/not-found cases.
- A concurrency test proving exactly one concurrent add succeeds.
- Movie catalog search by actor, director, release year, and genre.

Run:

```bash
cd java/movie-watchlist-db
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn test
```

## Lessons Learned

- Spring Boot is not just the `main` class; the value is in clear controller,
  service, repository, and configuration boundaries.
- A small API still benefits from explicit domain errors and stable JSON error
  envelopes.
- SQL shape matters. The exact query determines whether indexes can help and
  whether future engineers can safely modify the code.
- `PreparedStatement` protects values from SQL injection, but dynamic SQL still
  requires whitelisting for column names, sort fields, and directions.
- Transaction poolers favor short, stateless database interactions. Avoid
  session-level assumptions when targeting Supabase's transaction pooler.
- MVP data models are allowed, but the README should name their limits so the
  design does not pretend to be more production-ready than it is.
- Tests are most useful when they protect the project claims: duplicate
  prevention, status mapping, search behavior, and persistence awaiting.

## Possible Improvements

- Replace optional-filter SQL with a small dynamic SQL builder or Spring
  `NamedParameterJdbcTemplate` for better maintainability.
- Add pagination to `GET /movies`, using stable ordering such as
  `ORDER BY title, id`.
- Add watch-list-scoped search:
  `GET /watchlists/{watchListId}/movies?genre=sci-fi`.
- Normalize catalog metadata into `people`, `movie_credits`, `genres`, and
  `movie_genres`.
- Add OpenAPI documentation and generated API examples.
- Add Docker Compose for local Postgres testing.
- Add Testcontainers for repository contract tests against real Postgres.
- Add schema migrations through Flyway or Liquibase.
- Add query-level observability and `EXPLAIN ANALYZE` notes for important
  indexes.
- Add authentication and per-user watch-list authorization.

## Interview Surface

Be ready to explain:

- Why the watch-list membership is a join table instead of a JSON array.
- How duplicate adds are prevented under concurrency.
- Why `POST /watchlists/{id}/movies` uses a body, while `DELETE` identifies the
  membership in the path.
- Why catalog search lives under `GET /movies?...`.
- How JDBC positional parameters map to SQL placeholders.
- Why genre search uses normalized values and a GIN index.
- Why JDBC was chosen over JPA for this learning project.
- What would change in a production catalog model.
- How Supabase transaction pooler constraints influence connection and query
  design.

## Layout

```text
movie-watchlist-db/
  README.md
  java/movie-watchlist-db/
    README.md
    pom.xml
    db/schema.sql
    db/seed.sql
    src/main/java/...
    src/test/java/...
```

The Java implementation is the reference implementation. Additional language
implementations could implement the same API contract later.
