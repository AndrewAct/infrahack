import random
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from psycopg import sql
from psycopg.types.json import Jsonb

from core.database import Pool, Settings
from core.schemas import (
    ClaimedRun,
    RunResponse,
    TaskCreateRequest,
    TaskResponse,
    TaskUpdateRequest,
)


class NotFoundError(Exception):
    pass


class ConflictError(Exception):
    """Lease not held, expired, or run no longer in a claimable/running state."""


def compute_backoff(attempt: int, base: float, cap: float) -> float:
    """Exponential backoff with full-ish jitter (50-100%) to avoid retry herds."""
    delay = min(base * (2 ** (attempt - 1)), cap)
    return delay * (0.5 + random.random() * 0.5)


class TaskService:
    """Control plane: CRUD over task definitions."""

    def __init__(self, pool: Pool) -> None:
        self._pool = pool

    def create_task(self, request: TaskCreateRequest) -> TaskResponse:
        next_run_at = request.schedule.run_at or datetime.now(UTC)
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                insert into tasks
                    (name, description, payload, priority, schedule_kind,
                     interval_seconds, next_run_at, max_attempts)
                values
                    (%s, %s, %s, %s, %s::public.schedule_kind, %s, %s, %s)
                returning *
                """,
                (
                    request.name,
                    request.description,
                    Jsonb(request.payload),
                    request.priority,
                    request.schedule.kind,
                    request.schedule.interval_seconds,
                    next_run_at,
                    request.max_attempts,
                ),
            ).fetchone()
        return TaskResponse.model_validate(row)

    def list_tasks(self, limit: int = 100) -> list[TaskResponse]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                "select * from tasks order by created_at desc limit %s",
                (limit,),
            ).fetchall()
        return [TaskResponse.model_validate(row) for row in rows]

    def get_task(self, task_id: UUID) -> TaskResponse:
        with self._pool.connection() as conn:
            row = conn.execute(
                "select * from tasks where id = %s", (str(task_id),)
            ).fetchone()
        if row is None:
            raise NotFoundError(f"task {task_id} not found")
        return TaskResponse.model_validate(row)

    def update_task(self, task_id: UUID, request: TaskUpdateRequest) -> TaskResponse:
        fields = request.model_dump(exclude_none=True)
        if not fields:
            return self.get_task(task_id)

        assignments: list[sql.Composable] = []
        params: list[Any] = []
        for column, value in fields.items():
            if column == "payload":
                assignments.append(sql.SQL("payload = %s"))
                params.append(Jsonb(value))
            elif column == "status":
                assignments.append(sql.SQL("status = %s::public.task_status"))
                params.append(value)
            else:
                # column is from a fixed allow-list (model fields); quote it safely
                assignments.append(sql.SQL("{} = %s").format(sql.Identifier(column)))
                params.append(value)
        assignments.append(sql.SQL("updated_at = now()"))
        params.append(str(task_id))

        query = sql.SQL("update tasks set {} where id = %s returning *").format(
            sql.SQL(", ").join(assignments)
        )
        with self._pool.connection() as conn:
            row = conn.execute(query, params).fetchone()
        if row is None:
            raise NotFoundError(f"task {task_id} not found")
        return TaskResponse.model_validate(row)

    def delete_task(self, task_id: UUID) -> None:
        with self._pool.connection() as conn:
            row = conn.execute(
                "delete from tasks where id = %s returning id", (str(task_id),)
            ).fetchone()
        if row is None:
            raise NotFoundError(f"task {task_id} not found")

    def list_runs(self, task_id: UUID, limit: int = 50) -> list[RunResponse]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                select * from task_runs
                where task_id = %s
                order by scheduled_for desc
                limit %s
                """,
                (str(task_id), limit),
            ).fetchall()
        return [RunResponse.model_validate(row) for row in rows]


class SchedulerService:
    """Materializes due tasks into runs (tick) and reclaims lost runs (reap)."""

    def __init__(self, pool: Pool, settings: Settings) -> None:
        self._pool = pool
        self._settings = settings

    def tick(self, batch: int = 100) -> int:
        """Scan due tasks, materialize a run per slot, advance next_run_at. Idempotent."""
        materialized = 0
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                select id, schedule_kind, interval_seconds, next_run_at,
                       priority, payload, max_attempts
                from tasks
                where status = 'active'
                  and next_run_at is not null
                  and next_run_at <= now()
                order by next_run_at
                for update skip locked
                limit %s
                """,
                (batch,),
            )
            for task in cur.fetchall():
                cur.execute(
                    """
                    insert into task_runs
                        (task_id, scheduled_for, available_at, priority,
                         payload, max_attempts)
                    values (%s, %s, %s, %s, %s, %s)
                    on conflict (task_id, scheduled_for) do nothing
                    """,
                    (
                        task["id"],
                        task["next_run_at"],
                        task["next_run_at"],
                        task["priority"],
                        Jsonb(task["payload"]),
                        task["max_attempts"],
                    ),
                )
                if task["schedule_kind"] == "interval":
                    # Advance to the next slot. If still in the past (scheduler was
                    # behind) the next tick materializes that slot too (catch-up).
                    cur.execute(
                        """
                        update tasks
                        set next_run_at = next_run_at + make_interval(secs => %s),
                            updated_at = now()
                        where id = %s
                        """,
                        (task["interval_seconds"], task["id"]),
                    )
                else:
                    cur.execute(
                        "update tasks set next_run_at = null, updated_at = now() where id = %s",
                        (task["id"],),
                    )
                materialized += 1
        return materialized

    def reap(self, batch: int = 100) -> tuple[int, int]:
        """Find running runs with an expired lease (lost workers); retry or dead-letter."""
        requeued = 0
        dead = 0
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                select id, attempt, max_attempts
                from task_runs
                where status = 'running' and lease_expires_at < now()
                for update skip locked
                limit %s
                """,
                (batch,),
            )
            for run in cur.fetchall():
                next_attempt = run["attempt"] + 1
                if next_attempt < run["max_attempts"]:
                    delay = compute_backoff(
                        next_attempt,
                        self._settings.retry_base_seconds,
                        self._settings.retry_cap_seconds,
                    )
                    cur.execute(
                        """
                        update task_runs
                        set status = 'pending',
                            attempt = %s,
                            error = 'lease expired (worker lost)',
                            available_at = now() + make_interval(secs => %s),
                            worker_id = null, lease_token = null,
                            lease_expires_at = null, started_at = null,
                            updated_at = now()
                        where id = %s
                        """,
                        (next_attempt, delay, run["id"]),
                    )
                    requeued += 1
                else:
                    cur.execute(
                        """
                        update task_runs
                        set status = 'dead',
                            attempt = %s,
                            error = 'lease expired (worker lost)',
                            finished_at = now(),
                            worker_id = null, lease_token = null,
                            lease_expires_at = null,
                            updated_at = now()
                        where id = %s
                        """,
                        (next_attempt, run["id"]),
                    )
                    dead += 1
        return requeued, dead


