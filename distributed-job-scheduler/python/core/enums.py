from enum import StrEnum


class ScheduleKind(StrEnum):
    ONE_TIME = "one_time"
    INTERVAL = "interval"
    # CRON deferred: needs a cron parser + DST handling; one_time + interval is the slice.


class JobStatus(StrEnum):
    ACTIVE = "active"
    PAUSED = "paused"
    CANCELLED = "cancelled"


class RunStatus(StrEnum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    DEAD = "dead"


class NodeStatus(StrEnum):
    ACTIVE = "active"
    DRAINING = "draining"  # reserved: accept no new runs, drain in-flight (roadmap)
    DEAD = "dead"          # heartbeat lapsed; its runs get rescheduled
