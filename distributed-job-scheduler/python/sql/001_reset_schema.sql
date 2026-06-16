-- Distributed job scheduler schema.
--
-- Builds on the task-scheduler model (definition vs execution instance) and adds
-- the one dimension that makes THIS system "distributed-with-resources":
--
--   jobs      = schedule DEFINITION + resource DEMAND (intent, mutable)
--   job_runs  = each concrete EXECUTION instance (fact / history, append-mostly)
--   nodes     = the worker FLEET, each carrying a fixed CPU/MEM capacity   <-- new
--
-- The scheduler places a job_run onto a node that has free capacity (resource-aware
-- claim). A node's free capacity is DERIVED, never stored:
--
--     free = cpu_total - sum(req_cpu over its 'running' runs)
--
-- so it self-heals: the instant a run leaves 'running' (succeeded/failed/dead/reaped)
-- it stops counting, and the capacity returns with zero bookkeeping and no drift.

create extension if not exists pgcrypto;

drop table if exists public.job_runs cascade;
drop table if exists public.jobs cascade;
drop table if exists public.nodes cascade;

drop type if exists public.run_status;
drop type if exists public.job_status;
drop type if exists public.node_status;
drop type if exists public.schedule_kind;

create type public.schedule_kind as enum ('one_time', 'interval');
create type public.job_status    as enum ('active', 'paused', 'cancelled');
create type public.run_status     as enum ('pending', 'running', 'succeeded', 'failed', 'dead');
create type public.node_status    as enum ('active', 'draining', 'dead');

-- jobs: the schedule DEFINITION + resource DEMAND (mutable intent).
create table public.jobs (
    id               uuid primary key default gen_random_uuid(),
    name             text not null,
    description      text,
    tenant_id        text not null default 'default',  -- fair-share grouping key (weighting layer is roadmap)
    payload          jsonb not null default '{}'::jsonb,
    priority         smallint not null default 5 check (priority between 0 and 9),
    req_cpu          integer not null default 1   check (req_cpu > 0),     -- whole CPU units
    req_mem_mb       integer not null default 128 check (req_mem_mb > 0),  -- MB
    schedule_kind    public.schedule_kind not null,
    interval_seconds integer check (interval_seconds is null or interval_seconds > 0),
    status           public.job_status not null default 'active',
    next_run_at      timestamptz,                 -- scheduler scans this; null = no more runs
    max_attempts     smallint not null default 3 check (max_attempts >= 1),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint interval_requires_seconds check (
        (schedule_kind = 'interval'  and interval_seconds is not null)
        or (schedule_kind = 'one_time' and interval_seconds is null)
    )
);

-- Partial index: only active rows, ordered by next_run_at. The "due" scan costs
-- proportional to rows actually due, not to table size.
create index idx_jobs_due on public.jobs (next_run_at) where status = 'active';

-- nodes: the worker fleet. Capacity is fixed at registration; free is derived (header).
create table public.nodes (
    id                text primary key,             -- agent-chosen id (e.g. host:pid)
    cpu_total         integer not null check (cpu_total > 0),
    mem_total_mb      integer not null check (mem_total_mb > 0),
    status            public.node_status not null default 'active',
    last_heartbeat_at timestamptz not null default now(),  -- node liveness; silence => dead
    registered_at     timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

-- Node reaper scan: live nodes ordered by heartbeat (find the silent ones cheaply).
create index idx_nodes_live on public.nodes (last_heartbeat_at) where status = 'active';

-- job_runs: each concrete EXECUTION instance. req_*/tenant are SNAPSHOT from the job
-- so placement reads one table and the execution input is immutable/reproducible.
create table public.job_runs (
    id               uuid primary key default gen_random_uuid(),
    job_id           uuid not null references public.jobs(id) on delete cascade,
    tenant_id        text not null,               -- snapshot, for fair-share grouping at claim
    scheduled_for    timestamptz not null,        -- logical slot this run is for
    available_at     timestamptz not null default now(),  -- not-before for claim (retry backoff)
    status           public.run_status not null default 'pending',
    priority         smallint not null,           -- snapshot -> single-table placement
    req_cpu          integer not null,            -- snapshot resource demand
    req_mem_mb       integer not null,
    payload          jsonb not null,              -- snapshot -> immutable execution input
    attempt          smallint not null default 0,
    max_attempts     smallint not null,
    assigned_node_id text references public.nodes(id) on delete set null,  -- which node holds/ran it
    lease_token      uuid,                         -- fencing token, rotated on every claim
    lease_expires_at timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    error            text,
    result           jsonb,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    -- one run per (job, slot): makes scheduler materialization idempotent
    unique (job_id, scheduled_for)
);

-- Placement claim: pending runs in priority order. Resource fit (req <= free) is an
-- extra WHERE filter applied at claim time; the index gives the ordering for free.
create index idx_runs_claimable on public.job_runs (priority desc, available_at)
    where status = 'pending';
-- Run-level reaper: running runs whose lease has expired (lost worker).
create index idx_runs_lease on public.job_runs (lease_expires_at) where status = 'running';
-- Per-node running set: powers the derived free-capacity aggregate AND node-death requeue.
create index idx_runs_node on public.job_runs (assigned_node_id) where status = 'running';
create index idx_runs_job on public.job_runs (job_id);
