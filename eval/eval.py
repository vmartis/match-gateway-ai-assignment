#!/usr/bin/env python3
"""Biometric evaluation of the running Match Gateway (US-14, AC-53..AC-56, D-12).

Enrols every subject of dataset/enrollments.jsonl through the API, submits every pair of dataset/pairs.jsonl as a
verification, polls until all are terminal and prints FMR/FNMR, a recommended threshold for FMR <= 0.1 % and score
statistics. Python 3 standard library only.

Never prints tokens, templates or per-pair data. Numbers go to stdout (deterministic); progress and timings to stderr.

Exit codes: 0 = report printed; 1 = a request failed, a verification ended FAILED or polling timed out;
2 = setup error (dataset, token minting).
"""
import argparse
import bisect
import concurrent.futures
import json
import math
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TENANTS = ("bank-alpha", "bank-beta", "gov-gamma")
DEFAULT_THRESHOLD = 0.5
TARGET_FMR = 0.001
CONFIDENCE = 0.95
TERMINAL = ("MATCH", "NO_MATCH", "FAILED")
RETRYABLE_STATUS = (502, 503, 504)
CONNECT_RETRIES = 4


# --------------------------------------------------------------------------------------------------------------------
# Pure metric functions (unit-tested in test_eval.py)
# --------------------------------------------------------------------------------------------------------------------

def error_counts(genuine, impostor, threshold):
    """(false matches, false non-matches) at threshold; MATCH iff score >= threshold."""
    false_matches = sum(1 for s in impostor if s >= threshold)
    false_non_matches = sum(1 for s in genuine if s < threshold)
    return false_matches, false_non_matches


def rate(errors, total):
    if total <= 0:
        raise ValueError("rate needs a positive total")
    return errors / total


def recommend_threshold(genuine, impostor, target_fmr=TARGET_FMR):
    """Lowest threshold t among the observed scores (plus one value just above the maximum) with FMR(t) <= target.

    MATCH iff score >= t, so ties at t count as matches. Every t in (s_k, s_k+1] yields the same rates, so picking
    observed scores loses nothing. Returns (t, false_matches, false_non_matches).
    """
    if not impostor:
        raise ValueError("no impostor scores")
    imp = sorted(impostor)
    gen = sorted(genuine)
    allowed = math.floor(target_fmr * len(imp) + 1e-9)
    candidates = sorted(set(imp) | set(gen))
    candidates.append(math.nextafter(candidates[-1], math.inf))
    for t in candidates:
        fm = len(imp) - bisect.bisect_left(imp, t)
        if fm <= allowed:
            return t, fm, bisect.bisect_left(gen, t)
    raise AssertionError("unreachable: the last candidate has no false matches")


def _log_binom_pmf(k, n, p):
    return math.log(math.comb(n, k)) + k * math.log(p) + (n - k) * math.log1p(-p)


def binom_cdf(k, n, p):
    """P(X <= k) for X ~ Binomial(n, p), summed in log space for stability."""
    if p <= 0.0:
        return 1.0
    if p >= 1.0:
        return 1.0 if k >= n else 0.0
    return min(1.0, sum(math.exp(_log_binom_pmf(i, n, p)) for i in range(0, k + 1)))


def fmr_upper_bound(errors, trials, confidence=CONFIDENCE):
    """One-sided upper confidence bound on an error rate.

    0 errors: rule of three (3/n, approx. 95 %). Otherwise exact Clopper-Pearson: the p with P(X <= k; n, p) = 1 - conf,
    found by bisection (the CDF decreases in p).
    """
    if trials <= 0:
        raise ValueError("trials must be positive")
    if errors < 0 or errors > trials:
        raise ValueError("errors out of range")
    if errors == 0:
        return min(1.0, 3.0 / trials)
    if errors == trials:
        return 1.0
    alpha = 1.0 - confidence
    lo, hi = errors / trials, 1.0
    for _ in range(200):
        mid = (lo + hi) / 2
        if binom_cdf(errors, trials, mid) > alpha:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def percentile(values, q):
    """Linear-interpolation percentile (numpy default), q in [0, 100]."""
    if not values:
        raise ValueError("no values")
    if not 0 <= q <= 100:
        raise ValueError("q out of range")
    v = sorted(values)
    pos = (len(v) - 1) * q / 100
    lo = math.floor(pos)
    hi = math.ceil(pos)
    return v[lo] + (v[hi] - v[lo]) * (pos - lo)


