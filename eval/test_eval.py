"""Unit tests for the pure metric functions of eval.py. Run: python3 -m unittest eval/test_eval.py"""
import importlib.util
import math
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("eval_tool", Path(__file__).resolve().parent / "eval.py")
ev = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ev)


class ErrorCountsTest(unittest.TestCase):
    def test_counts_false_matches_and_non_matches(self):
        genuine = [0.9, 0.8, 0.4, 0.3]
        impostor = [0.1, 0.2, 0.6]
        self.assertEqual(ev.error_counts(genuine, impostor, 0.5), (1, 2))

    def test_ties_at_threshold_count_as_match(self):
        self.assertEqual(ev.error_counts([0.5], [0.5], 0.5), (1, 0))

    def test_rates(self):
        fm, fnm = ev.error_counts([0.9, 0.8, 0.4, 0.3], [0.1, 0.2, 0.6, 0.7], 0.5)
        self.assertAlmostEqual(ev.rate(fm, 4), 0.5)
        self.assertAlmostEqual(ev.rate(fnm, 4), 0.5)

    def test_rate_rejects_empty_population(self):
        with self.assertRaises(ValueError):
            ev.rate(0, 0)


class RecommendThresholdTest(unittest.TestCase):
    def test_picks_lowest_threshold_meeting_target(self):
        impostor = [i / 1000 for i in range(1000)]  # 0.000 .. 0.999; target 0.1 % allows 1 false match
        genuine = [0.95, 0.9985, 1.0]
        t, fm, fnm = ev.recommend_threshold(genuine, impostor, 0.001)
        # 0.9985 is the lowest observed score with only one impostor (0.999) at or above it
        self.assertEqual(t, 0.9985)
        self.assertEqual(fm, 1)
        self.assertEqual(fnm, 1)
        self.assertLessEqual(fm / len(impostor), 0.001)
        lower = max(s for s in impostor + genuine if s < t)
        self.assertGreater(ev.error_counts(genuine, impostor, lower)[0] / len(impostor), 0.001)

    def test_tie_at_threshold_is_a_match(self):
        # two impostors share the top score; with 1 allowed error the tie cannot be the threshold
        impostor = [0.1] * 998 + [0.9, 0.9]
        genuine = [0.95]
        t, fm, _ = ev.recommend_threshold(genuine, impostor, 0.001)
        self.assertEqual(t, 0.95)
        self.assertEqual(fm, 0)

    def test_goes_above_maximum_when_no_observed_score_qualifies(self):
        impostor = [0.7, 0.8]
        genuine = [0.6]
        t, fm, fnm = ev.recommend_threshold(genuine, impostor, 0.001)
        self.assertGreater(t, 0.8)
        self.assertEqual((fm, fnm), (0, 1))

    def test_rejects_missing_impostors(self):
        with self.assertRaises(ValueError):
            ev.recommend_threshold([0.9], [], 0.001)


class UpperBoundTest(unittest.TestCase):
    def test_rule_of_three_for_zero_errors(self):
        self.assertAlmostEqual(ev.fmr_upper_bound(0, 2500), 3 / 2500)

    def test_clopper_pearson_one_error_in_2500(self):
        self.assertAlmostEqual(ev.fmr_upper_bound(1, 2500), 0.00190, delta=1e-4)

    def test_clopper_pearson_known_values(self):
        # exact one-sided 95 % upper bounds, cross-checked by numeric Beta(k+1, n-k) quantile integration:
        # k=1,n=10 -> 0.3942; k=5,n=100 -> 0.10225; k=2,n=2500 -> 0.002516
        self.assertAlmostEqual(ev.fmr_upper_bound(1, 10), 0.3942, delta=1e-4)
        self.assertAlmostEqual(ev.fmr_upper_bound(5, 100), 0.10225, delta=1e-4)
        self.assertAlmostEqual(ev.fmr_upper_bound(2, 2500), 0.002516, delta=1e-5)

    def test_bound_is_where_cdf_equals_alpha(self):
        upper = ev.fmr_upper_bound(3, 2500)
        self.assertAlmostEqual(ev.binom_cdf(3, 2500, upper), 0.05, delta=1e-9)

    def test_all_errors_and_invalid_input(self):
        self.assertEqual(ev.fmr_upper_bound(10, 10), 1.0)
        with self.assertRaises(ValueError):
            ev.fmr_upper_bound(0, 0)
        with self.assertRaises(ValueError):
            ev.fmr_upper_bound(3, 2)

    def test_binom_cdf_matches_direct_sum(self):
        n, p = 20, 0.3
        direct = sum(math.comb(n, i) * p ** i * (1 - p) ** (n - i) for i in range(6))
        self.assertAlmostEqual(ev.binom_cdf(5, n, p), direct, places=12)


