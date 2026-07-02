# Movie Watchlist DB (Spring Boot)

A small Spring Boot backend for the classic take-home: **add / remove movies to a watch list**
with correct persistence, correct HTTP status codes, and searchable movie catalog metadata.

- **Java 25**, **Maven 3.9.9**, **Spring Boot 3.5.x** with embedded Tomcat.
- Persistence is still repository-driven: in-memory by default, Supabase Postgres when `DB_URL` is set. Movies can carry multiple normalized genre values such as `sci-fi`, `thriller`, and `comedy`.
- Supabase connection logic stays explicit: `DB_URL` / `DB_USER` / `DB_PASSWORD` feed HikariCP directly,
  so the app can keep using the Supabase transaction pooler.

## Run It

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

mvn test
mvn spring-boot:run
```

The app starts on `http://localhost:8080` unless `SERVER_PORT` is set.

Then:

```bash
LIST=11111111-1111-1111-1111-111111111111
MOVIE=00000000-0000-0000-0000-000000000002    # Inception

curl -i -X POST  localhost:8080/watchlists/$LIST/movies \
  -H 'Content-Type: application/json' \
  -d "{\"movieId\":\"$MOVIE\"}"

curl -i          localhost:8080/watchlists/$LIST/movies
curl -i -X DELETE localhost:8080/watchlists/$LIST/movies/$MOVIE
curl -s          localhost:8080/movies?director=Nolan
curl -s          "localhost:8080/movies?actor=Anne&releaseYear=2014&genre=sci-fi"
curl -s          localhost:8080/movies?category=thriller
curl -s          localhost:8080/health/ready
curl -s          localhost:8080/metrics
```

## Supabase Postgres

1. In the Supabase SQL editor, run `db/schema.sql`, then `db/seed.sql`.
2. `cp .env.example .env`.
3. Fill in the **transaction pooler** connection values:

```bash
DB_URL=jdbc:postgresql://aws-0-<region>.pooler.supabase.com:6543/postgres
DB_USER=postgres.<your-project-ref>
DB_PASSWORD=<your-supabase-db-password>
DB_POOL_MAX=10
```

The code still passes those values into HikariCP through `DataSourceFactory`; there is no JPA/Hibernate
session layer and no switch to Supabase's direct database URL.

## API Contract

| Method + path | Success | Failures |
| --- | --- | --- |
| `POST /watchlists/{id}/movies` `{"movieId"}` | `201 Created` + `Location` | list missing `404` · movie unknown `404` · duplicate `409` · bad UUID/JSON `400` |
| `DELETE /watchlists/{id}/movies/{movieId}` | `204 No Content` | list missing `404` · movie not in list `404` |
| `GET /watchlists/{id}/movies` | `200 OK` | list missing `404` |
| `GET /movies?actor=&director=&releaseYear=&genre=` | `200 OK` | bad query value `400` |
| `GET /health`, `GET /health/ready`, `GET /metrics` | `200 OK` (`503` if not ready) | |

Errors return `{"error":{"code":"...","message":"..."}}`.

## Architecture

```text
MovieWatchlistDbApplication
  Spring Boot entry point; sets server.port from AppConfig before booting.

config/
  AppConfig reads env/.env/defaults.
  DataSourceFactory builds the same Hikari pool from DB_URL/DB_USER/DB_PASSWORD.
  ApplicationBeans wires repositories, services, readiness, metrics.

web/
  Spring MVC controllers for watch lists, movies, health, and metrics.
  ApiExceptionHandler maps domain/Spring exceptions to stable JSON errors.
  MetricsInterceptor records route/status/duration for every request.

service/
  WatchListService owns add/remove/list business rules.
  MovieCatalogService owns actor/director/release-year/genre catalog search.

repository/
  Interfaces plus InMemory and Postgres implementations.
  Postgres uses JDBC + Hikari; watchlist add stays atomic via INSERT ... ON CONFLICT.

model/
  Value-typed IDs, Movie metadata, WatchList.
```

## Tests

`mvn test` runs:

- Spring Boot HTTP contract tests on a random embedded Tomcat port.
- Watch-list service tests for add/remove/duplicate/not-found behavior.
- Concurrency test proving one concurrent add wins and the rest conflict.
- Movie catalog search tests for actor/director/release-year/genre filtering.
