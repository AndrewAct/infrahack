# Task Scheduler

A distributed task-scheduler case study with interchangeable language implementations.

The shared design supports one-time and recurring tasks, priority-based dispatch,
worker pull with leases, bounded retries, and execution history. Each language
implementation may use its own idioms while preserving those core semantics.

See [CHEATSHEET.md](CHEATSHEET.md) for the full design, scaling notes, and interview prep.

## Implementations

- [Python](python/README.md): FastAPI HTTP shell over Postgres (psycopg), with a
  `tasks` / `task_runs` model, `SKIP LOCKED` worker claims, leases, and a
  background scheduler + reaper.
