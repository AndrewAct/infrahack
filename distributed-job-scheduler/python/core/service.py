import random
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from psycopg import sql
from psycopg.types.json import Jsonb

from core.database import Pool, Settings
from core.schemas import (
    ClaimedRun,
    JobCreateRequest,
    JobResponse,
    JobUpdateRequest,
    NodeResponse,
    RunResponse,
)


class NotFoundError(Exception):
    pass


class ConflictError(Exception):
    """Lease not held/expired, or node/run no longer in a usable state."""


def compute_backoff(attempt: int, base: float, cap: float) -> float:
    """Exponential backoff with full-ish jitter (50-100%) to avoid retry herds."""
    delay = min(base * (2 ** (attempt - 1)), cap)
    return delay * (0.5 + random.random() * 0.5)


class JobService:
    """Control plane: CRUD over job definitions (intent + resource demand)."""

    def __init__(self, pool: Pool) -> None:
        self._pool = pool

    def create_job(self, request: JobCreateRequest) -> JobResponse:
        next_run_at = request.schedule.run_at or datetime.now(UTC)
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                insert into jobs
                    (name, description, tenant_id, payload, priority, req_cpu,
                     req_mem_mb, schedule_kind, interval_seconds, next_run_at,
                     max_attempts)
                values
                    (%s, %s, %s, %s, %s, %s, %s, %s::public.schedule_kind, %s, %s, %s)
                returning *
                """,
                (
                    request.name,
                    request.description,
                    request.tenant_id,
                    Jsonb(request.payload),
                    request.priority,
                    request.req_cpu,
                    request.req_mem_mb,
                    request.schedule.kind,
                    request.schedule.interval_seconds,
                    next_run_at,
                    request.max_attempts,
                ),
            ).fetchone()
        return JobResponse.model_validate(row)

    def list_jobs(self, limit: int = 100) -> list[JobResponse]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                "select * from jobs order by created_at desc limit %s", (limit,)
            ).fetchall()
        return [JobResponse.model_validate(row) for row in rows]

    def get_job(self, job_id: UUID) -> JobResponse:
        with self._pool.connection() as conn:
            row = conn.execute(
                "select * from jobs where id = %s", (str(job_id),)
            ).fetchone()
        if row is None:
            raise NotFoundError(f"job {job_id} not found")
        return JobResponse.model_validate(row)

    def update_job(self, job_id: UUID, request: JobUpdateRequest) -> JobResponse:
        fields = request.model_dump(exclude_none=True)
        if not fields:
            return self.get_job(job_id)

        assignments: list[sql.Composable] = []
        params: list[Any] = []
        for column, value in fields.items():
            if column == "payload":
                assignments.append(sql.SQL("payload = %s"))
                params.append(Jsonb(value))
            elif column == "status":
                assignments.append(sql.SQL("status = %s::public.job_status"))
                params.append(value)
            else:
                # column is from a fixed allow-list (model fields); quote it safely
                assignments.append(sql.SQL("{} = %s").format(sql.Identifier(column)))
                params.append(value)
        assignments.append(sql.SQL("updated_at = now()"))
        params.append(str(job_id))

        query = sql.SQL("update jobs set {} where id = %s returning *").format(
            sql.SQL(", ").join(assignments)
        )
        with self._pool.connection() as conn:
            row = conn.execute(query, params).fetchone()
        if row is None:
            raise NotFoundError(f"job {job_id} not found")
        return JobResponse.model_validate(row)

    def delete_job(self, job_id: UUID) -> None:
        with self._pool.connection() as conn:
            row = conn.execute(
                "delete from jobs where id = %s returning id", (str(job_id),)
            ).fetchone()
        if row is None:
            raise NotFoundError(f"job {job_id} not found")

    def list_runs(self, job_id: UUID, limit: int = 50) -> list[RunResponse]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                select * from job_runs
                where job_id = %s
                order by scheduled_for desc
                limit %s
                """,
                (str(job_id), limit),
            ).fetchall()
        return [RunResponse.model_validate(row) for row in rows]