class WorkerService:
    """Data plane: workers claim runs, heartbeat their lease, and report outcomes."""

    def __init__(self, pool: Pool, settings: Settings) -> None:
        self._pool = pool
        self._settings = settings

    def claim(
        self, worker_id: str, max_tasks: int, lease_seconds: int
    ) -> list[ClaimedRun]:
        """Atomically claim up to max_tasks ready runs. FOR UPDATE SKIP LOCKED means
        concurrent workers get disjoint batches with no double-claim and no blocking."""
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                update task_runs
                set status = 'running',
                    worker_id = %s,
                    lease_token = gen_random_uuid(),
                    lease_expires_at = now() + make_interval(secs => %s),
                    started_at = coalesce(started_at, now()),
                    updated_at = now()
                where id in (
                    select id from task_runs
                    where status = 'pending' and available_at <= now()
                    order by priority desc, available_at asc
                    for update skip locked
                    limit %s
                )
                returning *
                """,
                (worker_id, lease_seconds, max_tasks),
            ).fetchall()
        return [ClaimedRun.model_validate(row) for row in rows]

    def heartbeat(
        self, run_id: UUID, lease_token: UUID, lease_seconds: int
    ) -> RunResponse:
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                update task_runs
                set lease_expires_at = now() + make_interval(secs => %s),
                    updated_at = now()
                where id = %s and status = 'running'
                  and lease_token = %s and lease_expires_at > now()
                returning *
                """,
                (lease_seconds, str(run_id), str(lease_token)),
            ).fetchone()
        if row is None:
            raise ConflictError("lease not held or expired")
        return RunResponse.model_validate(row)

    def complete(
        self, run_id: UUID, lease_token: UUID, result: dict[str, Any] | None
    ) -> RunResponse:
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                update task_runs
                set status = 'succeeded',
                    result = %s,
                    finished_at = now(),
                    updated_at = now()
                where id = %s and status = 'running'
                  and lease_token = %s and lease_expires_at > now()
                returning *
                """,
                (
                    Jsonb(result) if result is not None else None,
                    str(run_id),
                    str(lease_token),
                ),
            ).fetchone()
        if row is None:
            raise ConflictError("lease not held or expired")
        return RunResponse.model_validate(row)

    def fail(
        self, run_id: UUID, lease_token: UUID, error: str, retryable: bool
    ) -> RunResponse:
        with self._pool.connection() as conn, conn.cursor() as cur:
            run = cur.execute(
                """
                select attempt, max_attempts from task_runs
                where id = %s and status = 'running'
                  and lease_token = %s and lease_expires_at > now()
                for update
                """,
                (str(run_id), str(lease_token)),
            ).fetchone()
            if run is None:
                raise ConflictError("lease not held or expired")

            next_attempt = run["attempt"] + 1
            will_retry = retryable and next_attempt < run["max_attempts"]
            if will_retry:
                delay = compute_backoff(
                    next_attempt,
                    self._settings.retry_base_seconds,
                    self._settings.retry_cap_seconds,
                )
                row = cur.execute(
                    """
                    update task_runs
                    set status = 'pending',
                        attempt = %s,
                        error = %s,
                        available_at = now() + make_interval(secs => %s),
                        worker_id = null, lease_token = null,
                        lease_expires_at = null, started_at = null,
                        updated_at = now()
                    where id = %s
                    returning *
                    """,
                    (next_attempt, error, delay, str(run_id)),
                ).fetchone()
            else:
                row = cur.execute(
                    """
                    update task_runs
                    set status = 'dead',
                        attempt = %s,
                        error = %s,
                        finished_at = now(),
                        worker_id = null, lease_token = null,
                        lease_expires_at = null,
                        updated_at = now()
                    where id = %s
                    returning *
                    """,
                    (next_attempt, error, str(run_id)),
                ).fetchone()
        return RunResponse.model_validate(row)

    def get_run(self, run_id: UUID) -> RunResponse:
        with self._pool.connection() as conn:
            row = conn.execute(
                "select * from task_runs where id = %s", (str(run_id),)
            ).fetchone()
        if row is None:
            raise NotFoundError(f"run {run_id} not found")
        return RunResponse.model_validate(row)
