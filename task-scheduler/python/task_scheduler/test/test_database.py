"""End-to-end integration tests against a real Postgres (schema from sql/001_reset_schema.sql).

Skipped unless DATABASE_URL is set. Run:
    uv run python sql/001_reset_schema.sql in your DB first, then:
    DATABASE_URL=postgresql://... uv run pytest test/test_database.py
"""

import unittest
from datetime import UTC, datetime, timedelta
from uuid import UUID

from core.database import close_pool, get_settings, init_pool
from core.enums import ScheduleKind
from core.schemas import ClaimedRun, ScheduleSpec, TaskCreateRequest, TaskResponse
from core.service import SchedulerService, TaskService, WorkerService

# Detect DB config the same way the app does (reads .env via pydantic Settings),
# not from os.getenv, so a DATABASE_URL in .env is picked up here too.
DATABASE_URL = get_settings().database_url


@unittest.skipUnless(DATABASE_URL, "DATABASE_URL not set")
class SchedulerIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pool = init_pool()
        cls.settings = get_settings()

    @classmethod
    def tearDownClass(cls) -> None:
        close_pool()

    def _new_due_one_time_task(self, name: str) -> TaskResponse:
        service = TaskService(self.pool)
        task = service.create_task(
            TaskCreateRequest(
                name=name,
                payload={"marker": name},
                priority=7,
                schedule=ScheduleSpec(
                    kind=ScheduleKind.ONE_TIME,
                    run_at=datetime.now(UTC) - timedelta(seconds=1),
                ),
                max_attempts=2,
            )
        )
        self.addCleanup(service.delete_task, task.id)
        return task

    def test_materialize_claim_complete(self) -> None:
        task = self._new_due_one_time_task("it-happy")
        scheduler = SchedulerService(self.pool, self.settings)
        worker = WorkerService(self.pool, self.settings)

        self.assertGreaterEqual(scheduler.tick(), 1)

        claimed = worker.claim("worker-it", max_tasks=10, lease_seconds=30)
        mine = [r for r in claimed if r.task_id == task.id]
        self.assertEqual(len(mine), 1)
        run = mine[0]

        done = worker.complete(run.id, run.lease_token, {"ok": True})
        self.assertEqual(done.status, "succeeded")
        self.assertEqual(done.result, {"ok": True})

        # one_time task is exhausted: next_run_at cleared, no second run
        self.assertIsNone(TaskService(self.pool).get_task(task.id).next_run_at)

    def test_fail_then_retry_backoff(self) -> None:
        task = self._new_due_one_time_task("it-retry")
        scheduler = SchedulerService(self.pool, self.settings)
        worker = WorkerService(self.pool, self.settings)
        scheduler.tick()

        run = next(
            r
            for r in worker.claim("worker-it", max_tasks=10, lease_seconds=30)
            if r.task_id == task.id
        )
        failed = worker.fail(run.id, run.lease_token, "boom", retryable=True)

        # max_attempts=2 -> first failure requeues for a retry, not dead
        self.assertEqual(failed.status, "pending")
        self.assertEqual(failed.attempt, 1)
        self.assertGreater(failed.available_at, datetime.now(UTC))  # backoff applied

    def test_concurrent_claim_never_double_claims(self) -> None:
        """The core invariant of FOR UPDATE SKIP LOCKED: N workers hammering the
        pending set in parallel get DISJOINT batches -- no run is ever claimed by
        two workers, and nothing is lost. This is the test that turns "I reasoned
        SKIP LOCKED is safe" into "a regression here fails the build".
        """
        import threading
        from concurrent.futures import ThreadPoolExecutor

        n_tasks = 40
        n_workers = 8

        task_svc = TaskService(self.pool)
        my_task_ids: set[UUID] = set()
        for i in range(n_tasks):
            task = task_svc.create_task(
                TaskCreateRequest(
                    name=f"it-conc-{i}",
                    payload={"i": i},
                    priority=5,
                    schedule=ScheduleSpec(
                        kind=ScheduleKind.ONE_TIME,
                        run_at=datetime.now(UTC) - timedelta(seconds=1),
                    ),
                    max_attempts=1,
                )
            )
            my_task_ids.add(task.id)
            self.addCleanup(task_svc.delete_task, task.id)

        materialized = SchedulerService(self.pool, self.settings).tick(
            batch=n_tasks * 2
        )
        self.assertGreaterEqual(materialized, n_tasks)

        # Release all threads into the claim loop at the same instant -> max contention.
        gate = threading.Barrier(n_workers)

        def drain(idx: int) -> list[ClaimedRun]:
            worker = WorkerService(self.pool, self.settings)
            mine: list[ClaimedRun] = []
            gate.wait(timeout=10)
            while True:
                batch = worker.claim(f"w{idx}", max_tasks=5, lease_seconds=30)
                if not batch:
                    break
                mine.extend(batch)
            return mine

        with ThreadPoolExecutor(max_workers=n_workers) as ex:
            per_worker = list(ex.map(drain, range(n_workers)))

        claimed = [run for batch in per_worker for run in batch]
        claimed_ids = [run.id for run in claimed]

        # INVARIANT 1 (SKIP LOCKED): no run was claimed by more than one worker.
        self.assertEqual(
            len(claimed_ids),
            len(set(claimed_ids)),
            "a run was claimed by two workers -- SKIP LOCKED guarantee broken",
        )

        # INVARIANT 2 (no loss): every run we materialized was claimed exactly once.
        mine = [run for run in claimed if run.task_id in my_task_ids]
        self.assertEqual(len(mine), n_tasks)
        self.assertEqual(len({run.id for run in mine}), n_tasks)

        # INVARIANT 3 (fencing): each claim minted a distinct lease_token.
        self.assertEqual(len({run.lease_token for run in mine}), n_tasks)

    def test_stale_lease_token_is_rejected(self) -> None:
        from uuid import uuid4

        from core.service import ConflictError

        task = self._new_due_one_time_task("it-fence")
        scheduler = SchedulerService(self.pool, self.settings)
        worker = WorkerService(self.pool, self.settings)
        scheduler.tick()
        run = next(
            r
            for r in worker.claim("worker-it", max_tasks=10, lease_seconds=30)
            if r.task_id == task.id
        )
        with self.assertRaises(ConflictError):
            worker.complete(run.id, uuid4(), None)  # wrong fencing token


if __name__ == "__main__":
    unittest.main()