class SchedulerService:
    """Time plane + fault plane. Run only on the elected leader (see scheduler.py):
    materialize due jobs (tick), reschedule lost runs (reap_runs) and dead nodes
    (reap_nodes). Every method is idempotent.
    """

    def __init__(self, pool: Pool, settings: Settings) -> None:
        self._pool = pool
        self._settings = settings

    def tick(self, batch: int = 100) -> int:
        """Scan due jobs, materialize a run per slot (snapshotting resource demand),
        advance next_run_at. Idempotent via unique(job_id, scheduled_for)."""
        materialized = 0
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                select id, schedule_kind, interval_seconds, next_run_at, tenant_id,
                       priority, req_cpu, req_mem_mb, payload, max_attempts
                from jobs
                where status = 'active'
                  and next_run_at is not null
                  and next_run_at <= now()
                order by next_run_at
                for update skip locked
                limit %s
                """,
                (batch,),
            )
            for job in cur.fetchall():
                cur.execute(
                    """
                    insert into job_runs
                        (job_id, tenant_id, scheduled_for, available_at, priority,
                         req_cpu, req_mem_mb, payload, max_attempts)
                    values (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    on conflict (job_id, scheduled_for) do nothing
                    """,
                    (
                        job["id"],
                        job["tenant_id"],
                        job["next_run_at"],
                        job["next_run_at"],
                        job["priority"],
                        job["req_cpu"],
                        job["req_mem_mb"],
                        Jsonb(job["payload"]),
                        job["max_attempts"],
                    ),
                )
                if job["schedule_kind"] == "interval":
                    cur.execute(
                        """
                        update jobs
                        set next_run_at = next_run_at + make_interval(secs => %s),
                            updated_at = now()
                        where id = %s
                        """,
                        (job["interval_seconds"], job["id"]),
                    )
                else:
                    cur.execute(
                        "update jobs set next_run_at = null, updated_at = now() where id = %s",
                        (job["id"],),
                    )
                materialized += 1
        return materialized

    def reap_runs(self, batch: int = 100) -> tuple[int, int]:
        """Find running runs with an expired lease (lost worker); retry with backoff
        or dead-letter. Clearing assigned_node_id returns the node's capacity (derived)."""
        requeued = 0
        dead = 0
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                select id, attempt, max_attempts
                from job_runs
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
                        update job_runs
                        set status = 'pending',
                            attempt = %s,
                            error = 'lease expired (worker/node lost)',
                            available_at = now() + make_interval(secs => %s),
                            assigned_node_id = null, lease_token = null,
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
                        update job_runs
                        set status = 'dead',
                            attempt = %s,
                            error = 'lease expired (worker/node lost)',
                            finished_at = now(),
                            lease_token = null, lease_expires_at = null,
                            updated_at = now()
                        where id = %s
                        """,
                        (next_attempt, run["id"]),
                    )
                    dead += 1
        return requeued, dead

    def reap_nodes(self, batch: int = 100) -> tuple[int, int]:
        """Detect nodes whose heartbeat lapsed, mark them dead, and force-expire the
        leases of every run they held. The run-level reaper (reap_runs) then requeues
        or dead-letters those runs through the SAME bounded-retry path -- one mechanism,
        node failure reduced to lease expiry."""
        dead_nodes = 0
        expired_leases = 0
        timeout = self._settings.node_heartbeat_timeout_seconds
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                select id
                from nodes
                where status = 'active'
                  and last_heartbeat_at < now() - make_interval(secs => %s)
                for update skip locked
                limit %s
                """,
                (timeout, batch),
            )
            for node in cur.fetchall():
                cur.execute(
                    "update nodes set status = 'dead', updated_at = now() where id = %s",
                    (node["id"],),
                )
                cur.execute(
                    """
                    update job_runs
                    set lease_expires_at = now() - make_interval(secs => 1),
                        updated_at = now()
                    where assigned_node_id = %s and status = 'running'
                    """,
                    (node["id"],),
                )
                expired_leases += cur.rowcount
                dead_nodes += 1
        return dead_nodes, expired_leases


