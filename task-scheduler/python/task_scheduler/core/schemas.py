from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

from core.enums import RunStatus, ScheduleKind, TaskStatus


class ScheduleSpec(BaseModel):
    kind: ScheduleKind
    run_at: datetime | None = (
        None  # first/only fire time; defaults to now() server-side
    )
    interval_seconds: int | None = Field(default=None, gt=0)

    @model_validator(mode="after")
    def _validate(self) -> "ScheduleSpec":
        if self.kind == ScheduleKind.INTERVAL and self.interval_seconds is None:
            raise ValueError("interval schedule requires interval_seconds")
        if self.kind == ScheduleKind.ONE_TIME and self.interval_seconds is not None:
            raise ValueError("one_time schedule must not set interval_seconds")
        return self


class TaskCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=2_000)
    payload: dict[str, Any] = Field(default_factory=dict)
    priority: int = Field(default=5, ge=0, le=9)
    schedule: ScheduleSpec
    max_attempts: int = Field(default=3, ge=1, le=100)


class TaskUpdateRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=2_000)
    payload: dict[str, Any] | None = None
    priority: int | None = Field(default=None, ge=0, le=9)
    status: TaskStatus | None = None  # pause/resume/cancel


class TaskResponse(BaseModel):
    id: UUID
    name: str
    description: str | None
    payload: dict[str, Any]
    priority: int
    schedule_kind: ScheduleKind
    interval_seconds: int | None
    status: TaskStatus
    next_run_at: datetime | None
    max_attempts: int
    created_at: datetime
    updated_at: datetime


class RunResponse(BaseModel):
    id: UUID
    task_id: UUID
    scheduled_for: datetime
    available_at: datetime
    status: RunStatus
    priority: int
    payload: dict[str, Any]
    attempt: int
    max_attempts: int
    worker_id: str | None
    lease_expires_at: datetime | None
    started_at: datetime | None
    finished_at: datetime | None
    error: str | None
    result: dict[str, Any] | None


class ClaimedRun(RunResponse):
    # lease_token is returned ONLY to the worker that won the claim; it is the
    # fencing token required to heartbeat/complete/fail the run.
    lease_token: UUID


class ClaimRequest(BaseModel):
    max_tasks: int = Field(default=1, ge=1, le=100)
    lease_seconds: int = Field(default=30, ge=1, le=3_600)


class HeartbeatRequest(BaseModel):
    lease_token: UUID
    lease_seconds: int = Field(default=30, ge=1, le=3_600)


class CompleteRequest(BaseModel):
    lease_token: UUID
    result: dict[str, Any] | None = None


class FailRequest(BaseModel):
    lease_token: UUID
    error: str = Field(max_length=4_000)
    retryable: bool = True


class TickResponse(BaseModel):
    materialized: int


class ReapResponse(BaseModel):
    requeued: int
    dead: int
