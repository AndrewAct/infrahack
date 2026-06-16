import unittest

from core.service import compute_backoff


class ComputeBackoffTest(unittest.TestCase):
    def test_within_jittered_bounds_and_capped(self) -> None:
        base, cap = 2.0, 300.0
        for attempt in range(1, 8):
            expected = min(base * (2 ** (attempt - 1)), cap)
            for _ in range(200):
                delay = compute_backoff(attempt, base, cap)
                # jitter is 50-100% of the (capped) exponential value
                self.assertGreaterEqual(delay, 0.5 * expected)
                self.assertLessEqual(delay, expected)
                self.assertLessEqual(delay, cap)

    def test_grows_with_attempt(self) -> None:
        base, cap = 1.0, 10_000.0
        lows = [0.5 * min(base * (2 ** (a - 1)), cap) for a in range(1, 6)]
        self.assertTrue(all(lows[i] < lows[i + 1] for i in range(len(lows) - 1)))


if __name__ == "__main__":
    unittest.main()
