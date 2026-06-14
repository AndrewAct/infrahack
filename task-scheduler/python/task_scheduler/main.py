import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI

from core.database import close_pool, get_settings, init_pool
from core.router import admin_router, task_router, worker_router
from core.scheduler import BackgroundScheduler
from core.service import SchedulerService

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    pool = init_pool()
    settings = get_settings()
    scheduler: BackgroundScheduler | None = None
    if settings.scheduler_enabled:
        scheduler = BackgroundScheduler(
            SchedulerService(pool, settings),
            settings.scheduler_interval_seconds,
        )
        scheduler.start()
    try:
        yield
    finally:
        if scheduler is not None:
            scheduler.stop()
        close_pool()


app = FastAPI(title="Task Scheduler", lifespan=lifespan)
app.include_router(task_router)
app.include_router(worker_router)
app.include_router(admin_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
