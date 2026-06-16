"""Node agent — a worker node in the fleet.

Run several of these (each with its own id + capacity) against the same database to
get a real distributed cluster on one laptop, no Docker required:

    python node_agent.py node-a --cpu 4 --mem 8192
    python node_agent.py node-b --cpu 2 --mem 4096

Each agent:
  1. registers its capacity,
  2. heartbeats (node liveness + the lease of every run it holds),
  3. claims runs that FIT its free capacity and executes them concurrently,
  4. reports complete/fail with its fencing token.

Failure demos (the agent talks straight to Postgres, so signals == node faults):
    kill -9  <pid>   hard crash      -> leases expire -> scheduler reschedules its runs
    kill -STOP <pid> freeze (no HB)  -> looks dead -> rescheduled; on kill -CONT its
                                        stale fencing token is rejected (no double-write)
"""

from __future__ import annotations

import argparse
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable
from uuid import UUID

from core.database import get_settings, init_pool
from core.schemas import ClaimedRun
from core.service import ConflictError, NodeService

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
logger = logging.getLogger("node_agent")

# --- handler registry: payload["type"] -> function(payload) -> result ---------
Handler = Callable[[dict[str, Any]], dict[str, Any]]
HANDLERS: dict[str, Handler] = {}


def handler(kind: str) -> Callable[[Handler], Handler]:
    def register(fn: Handler) -> Handler:
        HANDLERS[kind] = fn
        return fn

    return register


@handler("sleep")
def _sleep(payload: dict[str, Any]) -> dict[str, Any]:
    """Occupy the node's capacity for `seconds` (default 2) — makes placement visible."""
    seconds = float(payload.get("seconds", 2))
    time.sleep(seconds)
    return {"slept": seconds}


@handler("echo")
def _echo(payload: dict[str, Any]) -> dict[str, Any]:
    return {"echo": payload.get("message", "")}


@handler("boom")
def _boom(payload: dict[str, Any]) -> dict[str, Any]:
    raise RuntimeError(payload.get("message", "boom (intentional failure)"))


def _dispatch(payload: dict[str, Any]) -> dict[str, Any]:
    return HANDLERS.get(payload.get("type", "sleep"), _sleep)(payload)


class NodeAgent:
    def __init__(
        self,
        node_id: str,
        cpu: int,
        mem_mb: int,
        lease_seconds: int,
        poll_seconds: float,
    ) -> None:
        self._id = node_id
        self._cpu = cpu
        self._mem = mem_mb
        self._lease = lease_seconds
        self._poll = poll_seconds
        self._service = NodeService(init_pool(), get_settings())
        self._executor = ThreadPoolExecutor(max_workers=cpu, thread_name_prefix="exec")
        self._inflight: dict[UUID, UUID] = {}  # run_id -> lease_token
        self._lock = threading.Lock()
        self._stop = threading.Event()

    def run(self) -> None:
        self._service.register(self._id, self._cpu, self._mem)
        logger.info("registered %s (cpu=%s mem=%sMB)", self._id, self._cpu, self._mem)
        hb = threading.Thread(target=self._heartbeat_loop, daemon=True)
        hb.start()
        try:
            self._claim_loop()
        except KeyboardInterrupt:
            logger.info("shutting down %s", self._id)
            self._stop.set()
            self._executor.shutdown(wait=True)

    def _claim_loop(self) -> None:
        while not self._stop.is_set():
            runs = self._service.claim(self._id, max_runs=self._cpu, lease_seconds=self._lease)
            if not runs:
                time.sleep(self._poll)
                continue
            for run in runs:
                with self._lock:
                    self._inflight[run.id] = run.lease_token
                self._executor.submit(self._execute, run)

    def _execute(self, run: ClaimedRun) -> None:
        try:
            result = _dispatch(run.payload)
            self._service.complete(run.id, run.lease_token, result)
            logger.info("completed run=%s (req_cpu=%s)", run.id, run.req_cpu)
        except ConflictError:
            # Lost the lease while executing (e.g. we were frozen and reaped). The
            # fencing token is stale; another node owns this run now. Drop it.
            logger.warning("lost lease for run=%s; dropping result", run.id)
        except Exception as error:  # noqa: BLE001 - handler failures are expected
            try:
                self._service.fail(run.id, run.lease_token, str(error), retryable=True)
                logger.info("failed run=%s: %s", run.id, error)
            except ConflictError:
                logger.warning("lost lease for run=%s; cannot report failure", run.id)
        finally:
            with self._lock:
                self._inflight.pop(run.id, None)

    def _heartbeat_loop(self) -> None:
        # Renew well within the lease window so a busy node is never falsely reaped.
        interval = max(1.0, self._lease / 3)
        while not self._stop.wait(interval):
            try:
                self._service.node_heartbeat(self._id)
            except Exception:
                logger.exception("node heartbeat failed")
            with self._lock:
                inflight = list(self._inflight.items())
            for run_id, token in inflight:
                try:
                    self._service.heartbeat_run(run_id, token, self._lease)
                except ConflictError:
                    pass  # run finished or lease lost; _execute handles it


def main() -> None:
    parser = argparse.ArgumentParser(description="Distributed job scheduler node agent")
    parser.add_argument("node_id", help="unique node id, e.g. node-a")
    parser.add_argument("--cpu", type=int, default=4, help="total CPU units")
    parser.add_argument("--mem", type=int, default=8192, help="total memory (MB)")
    parser.add_argument("--lease", type=int, default=30, help="lease seconds per claim")
    parser.add_argument("--poll", type=float, default=1.0, help="idle poll interval (s)")
    args = parser.parse_args()
    NodeAgent(args.node_id, args.cpu, args.mem, args.lease, args.poll).run()


if __name__ == "__main__":
    main()
