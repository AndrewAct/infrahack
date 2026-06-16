# Python Task Scheduler

A small FastAPI + Postgres implementation of a distributed task scheduler, sized for a
60-minute system-design / OOD demo. The scheduling state machine lives in
`core/service.py`; FastAPI is just a thin HTTP shell.

## Design at a glance

Two tables are the core (see `sql/001_reset_schema.sql`):

- **`tasks`** — the schedule *definition* (one-time or fixed-interval), priority,
  `next_run_at`, `max_attempts`, status (`active|paused|cancelled`).
- **`task_runs`** — each concrete *execution* instance, with `status`
  (`pending|running|succeeded|failed|dead`), `attempt`, lease fields, and a
  `UNIQUE(task_id, scheduled_for)` slot that makes materialization idempotent.

Three roles (`core/service.py`, `core/scheduler.py`):

- **Scheduler tick** — scans `tasks.next_run_at <= now()` (partial index), inserts a
  `pending` run per due slot, advances `next_run_at`.
- **Worker** — `claim` uses `FOR UPDATE SKIP LOCKED` so concurrent workers grab disjoint
  batches with no double-claim; `heartbeat`/`complete`/`fail` are gated by a per-claim
  `lease_token` (fencing) and a non-expired lease.
- **Reaper** — requeues (with exponential backoff + jitter) or dead-letters runs whose
  lease expired (lost workers).

This is **at-least-once** execution: a worker that finishes after its lease expired is
rejected, the run is retried, so workers must be idempotent.

### Why psycopg (not supabase-py)

A scheduler's correctness rests on transactions and row locks — `FOR UPDATE SKIP LOCKED`,
atomic claim, `ON CONFLICT`. The Supabase REST client (PostgREST) can't express those, so
we talk to Postgres directly with `psycopg`. Hosted Postgres (incl. Supabase's) is fine;
the REST *client* is the wrong abstraction for queue semantics.

## Scope

Implemented: one-time + fixed-interval tasks; priority 0–9; task CRUD + pause/resume/cancel;
worker pull with leases + heartbeat; at-least-once with bounded exponential-backoff retries;
dead-letter as a run status; run history; background scheduler + manual `/admin/tick`.

Deferred (stated on purpose): cron expressions / DST; auth / RBAC / multi-tenancy; Redis or
a real MQ; distributed tracing + metrics. See `../CHEATSHEET.md` for how each would be added.

## Setup

```bash
cd task-scheduler/python
uv venv
uv pip install -r requirements-dev.txt
```

Set `DATABASE_URL` in `.env` (copy from `.env.example`). With Supabase it's
**Dashboard → Settings → Database → Connection string → URI** — note the service-role /
secret key is *not* the DB password. Then apply the schema once
(`sql/001_reset_schema.sql`) via `psql` or the Supabase SQL editor.

## Run

```bash
uv run uvicorn task_scheduler.main:app --reload
```

Open <http://127.0.0.1:8000/docs>. The background scheduler tick + reaper run every
`SCHEDULER_INTERVAL_SECONDS`; set `SCHEDULER_ENABLED=false` to drive it manually with
`POST /admin/tick` and `POST /admin/reap`.

## Demo flow

```bash
# create a task that is due now
curl -X POST http://127.0.0.1:8000/tasks -H 'content-type: application/json' -d '{
  "name": "generate-report",
  "payload": {"customer_id": "c-123"},
  "priority": 8,
  "schedule": {"kind": "one_time"}
}'

# (scheduler tick materializes a run, or POST /admin/tick)
# claim it
curl -X POST http://127.0.0.1:8000/workers/worker-1/claims \
  -H 'content-type: application/json' -d '{"max_tasks": 1, "lease_seconds": 30}'

# use the returned run id + lease_token
curl -X POST http://127.0.0.1:8000/runs/<run_id>/complete \
  -H 'content-type: application/json' -d '{"lease_token": "<token>", "result": {"ok": true}}'
```

## Verify

```bash
uv run pytest                       # unit + router tests (no DB)
DATABASE_URL=... uv run pytest      # also runs the integration tests
uv run ruff format --check .
uv run ruff check .
```
