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
    JobCreateRequest,
    JobResponse,
    JobUpdateRequest,
    NodeRegisterRequest,
    NodeResponse,
    ReapNodesResponse,
    ReapResponse,
    RunResponse,
    TickResponse,
)
from core.service import (
    ConflictError,
    JobService,
    NodeService,
    NotFoundError,
    SchedulerService,
)

job_router = APIRouter(prefix="/jobs", tags=["jobs"])
node_router = APIRouter(prefix="/nodes", tags=["nodes"])
run_router = APIRouter(prefix="/runs", tags=["runs"])
admin_router = APIRouter(prefix="/admin", tags=["admin"])


def get_job_service(pool: Annotated[Pool, Depends(get_pool)]) -> JobService:
    return JobService(pool)


def get_node_service(pool: Annotated[Pool, Depends(get_pool)]) -> NodeService:
    return NodeService(pool, get_settings())


def get_scheduler_service(
    pool: Annotated[Pool, Depends(get_pool)],
) -> SchedulerService:
    return SchedulerService(pool, get_settings())


# --- Jobs (control plane) ----------------------------------------------------


@job_router.post("", response_model=JobResponse, status_code=status.HTTP_201_CREATED)
def create_job(
    request: JobCreateRequest,
    service: Annotated[JobService, Depends(get_job_service)],
) -> JobResponse:
    return service.create_job(request)


@job_router.get("", response_model=list[JobResponse])
def list_jobs(
    service: Annotated[JobService, Depends(get_job_service)],
) -> list[JobResponse]:
    return service.list_jobs()


@job_router.get("/{job_id}", response_model=JobResponse)
def get_job(
    job_id: UUID,
    service: Annotated[JobService, Depends(get_job_service)],
) -> JobResponse:
    try:
        return service.get_job(job_id)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@job_router.patch("/{job_id}", response_model=JobResponse)
def update_job(
    job_id: UUID,
    request: JobUpdateRequest,
    service: Annotated[JobService, Depends(get_job_service)],
) -> JobResponse:
    try:
        return service.update_job(job_id, request)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@job_router.delete("/{job_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_job(
    job_id: UUID,
    service: Annotated[JobService, Depends(get_job_service)],
) -> Response:
    try:
        service.delete_job(job_id)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@job_router.get("/{job_id}/runs", response_model=list[RunResponse])
def list_job_runs(
    job_id: UUID,
    service: Annotated[JobService, Depends(get_job_service)],
) -> list[RunResponse]:
    return service.list_runs(job_id)


# --- Nodes (data plane: the fleet) -------------------------------------------


@node_router.get("", response_model=list[NodeResponse])
def list_nodes(
    service: Annotated[NodeService, Depends(get_node_service)],
) -> list[NodeResponse]:
    return service.list_nodes()


@node_router.put("/{node_id}", response_model=NodeResponse)
def register_node(
    node_id: str,
    request: NodeRegisterRequest,
    service: Annotated[NodeService, Depends(get_node_service)],
) -> NodeResponse:
    return service.register(node_id, request.cpu_total, request.mem_total_mb)


@node_router.post("/{node_id}/heartbeat", response_model=NodeResponse)
def node_heartbeat(
    node_id: str,
    service: Annotated[NodeService, Depends(get_node_service)],
) -> NodeResponse:
    try:
        return service.node_heartbeat(node_id)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@node_router.post("/{node_id}/claims", response_model=list[ClaimedRun])
def claim_runs(
    node_id: str,
    request: ClaimRequest,
    service: Annotated[NodeService, Depends(get_node_service)],
) -> list[ClaimedRun]:
    return service.claim(node_id, request.max_runs, request.lease_seconds)


# --- Runs (data plane: outcomes) ---------------------------------------------


@run_router.get("/{run_id}", response_model=RunResponse)
def get_run(
    run_id: UUID,
    service: Annotated[NodeService, Depends(get_node_service)],
) -> RunResponse:
    try:
        return service.get_run(run_id)
    except NotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@run_router.post("/{run_id}/heartbeat", response_model=RunResponse)
def heartbeat_run(
    run_id: UUID,
    request: HeartbeatRequest,
    service: Annotated[NodeService, Depends(get_node_service)],
) -> RunResponse:
    try:
        return service.heartbeat_run(run_id, request.lease_token, request.lease_seconds)
    except ConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@run_router.post("/{run_id}/complete", response_model=RunResponse)
def complete_run(
    run_id: UUID,
    request: CompleteRequest,
    service: Annotated[NodeService, Depends(get_node_service)],
) -> RunResponse:
    try:
        return service.complete(run_id, request.lease_token, request.result)
    except ConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@run_router.post("/{run_id}/fail", response_model=RunResponse)
def fail_run(
    run_id: UUID,
    request: FailRequest,
    service: Annotated[NodeService, Depends(get_node_service)],
) -> RunResponse:
    try:
        return service.fail(run_id, request.lease_token, request.error, request.retryable)
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
    requeued, dead = service.reap_runs()
    return ReapResponse(requeued=requeued, dead=dead)


@admin_router.post("/reap-nodes", response_model=ReapNodesResponse)
def run_reap_nodes(
    service: Annotated[SchedulerService, Depends(get_scheduler_service)],
) -> ReapNodesResponse:
    dead_nodes, expired = service.reap_nodes()
    return ReapNodesResponse(dead_nodes=dead_nodes, expired_leases=expired)
