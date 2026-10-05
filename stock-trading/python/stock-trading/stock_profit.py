"""Max single-transaction profit: sequential O(n) baseline + chunked ThreadPoolExecutor version."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass


def max_profit(prices: list[int]) -> int:
    """O(n) time, O(1) space: track the running minimum price and best profit so far."""
    if not prices:
        return 0

    min_price = prices[0]
    best_profit = 0
    for price in prices[1:]:
        best_profit = max(best_profit, price - min_price)
        min_price = min(min_price, price)
    return best_profit


@dataclass(frozen=True)
class Summary:
    min_price: int
    max_price: int
    best_profit: int


def process_chunk(prices: list[int], start: int, end: int) -> Summary:
    """Summarize prices[start:end). Caller guarantees start < end (no empty chunks)."""
    min_price = max_price = prices[start]
    best_profit = 0
    for i in range(start + 1, end):
        price = prices[i]
        best_profit = max(best_profit, price - min_price)
        min_price = min(min_price, price)
        max_price = max(max_price, price)
    return Summary(min_price, max_price, best_profit)


def merge(left: Summary, right: Summary) -> Summary:
    """left and right must be adjacent chunks in chronological order (left before right)."""
    return Summary(
        min_price=min(left.min_price, right.min_price),
        max_price=max(left.max_price, right.max_price),
        best_profit=max(
            left.best_profit,
            right.best_profit,
            right.max_price - left.min_price,  # buy in left, sell in right
        ),
    )


def _chunk_bounds(n: int, num_workers: int) -> list[tuple[int, int]]:
    """Return balanced, non-empty [start, end) ranges covering [0, n) in order."""
    if num_workers < 1:
        raise ValueError("num_workers must be at least 1")
    if n == 0:
        return []

    chunk_count = min(num_workers, n)
    base_size, larger_chunk_count = divmod(n, chunk_count)

    bounds = []
    start = 0
    for chunk_index in range(chunk_count):
        size = base_size + (chunk_index < larger_chunk_count)
        end = start + size
        bounds.append((start, end))
        start = end
    return bounds


def max_profit_parallel(prices: list[int], num_workers: int) -> int:
    if num_workers < 1:
        raise ValueError("num_workers must be at least 1")

    n = len(prices)
    if n == 0:
        return 0

    bounds = _chunk_bounds(n, num_workers)
    if len(bounds) == 1:
        start, end = bounds[0]
        return process_chunk(prices, start, end).best_profit

    with ThreadPoolExecutor(max_workers=len(bounds)) as pool:
        futures = [
            pool.submit(process_chunk, prices, start, end)
            for start, end in bounds
        ]
        # Completion order is nondeterministic; submission order is chronological.
        summaries = [future.result() for future in futures]

    result = summaries[0]
    for summary in summaries[1:]:
        result = merge(result, summary)
    return result.best_profit
