from __future__ import annotations

from functools import lru_cache

from psycopg import Connection, Cursor
from psycopg.rows import DictRow, dict_row
from psycopg_pool import ConnectionPool
from pydantic_settings import BaseSettings, SettingsConfigDict

# Connections are configured with the dict_row factory below, so rows are dicts.
# Carry that through the type system via the DictRow generic.
Pool = ConnectionPool[Connection[DictRow]]


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
    database_url: str | None = None

    scheduler_enabled: bool = True
    scheduler_interval_seconds: float = 1.0

    # Retry backoff (seconds): delay = min(base * 2^(attempt-1), cap) * jitter.
    retry_base_seconds: float = 2.0
    retry_cap_seconds: float = 300.0

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()


_pool: Pool | None = None


def init_pool() -> Pool:
    """Open the connection pool. Called once from the app lifespan."""
    global _pool
    if _pool is not None:
        return _pool
    settings = get_settings()
    if not settings.database_url:
        raise RuntimeError(
            "DATABASE_URL is not set. Set it to your Postgres connection string "
            "(Supabase: Settings -> Database -> Connection string -> URI). The "
            "SUPABASE_SECRET_KEY/service-role key is not the database password."
        )
    pool: Pool = ConnectionPool(
        settings.database_url,
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
