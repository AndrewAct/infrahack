import logging
import threading

from core.service import SchedulerService

logger = logging.getLogger("task_scheduler.scheduler")


class BackgroundScheduler:
    """Runs the scheduler tick + reaper on a fixed interval in a daemon thread.

    The tick only materializes due runs, and the reaper only reclaims expired
    leases; both are cheap (partial-index scans) and idempotent, so a missed or
    overlapping cycle is harmless.
    """

    def __init__(self, scheduler: SchedulerService, interval_seconds: float) -> None:
        self._scheduler = scheduler
        self._interval = interval_seconds
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._run, name="scheduler-loop", daemon=True
        )

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=5)

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                materialized = self._scheduler.tick()
                requeued, dead = self._scheduler.reap()
                if materialized or requeued or dead:
                    logger.info(
                        "tick materialized=%s requeued=%s dead=%s",
                        materialized,
                        requeued,
                        dead,
                    )
            except Exception:
                logger.exception("scheduler cycle failed")
