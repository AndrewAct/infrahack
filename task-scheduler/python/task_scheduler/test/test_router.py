import unittest
from datetime import UTC, datetime
from typing import Any
from uuid import UUID, uuid4

from fastapi.testclient import TestClient

from core.enums import RunStatus, ScheduleKind, TaskStatus
from core.router import get_task_service, get_worker_service
from core.schemas import (
    ClaimedRun,
    RunResponse,
    TaskCreateRequest,
    TaskResponse,
)
from core.service import ConflictError, NotFoundError
from main import app


def _run_fields(run_id: UUID, **overrides: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": run_id,
        "task_id": uuid4(),
        "scheduled_for": datetime.now(UTC),
        "available_at": datetime.now(UTC),
        "status": RunStatus.RUNNING,
        "priority": 5,
        "payload": {"k": "v"},
        "attempt": 0,
        "max_attempts": 3,
        "worker_id": "worker-1",
        "lease_expires_at": datetime.now(UTC),
        "started_at": datetime.now(UTC),
        "finished_at": None,
        "error": None,
        "result": None,
    }
    base.update(overrides)
    return base


class FakeTaskService:
    def __init__(self) -> None:
        self.tasks: dict[UUID, TaskResponse] = {}

    def create_task(self, request: TaskCreateRequest) -> TaskResponse:
        now = datetime.now(UTC)
        task = TaskResponse(
            id=uuid4(),
            name=request.name,
            description=request.description,
            payload=request.payload,
            priority=request.priority,
            schedule_kind=request.schedule.kind,
            interval_seconds=request.schedule.interval_seconds,
            status=TaskStatus.ACTIVE,
            next_run_at=request.schedule.run_at or now,
            max_attempts=request.max_attempts,
            created_at=now,
            updated_at=now,
        )
        self.tasks[task.id] = task
        return task

    def list_tasks(self, limit: int = 100) -> list[TaskResponse]:
        return list(self.tasks.values())

    def get_task(self, task_id: UUID) -> TaskResponse:
        task = self.tasks.get(task_id)
        if task is None:
            raise NotFoundError(f"task {task_id} not found")
        return task


class FakeWorkerService:
    def __init__(self) -> None:
        self.leases: dict[UUID, UUID] = {}  # run_id -> lease_token

    def claim(
        self, worker_id: str, max_tasks: int, lease_seconds: int
    ) -> list[ClaimedRun]:
        run_id, token = uuid4(), uuid4()
        self.leases[run_id] = token
        return [
            ClaimedRun(lease_token=token, **_run_fields(run_id, worker_id=worker_id))
        ]

    def complete(
        self, run_id: UUID, lease_token: UUID, result: dict[str, Any] | None
    ) -> RunResponse:
        if self.leases.get(run_id) != lease_token:
            raise ConflictError("lease not held or expired")
        return RunResponse(
            **_run_fields(
                run_id,
                status=RunStatus.SUCCEEDED,
                finished_at=datetime.now(UTC),
                result=result,
            )
        )

    def get_run(self, run_id: UUID) -> RunResponse:
        return RunResponse(**_run_fields(run_id))


class RouterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.task_service = FakeTaskService()
        self.worker_service = FakeWorkerService()
        app.dependency_overrides[get_task_service] = lambda: self.task_service
        app.dependency_overrides[get_worker_service] = lambda: self.worker_service
        self.client = TestClient(app)  # no `with`: lifespan/DB pool not started

    def tearDown(self) -> None:
        app.dependency_overrides.clear()

    def test_create_and_list_task(self) -> None:
        response = self.client.post(
            "/tasks",
            json={
                "name": "generate-report",
                "payload": {"customer_id": "c-123"},
                "priority": 8,
                "schedule": {"kind": "interval", "interval_seconds": 60},
            },
        )
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body["priority"], 8)
        self.assertEqual(body["schedule_kind"], ScheduleKind.INTERVAL)
        self.assertEqual(body["status"], TaskStatus.ACTIVE)

        listed = self.client.get("/tasks")
        self.assertEqual(listed.status_code, 200)
        self.assertEqual(len(listed.json()), 1)

    def test_one_time_schedule_validation(self) -> None:
        response = self.client.post(
            "/tasks",
            json={
                "name": "bad",
                "schedule": {"kind": "one_time", "interval_seconds": 60},
            },
        )
        self.assertEqual(response.status_code, 422)

    def test_claim_returns_lease_token_then_complete(self) -> None:
        claim = self.client.post(
            "/workers/worker-1/claims",
            json={"max_tasks": 1, "lease_seconds": 30},
        )
        self.assertEqual(claim.status_code, 200)
        run = claim.json()[0]
        self.assertIn("lease_token", run)  # token exposed to the claiming worker
        run_id, token = run["id"], run["lease_token"]

        complete = self.client.post(
            f"/runs/{run_id}/complete",
            json={"lease_token": token, "result": {"ok": True}},
        )
        self.assertEqual(complete.status_code, 200)
        self.assertEqual(complete.json()["status"], RunStatus.SUCCEEDED)
        # plain run reads never leak the lease token
        self.assertNotIn("lease_token", complete.json())

    def test_complete_with_wrong_token_conflicts(self) -> None:
        claim = self.client.post(
            "/workers/worker-1/claims", json={"max_tasks": 1, "lease_seconds": 30}
        )
        run_id = claim.json()[0]["id"]
        complete = self.client.post(
            f"/runs/{run_id}/complete",
            json={"lease_token": str(uuid4()), "result": None},
        )
        self.assertEqual(complete.status_code, 409)


if __name__ == "__main__":
    unittest.main()
