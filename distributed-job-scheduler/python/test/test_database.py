"""Integration tests against a real Postgres. Skipped unless DATABASE_URL is set.

Apply sql/001_reset_schema.sql first. Each test uses unique ids/names and cleans up
after itself so it can run against a shared database.
"""

import unittest
import uuid

from core.database import get_settings, init_pool
from core.schemas import JobCreateRequest, ScheduleSpec
from core.service import JobService, NodeService, SchedulerService

_SKIP = get_settings().database_url is None


def _one_time(name: str, *, req_cpu: int = 1, priority: int = 5) -> JobCreateRequest:
    return JobCreateRequest(
        name=name,
        req_cpu=req_cpu,
        req_mem_mb=req_cpu * 128,
        priority=priority,
        payload={"type": "echo", "message": name},
        schedule=ScheduleSpec(kind="one_time"),
    )


@unittest.skipIf(_SKIP, "DATABASE_URL not set")
class PlacementIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pool = init_pool()
        cls.settings = get_settings()
        cls.jobs = JobService(cls.pool)
        cls.sched = SchedulerService(cls.pool, cls.settings)
        cls.nodes = NodeService(cls.pool, cls.settings)

    def setUp(self) -> None:
        self.tag = f"it-{uuid.uuid4().hex[:8]}"
        self.node_ids: list[str] = []
        self.job_ids: list[uuid.UUID] = []

    def tearDown(self) -> None:
        with self.pool.connection() as conn:
            for jid in self.job_ids:
                conn.execute("delete from jobs where id = %s", (str(jid),))
            for nid in self.node_ids:
                conn.execute("delete from job_runs where assigned_node_id = %s", (nid,))
                conn.execute("delete from nodes where id = %s", (nid,))

    def _make_node(self, suffix: str, cpu: int, mem: int = 65536) -> str:
        nid = f"{self.tag}-{suffix}"
        self.nodes.register(nid, cpu, mem)
        self.node_ids.append(nid)
        return nid

    def _make_jobs(self, n: int, *, req_cpu: int = 1) -> None:
        for i in range(n):
            job = self.jobs.create_job(_one_time(f"{self.tag}-{i}", req_cpu=req_cpu))
            self.job_ids.append(job.id)
        self.sched.tick()  # materialize into pending runs

    def test_claim_respects_node_capacity(self) -> None:
        node = self._make_node("cap", cpu=2)
        self._make_jobs(3, req_cpu=1)  # 3 unit jobs, but node only has 2 CPU
        claimed = self.nodes.claim(node, max_runs=5, lease_seconds=30)
        self.assertEqual(len(claimed), 2, "must not over-commit a node beyond capacity")
        free = self.nodes.get_node(node)
        self.assertEqual(free.cpu_free, 0)

    def test_no_double_claim_across_nodes(self) -> None:
        a = self._make_node("a", cpu=10)
        b = self._make_node("b", cpu=10)
        self._make_jobs(6, req_cpu=1)
        claimed_a = self.nodes.claim(a, max_runs=10, lease_seconds=30)
        claimed_b = self.nodes.claim(b, max_runs=10, lease_seconds=30)
        ids_a = {r.id for r in claimed_a}
        ids_b = {r.id for r in claimed_b}
        # The core invariant: no run is handed to two nodes (robust to any stragglers).
        self.assertEqual(ids_a & ids_b, set(), "a run must not be claimed by two nodes")
        # With 20 free slots our 6 runs are all claimable by someone.
        self.assertGreaterEqual(len(ids_a | ids_b), 6)

    def test_capacity_returns_after_complete(self) -> None:
        node = self._make_node("ret", cpu=1)
        self._make_jobs(2, req_cpu=1)
        first = self.nodes.claim(node, max_runs=1, lease_seconds=30)
        self.assertEqual(len(first), 1)
        self.assertEqual(self.nodes.get_node(node).cpu_free, 0)
        run = first[0]
        self.nodes.complete(run.id, run.lease_token, {"ok": True})
        # capacity is derived from running runs, so completing frees it immediately
        self.assertEqual(self.nodes.get_node(node).cpu_free, 1)
        second = self.nodes.claim(node, max_runs=1, lease_seconds=30)
        self.assertEqual(len(second), 1)

    def test_node_death_reschedules_runs(self) -> None:
        node = self._make_node("dead", cpu=4)
        self._make_jobs(1, req_cpu=1)
        claimed = self.nodes.claim(node, max_runs=1, lease_seconds=30)
        self.assertEqual(len(claimed), 1)
        run_id = claimed[0].id

        # Simulate a missed heartbeat by backdating it past the timeout.
        with self.pool.connection() as conn:
            conn.execute(
                "update nodes set last_heartbeat_at = now() - make_interval(secs => %s) "
                "where id = %s",
                (self.settings.node_heartbeat_timeout_seconds + 60, node),
            )

        dead_nodes, expired = self.sched.reap_nodes()
        self.assertGreaterEqual(dead_nodes, 1)
        self.assertGreaterEqual(expired, 1)
        self.sched.reap_runs()  # force-expired lease -> requeued

        run = self.nodes.get_run(run_id)
        self.assertEqual(run.status, "pending")
        self.assertIsNone(run.assigned_node_id)

    def test_fencing_token_rejects_stale_worker(self) -> None:
        node = self._make_node("fence", cpu=4)
        self._make_jobs(1, req_cpu=1)
        run = self.nodes.claim(node, max_runs=1, lease_seconds=30)[0]
        # Complete with a wrong token -> rejected.
        from core.service import ConflictError

        with self.assertRaises(ConflictError):
            self.nodes.complete(run.id, uuid.uuid4(), {"forged": True})


if __name__ == "__main__":
    unittest.main()
