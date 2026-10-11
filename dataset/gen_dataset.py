#!/usr/bin/env python3
"""Deterministic synthetic face-template dataset for the Match Gateway assignment.

Outputs (in the current directory):
  enrollments.jsonl  {"tenantId","subjectId","template"}            one per subject
  pairs.jsonl        {"tenantId","subjectId","probeTemplate","label"} label = GENUINE | IMPOSTOR

Templates: 128 x float32, little-endian, L2-normalised, base64 encoded (512 bytes before encoding).
Usage: python3 gen_dataset.py [--subjects 1000] [--pairs 5000] [--seed 42]
"""
import argparse, base64, json, random, struct, math

DIM = 128

def norm(v):
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v]

def enc(v):
    return base64.b64encode(struct.pack("<128f", *v)).decode()

def noisy(v, sigma, rnd):
    return norm([x + rnd.gauss(0, sigma) for x in v])

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--subjects", type=int, default=1000)
    ap.add_argument("--pairs", type=int, default=5000)
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    tenants = ["bank-alpha", "bank-beta", "gov-gamma"]

    subjects = []
    ids = {}
    for i in range(a.subjects):
        t = tenants[i % len(tenants)]
        sid = f"subj-{i:05d}"
        base = norm([rnd.gauss(0, 1) for _ in range(DIM)])
        # ~2 % of subjects are "look-alikes" of the previous subject (hard impostors)
        if i > 0 and rnd.random() < 0.02:
            prev = ids[subjects[-1][1]]
            base = norm([0.6 * p + 0.4 * b for p, b in zip(prev, base)])
        ids[sid] = base
        subjects.append((t, sid))

    with open("enrollments.jsonl", "w") as f:
        for t, sid in subjects:
            f.write(json.dumps({"tenantId": t, "subjectId": sid, "template": enc(noisy(ids[sid], 0.03, rnd))}) + "\n")

    with open("pairs.jsonl", "w") as f:
        for k in range(a.pairs):
            t, sid = subjects[rnd.randrange(len(subjects))]
            if rnd.random() < 0.5:
                # genuine: mostly good captures, some poor-quality captures
                sigma = 0.07 if rnd.random() < 0.9 else 0.16
                probe, label = noisy(ids[sid], sigma, rnd), "GENUINE"
            else:
                # impostor: prefer the look-alike neighbour when it exists, otherwise random subject
                idx = next(j for j, s in enumerate(subjects) if s[1] == sid)
                other = subjects[idx + 1][1] if idx + 1 < len(subjects) and rnd.random() < 0.3 else subjects[rnd.randrange(len(subjects))][1]
                if other == sid:
                    other = subjects[(idx + 7) % len(subjects)][1]
                probe, label = noisy(ids[other], 0.07, rnd), "IMPOSTOR"
            f.write(json.dumps({"tenantId": t, "subjectId": sid, "probeTemplate": enc(probe), "label": label}) + "\n")

if __name__ == "__main__":
    main()
