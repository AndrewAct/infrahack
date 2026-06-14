from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response, status

from core.database import Pool, get_pool, get_settings
from core.schemas import (
    ClaimedRun,
    ClaimRequest,
    CompleteRequest,
    FailRequest,
    HeartbeatRequest,
    ReapResponse,
    RunResponse,
    TaskCreateRequest,
    TaskResponse,
    TaskUpdateRequest,
    TickResponse,
)
from core.service import (
    ConflictError,
    NotFoundError,
    SchedulerService,
    TaskService,
    WorkerService,
)

task_router = APIRouter(prefix="/tasks", tags=["tasks"])
worker_router = APIRouter(tags=["workers"])
admin_router = APIRouter(prefix="/admin", tags=["admin"])


def get_task_service(
    pool: Annotated[Pool, Depends(get_pool)],
) -> TaskService:
    return TaskService(pool)


def get_worker_service(
    pool: Annotated[Pool, Depends(get_pool)],
) -> WorkerService:
    return WorkerService(pool, get_settings())


def get_scheduler_service(
    pool: Annotated[Pool, Depends(get_pool)],
) -> SchedulerService:
    return SchedulerService(pool, get_settings())


# --- Tasks (control plane) ---------------------------------------------------


@task_router.post("", response_model=TaskResponse, status_code=status.HTTP_201_CREATED)
def create_task(
    request: TaskCreateRequest,
    service: Annotated[TaskService, Depends(get_task_service)],
) -> TaskResponse:
    return service.create_task(request)


@task_router.get("", response_model=list[TaskResponse])
def list_tasks(
    service: Annotated[TaskService, Depends(get_task_service)],
) -> list[TaskResponse]:
    return service.list_tasks()


@task_router.get("/{task_id}", response_model=TaskResponse)
def get_task(
    task_id: UUID,
    service: Annotated[TaskService, Depends(get_task_service)],
) -> TaskResponse:
    try:
        return service.get_task(task_id)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@task_router.patch("/{task_id}", response_model=TaskResponse)
def update_task(
    task_id: UUID,
    request: TaskUpdateRequest,
    service: Annotated[TaskService, Depends(get_task_service)],
) -> TaskResponse:
    try:
        return service.update_task(task_id, request)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@task_router.delete("/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_task(
    task_id: UUID,
    service: Annotated[TaskService, Depends(get_task_service)],
) -> Response:
    try:
        service.delete_task(task_id)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@task_router.get("/{task_id}/runs", response_model=list[RunResponse])
def list_task_runs(
    task_id: UUID,
    service: Annotated[TaskService, Depends(get_task_service)],
) -> list[RunResponse]:
    return service.list_runs(task_id)


# --- Workers (data plane) ----------------------------------------------------


@worker_router.post("/workers/{worker_id}/claims", response_model=list[ClaimedRun])
def claim_runs(
    worker_id: str,
    request: ClaimRequest,
    service: Annotated[WorkerService, Depends(get_worker_service)],
) -> list[ClaimedRun]:
    return service.claim(worker_id, request.max_tasks, request.lease_seconds)


@worker_router.get("/runs/{run_id}", response_model=RunResponse)
def get_run(
    run_id: UUID,
    service: Annotated[WorkerService, Depends(get_worker_service)],
) -> RunResponse:
    try:
        return service.get_run(run_id)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@worker_router.post("/runs/{run_id}/heartbeat", response_model=RunResponse)
def heartbeat_run(
    run_id: UUID,
    request: HeartbeatRequest,
    service: Annotated[WorkerService, Depends(get_worker_service)],
) -> RunResponse:
    try:
        return service.heartbeat(run_id, request.lease_token, request.lease_seconds)
    except ConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@worker_router.post("/runs/{run_id}/complete", response_model=RunResponse)
def complete_run(
    run_id: UUID,
    request: CompleteRequest,
    service: Annotated[WorkerService, Depends(get_worker_service)],
) -> RunResponse:
    try:
        return service.complete(run_id, request.lease_token, request.result)
    except ConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@worker_router.post("/runs/{run_id}/fail", response_model=RunResponse)
def fail_run(
    run_id: UUID,
    request: FailRequest,
    service: Annotated[WorkerService, Depends(get_worker_service)],
) -> RunResponse:
    try:
        return service.fail(
            run_id, request.lease_token, request.error, request.retryable
        )
    except ConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


# --- Admin (manual scheduler control for demos) ------------------------------


@admin_router.post("/tick", response_model=TickResponse)
def run_tick(
    service: Annotated[SchedulerService, Depends(get_scheduler_service)],
) -> TickResponse:
    return TickResponse(materialized=service.tick())


@admin_router.post("/reap", response_model=ReapResponse)
def run_reap(
    service: Annotated[SchedulerService, Depends(get_scheduler_service)],
) -> ReapResponse:
    requeued, dead = service.reap()
    return ReapResponse(requeued=requeued, dead=dead)
