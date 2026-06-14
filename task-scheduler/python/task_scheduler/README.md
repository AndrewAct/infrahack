# Task Scheduler (Python) — Run & Test

How to bring the service up locally and run the tests. For the design, see
[`../README.md`](../README.md). All commands below run from **this directory**
(`python/task_scheduler/`).

## Prerequisites

- Python 3.12
- [uv](https://docs.astral.sh/uv/)
- A Postgres database (we use Supabase — a managed Postgres)

## 1. Setup

```bash
uv venv                                   # create .venv
source .venv/bin/activate                 # the (.venv) prompt
uv pip install -r ../requirements-dev.txt # app deps + dev tools (pytest, ruff, ty)
```

`../requirements-dev.txt` pulls in `requirements.txt` (the app deps) plus the dev
toolchain, so this one install covers running and testing.

## 2. Configure the database connection

```bash
cp ../.env.example .env        # then edit .env and set DATABASE_URL
```

`.env` is git-ignored (it holds the DB password). With Supabase, use the
**Session Pooler** URI (Dashboard → Settings → Database → Connection string), which is
IPv4-reachable and keeps full Postgres semantics:

```
DATABASE_URL=postgresql://postgres.<ref>:<password>@aws-1-<region>.pooler.supabase.com:5432/postgres
```

> The Supabase **service-role key is not** the database password. The direct
> `db.<ref>.supabase.co` host is IPv6-only and will time out on IPv4 networks — use the
> pooler host above.

## 3. Apply the schema

Run [`sql/001_reset_schema.sql`](sql/001_reset_schema.sql) once against your database
(Supabase SQL Editor, or your IDE's datasource console).

> ⚠️ It is **destructive**: it `DROP`s and recreates `tasks` / `task_runs` and the enums.
> Run it yourself; it is not applied automatically.

## 4. Run the service

```bash
uv run uvicorn main:app --reload
```

- Swagger UI: <http://127.0.0.1:8000/docs>
- Health check: <http://127.0.0.1:8000/health>

On startup the app opens the connection pool and (unless `SCHEDULER_ENABLED=false`)
starts a background scheduler that runs `tick` + `reap` every second.

## 5. Test

```bash
uv run pytest -q                                  # everything
uv run pytest test/test_retry.py test/test_router.py -q   # unit only, no DB needed
```

- **Unit tests** (`test_retry.py`, `test_router.py`) need no database.
- **Integration tests** (`test_database.py`) run against the real Postgres and are
  **skipped unless `DATABASE_URL` is set** in `.env`. They cover the full
  materialize → claim → complete / retry path, fencing-token rejection, and a
  concurrent-claim invariant (8 workers, no double-claim). Apply the schema (step 3)
  first.

## 6. Lint & type-check

```bash
uv run ruff check .      # lint
uv run ruff format .     # format
uv run ty check          # type-check
```

## Configuration reference

Set in `.env` (see [`../.env.example`](../.env.example)):

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | _(required)_ | Postgres connection string |
| `SCHEDULER_ENABLED` | `true` | Run the background tick/reap loop |
| `SCHEDULER_INTERVAL_SECONDS` | `1.0` | How often the scheduler ticks |
| `RETRY_BASE_SECONDS` | `2.0` | Exponential-backoff base for retries |
| `RETRY_CAP_SECONDS` | `300.0` | Backoff ceiling |