def distribution(values):
    return {
        "min": min(values),
        "p5": percentile(values, 5),
        "median": percentile(values, 50),
        "p95": percentile(values, 95),
        "max": max(values),
    }


def build_report(results):
    """results: list of dicts {tenant, label, status, score, threshold}. Returns the report as a list of lines."""
    genuine = sorted(r["score"] for r in results if r["label"] == "GENUINE")
    impostor = sorted(r["score"] for r in results if r["label"] == "IMPOSTOR")
    if not genuine or not impostor:
        raise ValueError("need genuine and impostor scores")
    n_gen, n_imp = len(genuine), len(impostor)
    fm, fnm = error_counts(genuine, impostor, DEFAULT_THRESHOLD)
    t, fm_t, fnm_t = recommend_threshold(genuine, impostor, TARGET_FMR)
    upper = fmr_upper_bound(fm_t, n_imp, CONFIDENCE)
    upper_method = "rule of three" if fm_t == 0 else "Clopper-Pearson"
    lookalikes = sum(1 for s in impostor if s >= DEFAULT_THRESHOLD)
    poor = sum(1 for s in genuine if s < t)
    server_mismatch = sum(
        1 for r in results if (r["status"] == "MATCH") != (r["score"] >= r["threshold"])
    )

    lines = [
        "== Match Gateway evaluation ==",
        f"pairs: {len(results)}  genuines: {n_gen}  impostors: {n_imp}",
        "",
        f"-- at default threshold {DEFAULT_THRESHOLD:.6f} (MATCH iff score >= threshold)",
        f"FMR : {_pct(rate(fm, n_imp))}  ({fm} false matches / {n_imp} impostors)",
        f"FNMR: {_pct(rate(fnm, n_gen))}  ({fnm} false non-matches / {n_gen} genuines)",
        "",
        f"-- recommended threshold (lowest observed score with FMR <= {_pct(TARGET_FMR)})",
        f"threshold: {t:.6f}",
        f"FMR : {_pct(rate(fm_t, n_imp))}  ({fm_t} false matches / {n_imp} impostors)",
        f"FNMR: {_pct(rate(fnm_t, n_gen))}  ({fnm_t} false non-matches / {n_gen} genuines)",
        f"FMR {CONFIDENCE:.0%} one-sided upper bound: {_pct(upper)}  ({upper_method}, n={n_imp})",
        "",
        "-- score distributions",
        f"{'label':<9} {'n':>5} {'min':>9} {'p5':>9} {'median':>9} {'p95':>9} {'max':>9}",
    ]
    for label, values in (("GENUINE", genuine), ("IMPOSTOR", impostor)):
        d = distribution(values)
        lines.append(
            f"{label:<9} {len(values):>5} {d['min']:>9.6f} {d['p5']:>9.6f} {d['median']:>9.6f} "
            f"{d['p95']:>9.6f} {d['max']:>9.6f}"
        )
    lines += [
        "",
        "-- notable",
        f"impostors with score >= {DEFAULT_THRESHOLD:.6f} (look-alikes): {lookalikes}",
        f"genuines below the recommended threshold (poor captures): {poor}",
        f"server decisions inconsistent with score >= recorded threshold: {server_mismatch}",
        "",
        "-- per tenant",
        f"{'tenant':<12} {'pairs':>6} {'genuine':>8} {'impostor':>9} {'MATCH':>6} {'NO_MATCH':>9}",
    ]
    for tenant in sorted({r["tenant"] for r in results}):
        rs = [r for r in results if r["tenant"] == tenant]
        lines.append(
            f"{tenant:<12} {len(rs):>6} {sum(r['label'] == 'GENUINE' for r in rs):>8} "
            f"{sum(r['label'] == 'IMPOSTOR' for r in rs):>9} {sum(r['status'] == 'MATCH' for r in rs):>6} "
            f"{sum(r['status'] == 'NO_MATCH' for r in rs):>9}"
        )
    return lines


def _pct(x):
    return f"{x * 100:.4f} %"


# --------------------------------------------------------------------------------------------------------------------
# Tokens, HTTP, dataset
# --------------------------------------------------------------------------------------------------------------------

class SetupError(Exception):
    pass


