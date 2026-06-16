# Distributed Job Scheduler (Python)

A distributed scheduler that places and executes jobs across a **fleet of worker
nodes**, making **resource-aware placement** decisions — given each node's CPU/MEM
capacity and each job's resource demand, it decides *what runs where*, balances load,
tolerates node failures, and elects a scheduler leader for HA.

Postgres is the single source of truth — **no Redis, no ZooKeeper/etcd, no Docker.**

## How this differs from `task-scheduler`

`task-scheduler` answers **WHEN** (a time trigger + durable work queue). This project
adds **WHERE** — the resource/placement dimension it deliberately leaves out:

| | task-scheduler | distributed-job-scheduler |
|---|---|---|
| worker | undifferentiated consumer | **node with CPU/MEM capacity** (`nodes` table) |
| job | a payload | payload **+ resource demand** (`req_cpu`, `req_mem_mb`) |
| claim | take highest-priority pending | take highest-priority pending **that fits free capacity** |
| HA | single scheduler | **leader election via `pg_try_advisory_lock`** |
| failure unit | worker (lease) | worker **and whole node** (heartbeat → reschedule) |

The spine is the `nodes` table + the resource-aware claim. A node's **free capacity is
derived, never stored**: `free = cpu_total - sum(req_cpu over its running runs)`, so it
self-heals the moment a run finishes/fails/is reaped.

## Architecture (Postgres-only)

```
   Client ──▶ API Server ──▶ ┌──────── PostgreSQL (jobs│job_runs│nodes) ───────┐
                             │   ▲ advisory-lock leader   ▲ heartbeat/claim     │
   Scheduler (leader+shards)─┘   │ tick / reap_runs       │ / report            │
     · tick: due job → run       │ reap_nodes             │                     │
     · placement: run ↔ node ◀───┘                        └── Node Agents ──────┘
     · reap_nodes: node dead → reschedule its runs            node-a  node-b ...
```

## Prerequisites

- Python 3.12, [uv](https://docs.astral.sh/uv/)
- A Postgres database (Supabase works — use a **session-mode** URI, not the
  transaction pooler; leader election and the agents need session connections)

## 1. Setup

```bash
uv venv && source .venv/bin/activate
uv pip install -r requirements-dev.txt
cp .env.example .env        # then set DATABASE_URL
```

## 2. Apply the schema

Run [`sql/001_reset_schema.sql`](sql/001_reset_schema.sql) once against your database.

> ⚠️ It is **destructive** (`DROP`s `jobs` / `job_runs` / `nodes` and the enums). Run
> it yourself; it is not applied automatically.

## 3. Run the local cluster (no Docker)

Each process is a real participant; one laptop, many terminals.

```bash
# Terminal 1 — API + leader scheduler (ticks, reaps nodes/runs)
uv run uvicorn main:app --reload

# Terminals 2..N — worker nodes with different capacities
uv run python node_agent.py node-a --cpu 4 --mem 8192
uv run python node_agent.py node-b --cpu 2 --mem 4096

# Seed work, then watch placement
uv run python demo_seed.py --jobs 30 --tenants 2
curl -s http://127.0.0.1:8000/nodes | python -m json.tool   # cpu_free per node
```

- Swagger UI: <http://127.0.0.1:8000/docs> · Health: `/health`

## 4. Failure demos (signals == node faults)

```bash
kill -9   <agent-pid>   # hard crash  -> leases expire -> runs rescheduled elsewhere
kill -STOP <agent-pid>  # freeze (no heartbeat) -> looks dead -> rescheduled;
kill -CONT <agent-pid>  #   on resume its stale fencing token is rejected (no double-run)
```

For leader failover, run a second `uvicorn` on another port (`--port 8001`) so two
schedulers contend for the advisory lock; `kill -STOP` the leader and the standby takes
over within one cycle.

## 5. Test & lint

```bash
uv run pytest test/test_placement.py -q   # unit, no DB
uv run pytest -q                          # + integration (needs DATABASE_URL + schema)
uv run ruff check . && uv run ruff format .
```

Integration tests cover: capacity-bounded claim, no double-claim across nodes, capacity
returning after complete, node-death rescheduling, and fencing-token rejection.

## Configuration

Set in `.env` (see [`.env.example`](.env.example)):

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | _(required)_ | Postgres connection (session mode) |
| `SCHEDULER_ENABLED` | `true` | Run the leader-gated tick/reap loop |
| `SCHEDULER_INTERVAL_SECONDS` | `1.0` | Scheduler cycle period |
| `NODE_HEARTBEAT_TIMEOUT_SECONDS` | `15.0` | Silence before a node is declared dead |
| `RETRY_BASE_SECONDS` / `RETRY_CAP_SECONDS` | `2.0` / `300.0` | Retry backoff bounds |

## Out of scope (by design)

- **Runtime resource *isolation*** (cgroups/containers). We do scheduling-layer resource
  *accounting + placement*, not kernel-level enforcement — that belongs to the executor.
- **Weighted fair-share / preemption / central bin-packing.** `tenant_id` is snapshotted
  so the grouping key is ready; the weighting/aging order and push-based placement are the
  next layer. Today: pull-based, resource-aware, strict priority.
- **Redis dispatch layer** — only worth it once measured PG claim QPS is the bottleneck.
- **Exactly-once** — at-least-once + fencing tokens; jobs should be idempotent.
