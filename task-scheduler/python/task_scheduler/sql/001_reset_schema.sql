-- Distributed task scheduler schema.
--
-- Two tables are the core of the design:
--   tasks      = the schedule DEFINITION / template (intent, mutable)
--   task_runs  = each concrete EXECUTION instance (fact / history, append-mostly)
--
-- A recurring task spawns many runs. Without task_runs you cannot express retries,
-- leases, history, or monitoring -- it would be a to-do list, not a scheduler.

create extension if not exists pgcrypto;

drop table if exists public.task_runs cascade;
drop table if exists public.tasks cascade;

drop type if exists public.run_status;
drop type if exists public.task_status;
drop type if exists public.schedule_kind;

create type public.schedule_kind as enum ('one_time', 'interval');
create type public.task_status   as enum ('active', 'paused', 'cancelled');
create type public.run_status     as enum ('pending', 'running', 'succeeded', 'failed', 'dead');

create table public.tasks (
    id               uuid primary key default gen_random_uuid(),
    name             text not null,
    description      text,
    payload          jsonb not null default '{}'::jsonb,
    priority         smallint not null default 5 check (priority between 0 and 9),
    schedule_kind    public.schedule_kind not null,
    interval_seconds integer check (interval_seconds is null or interval_seconds > 0),
    status           public.task_status not null default 'active',
    next_run_at      timestamptz,                 -- scheduler scans this; null = no more runs
    max_attempts     smallint not null default 3 check (max_attempts >= 1),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    -- interval tasks must define interval_seconds; one_time must not
    constraint interval_requires_seconds check (
        (schedule_kind = 'interval'  and interval_seconds is not null)
        or (schedule_kind = 'one_time' and interval_seconds is null)
    )
);

-- Partial index: only active rows, ordered by next_run_at.
-- Makes the "due" scan a cheap range scan whose cost is proportional to rows
-- actually due, not to table size. paused/cancelled never bloat it.
create index idx_tasks_due on public.tasks (next_run_at) where status = 'active';

create table public.task_runs (
    id               uuid primary key default gen_random_uuid(),
    task_id          uuid not null references public.tasks(id) on delete cascade,
    scheduled_for    timestamptz not null,        -- logical slot this run is for
    available_at     timestamptz not null default now(),  -- not-before for claim (retry backoff)
    status           public.run_status not null default 'pending',
    priority         smallint not null,           -- snapshot from task -> single-table claim
    payload          jsonb not null,              -- snapshot from task -> immutable execution input
    attempt          smallint not null default 0,
    max_attempts     smallint not null,
    worker_id        text,
    lease_token      uuid,                         -- fencing token, rotated on every claim
    lease_expires_at timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    error            text,
    result           jsonb,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    -- one run per (task, slot): makes scheduler materialization idempotent
    unique (task_id, scheduled_for)
);

-- Partial index for the worker claim: only claimable rows, ordered the way claim reads them.
create index idx_runs_claimable on public.task_runs (priority desc, available_at)
    where status = 'pending';
-- Reaper scan: running runs whose lease has expired.
create index idx_runs_lease on public.task_runs (lease_expires_at) where status = 'running';
create index idx_runs_task on public.task_runs (task_id);