class PercentileTest(unittest.TestCase):
    def test_linear_interpolation(self):
        self.assertAlmostEqual(ev.percentile([4, 1, 3, 2], 50), 2.5)
        self.assertAlmostEqual(ev.percentile(list(range(101)), 5), 5.0)
        self.assertAlmostEqual(ev.percentile([0.0, 1.0], 95), 0.95)

    def test_extremes_and_single_value(self):
        self.assertEqual(ev.percentile([3, 1, 2], 0), 1)
        self.assertEqual(ev.percentile([3, 1, 2], 100), 3)
        self.assertEqual(ev.percentile([7], 50), 7)

    def test_invalid_input(self):
        with self.assertRaises(ValueError):
            ev.percentile([], 50)
        with self.assertRaises(ValueError):
            ev.percentile([1], 101)


class ReportTest(unittest.TestCase):
    @staticmethod
    def results():
        out = []
        for i in range(200):
            tenant = ev.TENANTS[i % 3]
            if i % 2 == 0:
                score = 0.55 + (i % 40) / 100
                out.append({"tenant": tenant, "label": "GENUINE", "status": "MATCH", "score": score, "threshold": 0.5})
            else:
                score = 0.6 if i == 1 else (i % 30) / 100
                status = "MATCH" if score >= 0.5 else "NO_MATCH"
                out.append({"tenant": tenant, "label": "IMPOSTOR", "status": status, "score": score, "threshold": 0.5})
        return out

    def test_report_is_deterministic_and_order_independent(self):
        rs = self.results()
        self.assertEqual(ev.build_report(rs), ev.build_report(list(reversed(rs))))

    def test_report_contains_expected_numbers(self):
        text = "\n".join(ev.build_report(self.results()))
        self.assertIn("genuines: 100  impostors: 100", text)
        self.assertIn("(1 false matches / 100 impostors)", text)
        self.assertIn("look-alikes): 1", text)
        self.assertIn("rule of three", text)
        self.assertIn("server decisions inconsistent with score >= recorded threshold: 0", text)


class TokenStoreTest(unittest.TestCase):
    def test_refresh_remints_once_per_stale_token(self):
        minted = []

        def minter(tenant):
            minted.append(tenant)
            return f"{tenant}.tok.{len(minted)}"

        store = ev.TokenStore(["a"], minter)
        store.refresh_all()
        stale = store.get("a")
        fresh = store.refresh("a", stale)
        self.assertNotEqual(stale, fresh)
        self.assertEqual(store.refresh("a", stale), fresh)  # a second caller with the same stale token reuses it
        self.assertEqual(len(minted), 2)


class NoRedirectTest(unittest.TestCase):
    def test_redirect_is_not_followed_so_the_token_never_leaves(self):
        import http.server
        import threading

        seen = []

        class Target(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                seen.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        target = http.server.HTTPServer(("127.0.0.1", 0), Target)

        class Redirector(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{target.server_port}/stolen")
                self.end_headers()

            def log_message(self, *args):
                pass

        source = http.server.HTTPServer(("127.0.0.1", 0), Redirector)
        for server in (target, source):
            threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            client = ev.Client(f"http://127.0.0.1:{source.server_port}", {})
            status, _ = client._send("GET", "/x", "secret-token", None, {})
        finally:
            source.shutdown()
            target.shutdown()
        self.assertEqual(status, 302)
        self.assertEqual(seen, [])


if __name__ == "__main__":
    unittest.main()