def mint_token(tenant):
    """Mints a 15-minute token via the dev script. Never prints the token or the script output."""
    proc = subprocess.run(
        ["./scripts/token.sh", tenant, "eval", "enroll verify"],
        cwd=REPO_ROOT, capture_output=True, text=True, timeout=120,
    )
    if proc.returncode != 0:
        raise SetupError(f"token minting for {tenant} failed (exit code {proc.returncode})")
    lines = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip()]
    token = lines[-1] if lines else ""
    if token.count(".") != 2:
        raise SetupError(f"token minting for {tenant} returned no JWT")
    return token


class TokenStore:
    """One token per tenant; re-minted per phase and after a 401 (thread-safe, one re-mint per stale token)."""

    def __init__(self, tenants, minter=mint_token):
        self._tenants = tuple(tenants)
        self._minter = minter
        self._lock = threading.Lock()
        self._tokens = {}

    def refresh_all(self):
        with self._lock:
            self._tokens = {t: self._minter(t) for t in self._tenants}

    def get(self, tenant):
        with self._lock:
            return self._tokens[tenant]

    def refresh(self, tenant, stale):
        with self._lock:
            if self._tokens.get(tenant) == stale:
                self._tokens[tenant] = self._minter(tenant)
            return self._tokens[tenant]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never follow redirects: urllib would forward the Authorization header to the redirect target (security review)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


class Client:
    def __init__(self, base_url, tokens, request_timeout_s=30):
        self.base_url = base_url.rstrip("/")
        self.tokens = tokens
        self.request_timeout_s = request_timeout_s

    def _send(self, method, path, token, body, headers):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + token)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in headers.items():
            req.add_header(k, v)
        try:
            with _OPENER.open(req, timeout=self.request_timeout_s) as resp:
                return resp.status, _parse_json(resp.read())
        except urllib.error.HTTPError as e:
            e.read()
            return e.code, None

    def call(self, method, path, tenant, body=None, headers=None):
        """Returns (status, json). Retries connection errors and 502/503/504; re-mints the token once on 401."""
        headers = headers or {}
        token = self.tokens.get(tenant)
        reminted = False
        attempt = 0
        while True:
            try:
                status, payload = self._send(method, path, token, body, headers)
            except (urllib.error.URLError, ConnectionError, TimeoutError, OSError):
                status, payload = None, None
            if status == 401 and not reminted:
                token = self.tokens.refresh(tenant, token)
                reminted = True
                continue
            if (status is None or status in RETRYABLE_STATUS) and attempt < CONNECT_RETRIES:
                attempt += 1
                time.sleep(0.2 * 2 ** attempt)
                continue
            return status, payload


def _parse_json(raw):
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def load_jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def ensure_dataset(dataset_dir):
    enr, pairs = dataset_dir / "enrollments.jsonl", dataset_dir / "pairs.jsonl"
    if not (enr.exists() and pairs.exists()):
        log(f"generating dataset in {dataset_dir} (seed 42)")
        proc = subprocess.run([sys.executable, "gen_dataset.py"], cwd=dataset_dir, capture_output=True)
        if proc.returncode != 0:
            raise SetupError(f"gen_dataset.py failed (exit code {proc.returncode})")
    return load_jsonl(enr), load_jsonl(pairs)


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def status_summary(statuses):
    counts = {}
    for s in statuses:
        key = "connection error" if s is None else str(s)
        counts[key] = counts.get(key, 0) + 1
    return ", ".join(f"{k}: {counts[k]}" for k in sorted(counts))


# --------------------------------------------------------------------------------------------------------------------
# Phases
# --------------------------------------------------------------------------------------------------------------------

def enrol_all(client, enrollments, concurrency):
    def one(e):
        status, _ = client.call("POST", f"/v1/subjects/{e['subjectId']}/enrollment", e["tenantId"],
                                {"template": e["template"]})
        return status

    with concurrent.futures.ThreadPoolExecutor(concurrency) as pool:
        statuses = list(pool.map(one, enrollments))
    bad = [s for s in statuses if s != 201]
    log(f"enrol: {len(statuses) - len(bad)}/{len(statuses)} returned 201")
    if bad:
        log(f"enrol errors by status: {status_summary(bad)}")
        return False
    return True


