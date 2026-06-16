from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

from core.enums import JobStatus, NodeStatus, RunStatus, ScheduleKind


class ScheduleSpec(BaseModel):
    kind: ScheduleKind
    run_at: datetime | None = None  # first/only fire time; defaults to now() server-side
    interval_seconds: int | None = Field(default=None, gt=0)

    @model_validator(mode="after")
    def _validate(self) -> "ScheduleSpec":
        if self.kind == ScheduleKind.INTERVAL and self.interval_seconds is None:
            raise ValueError("interval schedule requires interval_seconds")
        if self.kind == ScheduleKind.ONE_TIME and self.interval_seconds is not None:
            raise ValueError("one_time schedule must not set interval_seconds")
        return self


# --- Jobs (control plane) ----------------------------------------------------


class JobCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=2_000)
    tenant_id: str = Field(default="default", min_length=1, max_length=200)
    payload: dict[str, Any] = Field(default_factory=dict)
    priority: int = Field(default=5, ge=0, le=9)
    req_cpu: int = Field(default=1, ge=1, le=1_024)       # CPU units demanded
    req_mem_mb: int = Field(default=128, ge=1, le=4_194_304)  # MB demanded
    schedule: ScheduleSpec
    max_attempts: int = Field(default=3, ge=1, le=100)


class JobUpdateRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=2_000)
    payload: dict[str, Any] | None = None
    priority: int | None = Field(default=None, ge=0, le=9)
    req_cpu: int | None = Field(default=None, ge=1, le=1_024)
    req_mem_mb: int | None = Field(default=None, ge=1, le=4_194_304)
    status: JobStatus | None = None  # pause/resume/cancel


class JobResponse(BaseModel):
    id: UUID
    name: str
    description: str | None
    tenant_id: str
    payload: dict[str, Any]
    priority: int
    req_cpu: int
    req_mem_mb: int
    schedule_kind: ScheduleKind
    interval_seconds: int | None
    status: JobStatus
    next_run_at: datetime | None
    max_attempts: int
    created_at: datetime
    updated_at: datetime


class RunResponse(BaseModel):
    id: UUID
    job_id: UUID
    tenant_id: str
    scheduled_for: datetime
    available_at: datetime
    status: RunStatus
    priority: int
    req_cpu: int
    req_mem_mb: int
    payload: dict[str, Any]
    attempt: int
    max_attempts: int
    assigned_node_id: str | None
    lease_expires_at: datetime | None
    started_at: datetime | None
    finished_at: datetime | None
    error: str | None
    result: dict[str, Any] | None


class ClaimedRun(RunResponse):
    # lease_token is returned ONLY to the node that won the claim; it is the fencing
    # token required to heartbeat/complete/fail the run.
    lease_token: UUID


# --- Nodes (data plane / the fleet) ------------------------------------------


class NodeRegisterRequest(BaseModel):
    cpu_total: int = Field(ge=1, le=1_024)
    mem_total_mb: int = Field(ge=1, le=4_194_304)


class NodeResponse(BaseModel):
    id: str
    cpu_total: int
    mem_total_mb: int
    cpu_free: int      # derived: cpu_total - sum(req_cpu over running runs)
    mem_free_mb: int   # derived
    status: NodeStatus
    last_heartbeat_at: datetime
    registered_at: datetime
    updated_at: datetime


class ClaimRequest(BaseModel):
    max_runs: int = Field(default=1, ge=1, le=100)
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


# --- Scheduler admin ---------------------------------------------------------


class TickResponse(BaseModel):
    materialized: int


class ReapResponse(BaseModel):
    requeued: int
    dead: int


class ReapNodesResponse(BaseModel):
    dead_nodes: int
    expired_leases: int
