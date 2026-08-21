import pytest

from main import TemporalKVStore


@pytest.fixture
def store() -> TemporalKVStore:
    return TemporalKVStore()


def test_basic_overwrite(store: TemporalKVStore) -> None:
    store.set(1, "k", "f", 10)
    store.set(2, "k", "f", 20)

    assert store.get(2, "k", "f") == 20
    assert store.get_when(2, "k", "f", at_timestamp=1) == 10


def test_missing_values(store: TemporalKVStore) -> None:
    assert store.get(1, "k", "f") is None
    assert store.compare_and_set(1, "k", "f", expected_value=10, new_value=20) is False
    assert store.compare_and_delete(1, "k", "f", expected_value=10) is False


def test_ttl_boundary(store: TemporalKVStore) -> None:
    store.set_with_ttl(10, "k", "f", 5, ttl=3)

    assert store.get(10, "k", "f") == 5
    assert store.get(12, "k", "f") == 5
    assert store.get(13, "k", "f") is None


def test_ttl_overwrite_does_not_resurrect_old_value(store: TemporalKVStore) -> None:
    store.set(1, "k", "f", 10)
    store.set_with_ttl(5, "k", "f", 20, ttl=3)

    assert store.get_when(100, "k", "f", at_timestamp=4) == 10
    assert store.get_when(100, "k", "f", at_timestamp=6) == 20
    assert store.get_when(100, "k", "f", at_timestamp=8) is None
    assert store.get_when(100, "k", "f", at_timestamp=100) is None


def test_delete_and_recreate(store: TemporalKVStore) -> None:
    store.set(1, "k", "f", 10)
    assert store.compare_and_delete(2, "k", "f", expected_value=10) is True
    store.set(5, "k", "f", 30)

    assert store.get_when(5, "k", "f", at_timestamp=1) == 10
    assert store.get_when(5, "k", "f", at_timestamp=3) is None
    assert store.get_when(5, "k", "f", at_timestamp=5) == 30


def test_failed_cas_does_not_create_history(store: TemporalKVStore) -> None:
    store.set_with_ttl(1, "k", "f", 10, ttl=3)
    versions_before = len(store._history["k"]["f"])

    assert store.compare_and_set(2, "k", "f", expected_value=100, new_value=20) is False
    assert len(store._history["k"]["f"]) == versions_before
    assert store.get_when(2, "k", "f", at_timestamp=3) == 10
    assert store.get_when(4, "k", "f", at_timestamp=4) is None


def test_failed_cas_with_ttl_does_not_create_history(
    store: TemporalKVStore,
) -> None:
    store.set(1, "k", "f", 10)
    versions_before = len(store._history["k"]["f"])

    assert (
        store.compare_and_set_with_ttl(
            2,
            "k",
            "f",
            expected_value=100,
            new_value=20,
            ttl=5,
        )
        is False
    )
    assert len(store._history["k"]["f"]) == versions_before
    assert store.get(100, "k", "f") == 10


def test_cas_after_expiration_fails(store: TemporalKVStore) -> None:
    store.set_with_ttl(1, "k", "f", 10, ttl=3)

    assert store.get(4, "k", "f") is None
    assert store.compare_and_set(4, "k", "f", expected_value=10, new_value=99) is False
    assert store.compare_and_delete(4, "k", "f", expected_value=10) is False
    assert (
        store.compare_and_set_with_ttl(
            4,
            "k",
            "f",
            expected_value=10,
            new_value=99,
            ttl=5,
        )
        is False
    )


def test_cas_overwrites_ttl_value_with_non_ttl_value(store: TemporalKVStore) -> None:
    store.set_with_ttl(1, "k", "f", 10, ttl=5)

    assert store.compare_and_set(2, "k", "f", expected_value=10, new_value=20) is True
    assert store.get(100, "k", "f") == 20


def test_cas_with_ttl_creates_new_ttl(store: TemporalKVStore) -> None:
    store.set(1, "k", "f", 10)

    assert (
        store.compare_and_set_with_ttl(
            5, "k", "f", expected_value=10, new_value=20, ttl=4
        )
        is True
    )
    assert store.get(8, "k", "f") == 20
    assert store.get(9, "k", "f") is None


def test_scans_ignore_expired_and_deleted_values(store: TemporalKVStore) -> None:
    store.set(1, "k", "active", 1)
    store.set_with_ttl(1, "k", "active_ttl", 2, ttl=100)
    store.set_with_ttl(1, "k", "expired_ttl", 3, ttl=2)
    store.set(1, "k", "deleted", 4)
    store.compare_and_delete(2, "k", "deleted", expected_value=4)

    assert store.scan(10, "k") == ["active(1)", "active_ttl(2)"]


def test_scan_ordering(store: TemporalKVStore) -> None:
    store.set(1, "k", "zebra", 1)
    store.set(1, "k", "apple", 2)
    store.set(1, "k", "middle", 3)

    assert store.scan(1, "k") == ["apple(2)", "middle(3)", "zebra(1)"]


def test_scan_with_no_visible_fields_returns_empty_list(
    store: TemporalKVStore,
) -> None:
    assert store.scan(1, "missing-key") == []


def test_prefix_scanning(store: TemporalKVStore) -> None:
    store.set(1, "k", "app", 1)
    store.set(1, "k", "apple", 2)
    store.set(1, "k", "application", 3)
    store.set(1, "k", "banana", 4)

    assert store.scan_with_prefix(1, "k", "app") == [
        "app(1)",
        "apple(2)",
        "application(3)",
    ]


def test_last_successful_write_wins_at_same_timestamp(
    store: TemporalKVStore,
) -> None:
    store.set(5, "k", "f", 10)
    assert store.compare_and_set(5, "k", "f", expected_value=10, new_value=20)
    assert store.get_when(5, "k", "f", at_timestamp=5) == 20

    assert store.compare_and_delete(5, "k", "f", expected_value=20)
    assert store.get_when(5, "k", "f", at_timestamp=5) is None


def test_multiple_historical_versions(store: TemporalKVStore) -> None:
    store.set(1, "k", "f", 10)  # [1, 5)   -> 10
    store.compare_and_set(
        5, "k", "f", expected_value=10, new_value=20
    )  # [5, 10)  -> 20
    store.set_with_ttl(10, "k", "f", 30, ttl=5)  # [10, 15) -> 30, then expired
    store.set(15, "k", "f", 40)  # [15, 20) -> 40
    store.compare_and_delete(20, "k", "f", expected_value=40)  # [20, 25) -> missing
    store.set(25, "k", "f", 50)  # [25, inf) -> 50

    assert store.get_when(100, "k", "f", at_timestamp=3) == 10
    assert store.get_when(100, "k", "f", at_timestamp=7) == 20
    assert store.get_when(100, "k", "f", at_timestamp=12) == 30
    assert store.get_when(100, "k", "f", at_timestamp=16) == 40
    assert store.get_when(100, "k", "f", at_timestamp=20) is None
    assert store.get_when(100, "k", "f", at_timestamp=22) is None
    assert store.get_when(100, "k", "f", at_timestamp=25) == 50
    assert store.get_when(100, "k", "f", at_timestamp=1000) == 50