def submit_all(client, pairs, concurrency, run_id):
    def one(indexed):
        i, p = indexed
        status, payload = client.call(
            "POST", "/v1/verifications", p["tenantId"],
            {"subjectId": p["subjectId"], "probeTemplate": p["probeTemplate"]},
            {"Idempotency-Key": f"eval-{run_id}-{i}"},
        )
        vid = payload.get("verificationId") if status in (200, 202) and isinstance(payload, dict) else None
        return status, vid

    with concurrent.futures.ThreadPoolExecutor(concurrency) as pool:
        out = list(pool.map(one, enumerate(pairs)))
    # 200 is a legitimate idempotent replay (AC-18) after a retried POST whose first attempt was committed.
    bad = [s for s, vid in out if s not in (200, 202) or not vid]
    log(f"submit: {len(out) - len(bad)}/{len(out)} returned 202")
    if bad:
        log(f"submit errors by status: {status_summary(bad)}")
        return None
    return [vid for _, vid in out]


def poll_all(client, pairs, ids, concurrency, deadline):
    """Returns ({index: response json}, errors). Polls only pending ids, with growing delay between rounds."""
    done = {}
    pending = list(range(len(ids)))
    delay = 0.2
    errors = []

    def one(i):
        status, payload = client.call("GET", f"/v1/verifications/{ids[i]}", pairs[i]["tenantId"])
        return i, status, payload

    with concurrent.futures.ThreadPoolExecutor(concurrency) as pool:
        while pending and time.monotonic() < deadline:
            still = []
            for i, status, payload in pool.map(one, pending):
                if status == 200 and isinstance(payload, dict) and payload.get("status") in TERMINAL:
                    done[i] = payload
                elif status == 200:
                    still.append(i)
                else:
                    errors.append(status)
            pending = still
            log(f"poll: {len(done)}/{len(ids)} terminal")
            if pending:
                time.sleep(min(delay, max(0.0, deadline - time.monotonic())))
                delay = min(delay * 2, 2.0)
    return done, pending, errors


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--concurrency", type=int, default=32)
    ap.add_argument("--timeout-s", type=float, default=300, help="overall polling timeout in seconds")
    ap.add_argument("--dataset-dir", default="dataset", help="relative paths resolve against the repo root")
    a = ap.parse_args(argv)
    if a.concurrency < 1 or a.timeout_s <= 0:
        ap.error("concurrency and timeout must be positive")

    dataset_dir = Path(a.dataset_dir)
    if not dataset_dir.is_absolute():
        dataset_dir = REPO_ROOT / dataset_dir
    run_id = uuid.uuid4().hex[:12]
    started = time.monotonic()
    try:
        enrollments, pairs = ensure_dataset(dataset_dir)
        tenants = sorted({e["tenantId"] for e in enrollments} | {p["tenantId"] for p in pairs})
        tokens = TokenStore(tenants)
        client = Client(a.base_url, tokens)
        log(f"run {run_id}: {len(enrollments)} subjects, {len(pairs)} pairs, tenants {', '.join(tenants)}")

        tokens.refresh_all()
        if not enrol_all(client, enrollments, a.concurrency):
            return 1

        tokens.refresh_all()
        ids = submit_all(client, pairs, a.concurrency, run_id)
        if ids is None:
            return 1

        tokens.refresh_all()
        done, pending, errors = poll_all(client, pairs, ids, a.concurrency, time.monotonic() + a.timeout_s)
    except SetupError as e:
        log(f"setup error: {e}")
        return 2
    log(f"elapsed: {time.monotonic() - started:.1f} s")

    failed = [d.get("reason") or "unknown" for d in done.values() if d["status"] == "FAILED"]
    if errors or pending or failed:
        print(f"verifications: {len(ids)}  terminal: {len(done)}  timed out: {len(pending)}  "
              f"poll errors: {len(errors)}  FAILED: {len(failed)}")
        if errors:
            print(f"poll errors by status: {status_summary(errors)}")
        for reason in sorted(set(failed)):
            print(f"FAILED reason {reason}: {failed.count(reason)}")
        return 1

    results = [
        {
            "tenant": pairs[i]["tenantId"],
            "label": pairs[i]["label"],
            "status": d["status"],
            "score": float(d["score"]),
            "threshold": float(d["threshold"]),
        }
        for i, d in sorted(done.items())
    ]
    print("\n".join(build_report(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
