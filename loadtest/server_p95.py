#!/usr/bin/env python3
"""Server-side p95 of decidedAt - createdAt for one load-test window (AC-57, D-13).

Usage: server_p95.py BEFORE.txt AFTER.txt
Each file holds the concatenated Prometheus scrapes of all matcher replicas. The histogram is cumulative since each
replica started, so the window is AFTER - BEFORE per bucket, summed over replicas; p95 is interpolated linearly inside
its bucket (as PromQL histogram_quantile does).
"""
import math
import re
import sys
from collections import defaultdict

BUCKET = re.compile(r'^matchgw_verification_latency_seconds_bucket\{(?:[^}]*,)?le="([^"]+)"[^}]*\}\s+(\S+)')


def buckets(text):
    sums = defaultdict(float)
    for line in text.splitlines():
        m = BUCKET.match(line)
        if m:
            sums[float(m.group(1))] += float(m.group(2))
    return sums


def quantile(q, before, after):
    """Returns (quantile in seconds or None, sample count) of the window between two cumulative bucket sets."""
    bounds = sorted(after)
    delta = [(le, after[le] - before.get(le, 0.0)) for le in bounds]
    total = delta[-1][1] if delta else 0.0
    if total <= 0:
        return None, 0
    rank = q * total
    prev_le, prev_count = 0.0, 0.0
    for le, count in delta:
        if count >= rank:
            if math.isinf(le):
                return prev_le, int(total)
            return prev_le + (le - prev_le) * (rank - prev_count) / max(count - prev_count, 1e-12), int(total)
        prev_le, prev_count = le, count
    return prev_le, int(total)


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1]) as b, open(argv[2]) as a:
        p95, n = quantile(0.95, buckets(b.read()), buckets(a.read()))
    if p95 is None:
        print("  server-side p95 of decidedAt - createdAt: no decisions in the window")
    else:
        print(f"  server-side p95 of decidedAt - createdAt (matcher histogram, {n} decisions): {p95 * 1000:.1f} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
