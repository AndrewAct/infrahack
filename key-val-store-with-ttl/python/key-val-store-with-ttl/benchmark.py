import argparse
import tracemalloc
from time import perf_counter

from main import TemporalKVStore


def run(operations: int) -> None:
    """Measure the hot-field append and historical-read paths."""
    store = TemporalKVStore()
    tracemalloc.start()

    write_started = perf_counter()
    for timestamp in range(operations):
        store.set(timestamp, "hot-key", "hot-field", timestamp)
    write_seconds = perf_counter() - write_started

    read_started = perf_counter()
    checksum = 0
    for at_timestamp in range(operations):
        value = store.get_when(
            operations,
            "hot-key",
            "hot-field",
            at_timestamp,
        )
        if value is not None:
            checksum += value
    read_seconds = perf_counter() - read_started

    current_bytes, peak_bytes = tracemalloc.get_traced_memory()
    tracemalloc.stop()

    expected_checksum = operations * (operations - 1) // 2
    if checksum != expected_checksum:
        raise RuntimeError(
            f"unexpected checksum: got {checksum}, expected {expected_checksum}"
        )

    mib = 1024 * 1024
    print(f"operations: {operations:,}")
    print(f"writes: {write_seconds:.3f}s ({operations / write_seconds:,.0f} ops/s)")
    print(
        "historical reads: "
        f"{read_seconds:.3f}s ({operations / read_seconds:,.0f} ops/s)"
    )
    print(f"current traced memory: {current_bytes / mib:.1f} MiB")
    print(f"peak traced memory: {peak_bytes / mib:.1f} MiB")
    print(f"checksum: {checksum}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark TemporalKVStore's deepest-version-chain workload."
    )
    parser.add_argument(
        "--operations",
        type=int,
        default=200_000,
        help="number of writes and historical reads (default: 200000)",
    )
    args = parser.parse_args()
    if args.operations <= 0:
        parser.error("--operations must be positive")
    run(args.operations)


if __name__ == "__main__":
    main()
