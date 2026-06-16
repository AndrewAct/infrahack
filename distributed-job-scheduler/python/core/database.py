from __future__ import annotations

from functools import lru_cache

import psycopg
from psycopg import Connection, Cursor
from psycopg.rows import DictRow, dict_row
from psycopg_pool import ConnectionPool
from pydantic_settings import BaseSettings, SettingsConfigDict

# Connections are configured with the dict_row factory below, so rows are dicts.
# Carry that through the type system via the DictRow generic.
Pool = ConnectionPool[Connection[DictRow]]

# Fixed 64-bit key for the scheduler leadership advisory lock. Every scheduler
# replica calls pg_try_advisory_lock(LEADER_LOCK_KEY); exactly one wins and becomes
# the leader that ticks/reaps. The lock is held by an open session, so a crashed
# leader's lock auto-releases and a standby takes over -- HA without ZooKeeper/etcd.
LEADER_LOCK_KEY = 0x6A6F6273636864  # "jobschd"


class GuardedCursor(Cursor[DictRow]):
    """Normalize a Supabase-pooler (Supavisor) quirk: fetching from a zero-row
    result raises `InterfaceError` instead of returning None/[]. Guarding on
    rowcount lets every `.fetchone()`/`.fetchall()` call site stay idiomatic.
    """

    def fetchone(self) -> DictRow | None:
        return super().fetchone() if self.rowcount else None

    def fetchall(self) -> list[DictRow]:
        return super().fetchall() if self.rowcount else []


def _configure(conn: Connection[DictRow]) -> None:
    conn.cursor_factory = GuardedCursor


class Settings(BaseSettings):
    # Postgres connection string. With Supabase: Dashboard -> Settings -> Database ->
    # Connection string -> URI. NOTE: the service-role key is NOT the DB password.
    # Leader election + the standalone agent pool need SESSION-mode connections, so
    # use the direct/session-pooler URI (not the transaction pooler).
    database_url: str | None = None

    scheduler_enabled: bool = True
    scheduler_interval_seconds: float = 1.0

    # A node is presumed dead (and its runs rescheduled) after this much silence.
    node_heartbeat_timeout_seconds: float = 15.0

    # Retry backoff (seconds): delay = min(base * 2^(attempt-1), cap) * jitter.
    retry_base_seconds: float = 2.0
    retry_cap_seconds: float = 300.0

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()


def _require_dsn() -> str:
    settings = get_settings()
    if not settings.database_url:
        raise RuntimeError(
            "DATABASE_URL is not set. Set it to your Postgres connection string "
            "(Supabase: Settings -> Database -> Connection string -> URI). The "
            "SUPABASE_SECRET_KEY/service-role key is not the database password."
        )
    return settings.database_url


def connect_session(dsn: str | None = None) -> Connection:
    """Open a standalone autocommit SESSION connection (not from the pool).

    Used to HOLD a session-level advisory lock for leader election: the lock lives
    exactly as long as this connection is open, so closing it (or crashing) releases
    leadership. Must be a session-mode endpoint, not the transaction pooler.
    """
    return psycopg.connect(dsn or _require_dsn(), autocommit=True)


_pool: Pool | None = None


def init_pool() -> Pool:
    """Open the shared connection pool. Called from the app lifespan or an agent."""
    global _pool
    if _pool is not None:
        return _pool
    dsn = _require_dsn()
    pool: Pool = ConnectionPool(
        dsn,
        min_size=1,
        max_size=10,
        kwargs={"row_factory": dict_row, "autocommit": False},
        configure=_configure,
        open=False,
    )
    pool.open(wait=True, timeout=10)
    _pool = pool
    return _pool


def get_pool() -> Pool:
    if _pool is None:
        raise RuntimeError("connection pool is not initialized")
    return _pool


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None
