import logging
import threading

from core.database import LEADER_LOCK_KEY, Settings, connect_session
from core.service import SchedulerService

logger = logging.getLogger("job_scheduler.scheduler")


class BackgroundScheduler:
    """Runs reap_nodes + tick + reap_runs on a fixed interval, but ONLY while this
    replica holds the leadership advisory lock.

    Leader election uses a Postgres SESSION-level advisory lock held on a dedicated
    connection: every replica calls pg_try_advisory_lock(LEADER_LOCK_KEY); exactly
    one wins. The lock lives as long as that connection is open, so if the leader
    crashes (connection drops) the lock auto-releases and a standby acquires it on its
    next cycle. No ZooKeeper/etcd. Standbys just idle until they win the lock.

    Every cycle is idempotent, so a missed/overlapping cycle is harmless.
    """

    def __init__(self, scheduler: SchedulerService, settings: Settings) -> None:
        self._scheduler = scheduler
        self._settings = settings
        self._interval = settings.scheduler_interval_seconds
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._run, name="scheduler-loop", daemon=True
        )
        self._lock_conn = None
        self._is_leader = False

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=5)
        self._release()

    def _acquire_leadership(self) -> bool:
        """Try to become (or confirm we remain) the leader. Holding the lock connection
        open == holding leadership."""
        try:
            if self._lock_conn is None or self._lock_conn.closed:
                self._lock_conn = connect_session(self._settings.database_url)
                self._is_leader = False
            if not self._is_leader:
                got = self._lock_conn.execute(
                    "select pg_try_advisory_lock(%s)", (LEADER_LOCK_KEY,)
                ).fetchone()[0]
                if got:
                    self._is_leader = True
                    logger.info("acquired scheduler leadership")
            return self._is_leader
        except Exception:
            logger.exception("leadership acquisition failed; will retry")
            self._release()
            return False

    def _release(self) -> None:
        if self._lock_conn is not None:
            try:
                self._lock_conn.close()
            except Exception:
                pass
        self._lock_conn = None
        self._is_leader = False

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                if not self._acquire_leadership():
                    continue  # standby: do nothing until we win the lock
                dead_nodes, expired = self._scheduler.reap_nodes()
                materialized = self._scheduler.tick()
                requeued, dead = self._scheduler.reap_runs()
                if materialized or requeued or dead or dead_nodes:
                    logger.info(
                        "leader cycle materialized=%s requeued=%s dead=%s "
                        "dead_nodes=%s expired_leases=%s",
                        materialized,
                        requeued,
                        dead,
                        dead_nodes,
                        expired,
                    )
            except Exception:
                logger.exception("scheduler cycle failed")
