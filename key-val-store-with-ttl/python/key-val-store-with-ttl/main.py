from bisect import bisect_right
from dataclasses import dataclass


@dataclass(slots=True, frozen=True)
class _Version:
    timestamp: int
    value: int | None  # None marks a tombstone (field deleted as of this timestamp)
    expires_at: int | None  # None means the write never expires


class TemporalKVStore:
    """DB[key][field] = value, with per-write TTL and time-travel reads.

    Each (key, field) keeps an append-only list of versions ordered by
    timestamp. Resolving a value at time T means: binary-search for the
    latest version with version.timestamp <= T and return *only* that
    version's own state (tombstone / expired / value). We never fall back
    to an older version once the latest one is expired or deleted -- an
    expired TTL write must look "missing", not resurrect whatever it
    overwrote.
    """

    def __init__(self) -> None:
        self._history: dict[str, dict[str, list[_Version]]] = {}

    # ---- Level 1: basic operations ----

    def set(self, timestamp: int, key: str, field: str, value: int) -> None:
        self._append(key, field, timestamp, value, expires_at=None)

    def get(self, timestamp: int, key: str, field: str) -> int | None:
        return self._resolve(key, field, timestamp)

    def compare_and_set(
        self,
        timestamp: int,
        key: str,
        field: str,
        expected_value: int,
        new_value: int,
    ) -> bool:
        return self._compare_and_set(
            timestamp, key, field, expected_value, new_value, expires_at=None
        )

    def compare_and_delete(
        self, timestamp: int, key: str, field: str, expected_value: int
    ) -> bool:
        if self._resolve(key, field, timestamp) != expected_value:
            return False
        self._append(key, field, timestamp, value=None, expires_at=None)
        return True

    # ---- Level 2: scans ----

    def scan(self, timestamp: int, key: str) -> list[str]:
        return self._scan(timestamp, key, prefix="")

    def scan_with_prefix(self, timestamp: int, key: str, prefix: str) -> list[str]:
        return self._scan(timestamp, key, prefix=prefix)

    # ---- Level 3: TTL ----

    def set_with_ttl(
        self, timestamp: int, key: str, field: str, value: int, ttl: int
    ) -> None:
        self._append(key, field, timestamp, value, expires_at=timestamp + ttl)

    def compare_and_set_with_ttl(
        self,
        timestamp: int,
        key: str,
        field: str,
        expected_value: int,
        new_value: int,
        ttl: int,
    ) -> bool:
        return self._compare_and_set(
            timestamp,
            key,
            field,
            expected_value,
            new_value,
            expires_at=timestamp + ttl,
        )

    # ---- Level 4: time travel ----

    def get_when(
        self, timestamp: int, key: str, field: str, at_timestamp: int
    ) -> int | None:
        del timestamp  # caller's "now"; visibility is decided by at_timestamp
        return self._resolve(key, field, at_timestamp)

    # ---- internal helpers ----

    def _append(
        self,
        key: str,
        field: str,
        timestamp: int,
        value: int | None,
        expires_at: int | None,
    ) -> None:
        fields = self._history.setdefault(key, {})
        fields.setdefault(field, []).append(_Version(timestamp, value, expires_at))

    def _resolve(self, key: str, field: str, at_timestamp: int) -> int | None:
        versions = self._history.get(key, {}).get(field)
        if not versions:
            return None
        # Timestamps are non-decreasing, so versions is sorted -- binary
        # search for the latest version at or before at_timestamp.
        index = bisect_right(versions, at_timestamp, key=lambda v: v.timestamp) - 1
        if index < 0:
            return None
        version = versions[index]
        if version.value is None:
            return None
        if version.expires_at is not None and at_timestamp >= version.expires_at:
            return None
        return version.value

    def _compare_and_set(
        self,
        timestamp: int,
        key: str,
        field: str,
        expected_value: int,
        new_value: int,
        expires_at: int | None,
    ) -> bool:
        if self._resolve(key, field, timestamp) != expected_value:
            return False
        self._append(key, field, timestamp, new_value, expires_at)
        return True

    def _scan(self, timestamp: int, key: str, prefix: str) -> list[str]:
        fields = self._history.get(key, {})
        entries = []
        for field in sorted(fields):
            if not field.startswith(prefix):
                continue
            value = self._resolve(key, field, timestamp)
            if value is not None:
                entries.append(f"{field}({value})")
        return entries


def main() -> None:
    # Level 4 worked example from the spec.
    history_store = TemporalKVStore()
    history_store.set(10, "item", "x", 100)
    history_store.set_with_ttl(20, "item", "x", 200, ttl=5)
    history_store.set(30, "item", "x", 300)
    assert history_store.get_when(30, "item", "x", at_timestamp=15) == 100
    assert history_store.get_when(30, "item", "x", at_timestamp=22) == 200
    assert history_store.get_when(30, "item", "x", at_timestamp=27) is None
    assert history_store.get_when(35, "item", "x", at_timestamp=35) == 300

    # A TTL write that later expires must not resurrect what it overwrote.
    ttl_store = TemporalKVStore()
    ttl_store.set(1, "user1", "score", 10)
    ttl_store.set(1, "user1", "age", 25)
    ttl_store.set(1, "user1", "status", 1)
    ttl_store.set_with_ttl(5, "user1", "score", 20, ttl=3)
    assert ttl_store.get_when(100, "user1", "score", at_timestamp=4) == 10
    assert ttl_store.get_when(100, "user1", "score", at_timestamp=6) == 20
    assert ttl_store.get_when(100, "user1", "score", at_timestamp=8) is None
    assert ttl_store.scan(6, "user1") == [
        "age(25)",
        "score(20)",
        "status(1)",
    ]

    print("all example assertions passed")


if __name__ == "__main__":
    main()