class NodeService:
    """Data plane: nodes register + heartbeat, claim runs that FIT their free
    capacity, and report outcomes (fencing-token protected)."""

    def __init__(self, pool: Pool, settings: Settings) -> None:
        self._pool = pool
        self._settings = settings

    # --- node lifecycle ------------------------------------------------------

    def register(
        self, node_id: str, cpu_total: int, mem_total_mb: int
    ) -> NodeResponse:
        """Upsert a node and (re)activate it. Re-registering a previously dead node
        revives it; capacity is whatever the agent reports now."""
        with self._pool.connection() as conn:
            conn.execute(
                """
                insert into nodes (id, cpu_total, mem_total_mb, status, last_heartbeat_at)
                values (%s, %s, %s, 'active', now())
                on conflict (id) do update
                set cpu_total = excluded.cpu_total,
                    mem_total_mb = excluded.mem_total_mb,
                    status = 'active',
                    last_heartbeat_at = now(),
                    updated_at = now()
                """,
                (node_id, cpu_total, mem_total_mb),
            )
        return self.get_node(node_id)

    def node_heartbeat(self, node_id: str) -> NodeResponse:
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                update nodes
                set last_heartbeat_at = now(), status = 'active', updated_at = now()
                where id = %s
                returning id
                """,
                (node_id,),
            ).fetchone()
        if row is None:
            raise NotFoundError(f"node {node_id} not registered")
        return self.get_node(node_id)

    def get_node(self, node_id: str) -> NodeResponse:
        with self._pool.connection() as conn:
            row = conn.execute(self._NODE_SELECT + " where n.id = %s", (node_id,)).fetchone()
        if row is None:
            raise NotFoundError(f"node {node_id} not found")
        return NodeResponse.model_validate(row)

    def list_nodes(self) -> list[NodeResponse]:
        with self._pool.connection() as conn:
            rows = conn.execute(self._NODE_SELECT + " order by n.id").fetchall()
        return [NodeResponse.model_validate(row) for row in rows]

    # Derive free capacity = total - sum(req over this node's running runs).
    _NODE_SELECT = """
        select n.id, n.cpu_total, n.mem_total_mb,
               n.cpu_total    - coalesce(r.cpu, 0) as cpu_free,
               n.mem_total_mb - coalesce(r.mem, 0) as mem_free_mb,
               n.status, n.last_heartbeat_at, n.registered_at, n.updated_at
        from nodes n
        left join (
            select assigned_node_id,
                   sum(req_cpu)    as cpu,
                   sum(req_mem_mb) as mem
            from job_runs
            where status = 'running'
            group by assigned_node_id
        ) r on r.assigned_node_id = n.id
    """

    # --- placement (the resource-aware claim) --------------------------------

    def claim(
        self, node_id: str, max_runs: int, lease_seconds: int
    ) -> list[ClaimedRun]:
        """Resource-aware claim. Compute this node's free capacity (derived), then
        greedily claim the highest-priority pending runs that FIT, decrementing local
        free as we go. FOR UPDATE SKIP LOCKED keeps concurrent nodes disjoint and
        non-blocking; the node row lock serializes this node's own capacity view."""
        claimed: list[ClaimedRun] = []
        with self._pool.connection() as conn, conn.cursor() as cur:
            node = cur.execute(
                "select cpu_total, mem_total_mb, status from nodes where id = %s for update",
                (node_id,),
            ).fetchone()
            if node is None or node["status"] != "active":
                return []

            used = cur.execute(
                """
                select coalesce(sum(req_cpu), 0) as cpu,
                       coalesce(sum(req_mem_mb), 0) as mem
                from job_runs
                where assigned_node_id = %s and status = 'running'
                """,
                (node_id,),
            ).fetchone()
            free_cpu = node["cpu_total"] - used["cpu"]
            free_mem = node["mem_total_mb"] - used["mem"]

            while len(claimed) < max_runs:
                run = cur.execute(
                    """
                    select id, req_cpu, req_mem_mb
                    from job_runs
                    where status = 'pending' and available_at <= now()
                      and req_cpu <= %s and req_mem_mb <= %s
                    order by priority desc, available_at asc
                    for update skip locked
                    limit 1
                    """,
                    (free_cpu, free_mem),
                ).fetchone()
                if run is None:
                    break
                updated = cur.execute(
                    """
                    update job_runs
                    set status = 'running',
                        assigned_node_id = %s,
                        lease_token = gen_random_uuid(),
                        lease_expires_at = now() + make_interval(secs => %s),
                        started_at = coalesce(started_at, now()),
                        updated_at = now()
                    where id = %s
                    returning *
                    """,
                    (node_id, lease_seconds, run["id"]),
                ).fetchone()
                claimed.append(ClaimedRun.model_validate(updated))
                free_cpu -= run["req_cpu"]
                free_mem -= run["req_mem_mb"]
        return claimed

    # --- run outcomes (fencing-token protected) ------------------------------

    def heartbeat_run(
        self, run_id: UUID, lease_token: UUID, lease_seconds: int
    ) -> RunResponse:
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                update job_runs
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
        # status leaves 'running' -> the node's capacity returns automatically.
        with self._pool.connection() as conn:
            row = conn.execute(
                """
                update job_runs
                set status = 'succeeded',
                    result = %s,
                    finished_at = now(),
                    lease_token = null, lease_expires_at = null,
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
                select attempt, max_attempts from job_runs
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
                    update job_runs
                    set status = 'pending',
                        attempt = %s,
                        error = %s,
                        available_at = now() + make_interval(secs => %s),
                        assigned_node_id = null, lease_token = null,
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
                    update job_runs
                    set status = 'dead',
                        attempt = %s,
                        error = %s,
                        finished_at = now(),
                        lease_token = null, lease_expires_at = null,
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
                "select * from job_runs where id = %s", (str(run_id),)
            ).fetchone()
        if row is None:
            raise NotFoundError(f"run {run_id} not found")
        return RunResponse.model_validate(row)
