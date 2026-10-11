import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("server_p95", Path(__file__).with_name("server_p95.py"))
sp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sp)


def scrape(counts):
    return "\n".join(f'matchgw_verification_latency_seconds_bucket{{application="m",le="{le}"}} {c}' for le, c in counts)


class ServerP95Test(unittest.TestCase):
    def test_window_delta_summed_over_replicas_and_interpolated(self):
        before = scrape([("0.1", 50), ("0.2", 50), ("+Inf", 50)])  # history before the run: must not count
        after_a = scrape([("0.1", 100), ("0.2", 150), ("+Inf", 150)])
        after_b = scrape([("0.1", 0), ("0.2", 0), ("+Inf", 0)])
        p95, n = sp.quantile(0.95, sp.buckets(before), sp.buckets(after_a + "\n" + after_b))
        self.assertEqual(n, 100)  # 150 - 50
        # window: 50 in (0, 0.1], 50 in (0.1, 0.2]; rank 95 -> 0.1 + 0.1 * 45/50
        self.assertAlmostEqual(p95, 0.19)

    def test_empty_window(self):
        same = scrape([("0.1", 5), ("+Inf", 5)])
        self.assertEqual(sp.quantile(0.95, sp.buckets(same), sp.buckets(same)), (None, 0))

    def test_rank_in_inf_bucket_returns_largest_finite_bound(self):
        p95, n = sp.quantile(0.95, {}, sp.buckets(scrape([("0.1", 1), ("+Inf", 10)])))
        self.assertEqual((p95, n), (0.1, 10))


if __name__ == "__main__":
    unittest.main()
