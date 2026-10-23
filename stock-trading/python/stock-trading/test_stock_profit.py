from __future__ import annotations

from itertools import product

import pytest

from stock_profit import (
    Summary,
    _chunk_bounds,
    max_profit,
    max_profit_parallel,
    merge,
    process_chunk,
)


CASES = [
    ([], 0),
    ([1], 0),
    ([1, 2], 1),
    ([2, 1], 0),
    ([7, 1, 5, 3, 6, 4], 5),
    ([7, 6, 4, 3, 1], 0),
    ([3, 3, 3, 3], 0),
    ([1, 10, 2, 3], 9),
    ([10, 1, 2, 3, 20], 19),
]


@pytest.mark.parametrize(("prices", "expected"), CASES)
def test_max_profit(prices: list[int], expected: int) -> None:
    assert max_profit(prices) == expected


@pytest.mark.parametrize(("prices", "expected"), CASES)
@pytest.mark.parametrize("num_workers", (1, 2, 3, 8))
def test_max_profit_parallel(
    prices: list[int], expected: int, num_workers: int
) -> None:
    assert max_profit_parallel(prices, num_workers) == expected


@pytest.mark.parametrize(
    ("n", "num_workers", "expected"),
    [
        (0, 1, []),
        (1, 4, [(0, 1)]),
        (10, 6, [(0, 2), (2, 4), (4, 6), (6, 8), (8, 9), (9, 10)]),
        (10, 3, [(0, 4), (4, 7), (7, 10)]),
    ],
)
def test_chunk_bounds_are_balanced_and_complete(
    n: int, num_workers: int, expected: list[tuple[int, int]]
) -> None:
    assert _chunk_bounds(n, num_workers) == expected


@pytest.mark.parametrize("num_workers", (0, -1))
def test_parallel_rejects_non_positive_worker_counts(num_workers: int) -> None:
    with pytest.raises(ValueError, match="num_workers must be at least 1"):
        max_profit_parallel([], num_workers)


def test_process_chunk_and_merge_handle_cross_chunk_transaction() -> None:
    prices = [10, 1, 2, 3, 20]
    left = process_chunk(prices, 0, 3)
    right = process_chunk(prices, 3, 5)

    assert left == Summary(min_price=1, max_price=10, best_profit=1)
    assert right == Summary(min_price=3, max_price=20, best_profit=17)
    assert merge(left, right) == Summary(min_price=1, max_price=20, best_profit=19)


def test_merge_is_associative_for_adjacent_chunks() -> None:
    prices = [7, 1, 5, 3, 6, 4]
    first = process_chunk(prices, 0, 2)
    second = process_chunk(prices, 2, 4)
    third = process_chunk(prices, 4, 6)

    merged = merge(merge(first, second), third)
    assert merged == merge(first, merge(second, third))
    assert merged == process_chunk(prices, 0, len(prices))


def _brute_force_max_profit(prices: list[int]) -> int:
    return max(
        0,
        max(
            (
                prices[sell] - prices[buy]
                for buy in range(len(prices))
                for sell in range(buy + 1, len(prices))
            ),
            default=0,
        ),
    )


def test_implementations_match_brute_force_for_small_inputs() -> None:
    for length in range(6):
        for prices in map(list, product(range(4), repeat=length)):
            expected = _brute_force_max_profit(prices)
            assert max_profit(prices) == expected
            for num_workers in (1, 2, 3, length + 2):
                assert max_profit_parallel(prices, num_workers) == expected
