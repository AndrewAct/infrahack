import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI

from core.database import close_pool, get_settings, init_pool
from core.router import admin_router, job_router, node_router, run_router
from core.scheduler import BackgroundScheduler
from core.service import SchedulerService

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    pool = init_pool()
    settings = get_settings()
    scheduler: BackgroundScheduler | None = None
    if settings.scheduler_enabled:
        # Safe to run on every replica: leadership is gated by an advisory lock,
        # so only one process actually ticks/reaps at a time.
        scheduler = BackgroundScheduler(SchedulerService(pool, settings), settings)
        scheduler.start()
    try:
        yield
    finally:
        if scheduler is not None:
            scheduler.stop()
        close_pool()


app = FastAPI(title="Distributed Job Scheduler", lifespan=lifespan)
app.include_router(job_router)
app.include_router(node_router)
app.include_router(run_router)
app.include_router(admin_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
