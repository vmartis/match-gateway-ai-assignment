# Biometric evaluation (US-14)

`eval.py` runs the synthetic dataset through the running stack and reports matching quality (AC-53..AC-56).
Python 3 standard library only, so there is nothing to install.

## Run

```
docker compose up --build -d          # stack first; the keygen service must have created the issuer keys
python3 eval/eval.py                  # from the repo root
```

Options: `--base-url` (default `http://127.0.0.1:8080`), `--concurrency` (default 32), `--timeout-s` (overall polling
timeout, default 300), `--dataset-dir` (default `dataset`, resolved against the repo root).

What it does:

1. If `dataset/enrollments.jsonl` or `dataset/pairs.jsonl` is missing, it runs `python3 gen_dataset.py` in `dataset/`
   (seed 42, deterministic). Both files are gitignored.
2. It mints one token per tenant (`bank-alpha`, `bank-beta`, `gov-gamma`) with `./scripts/token.sh <tenant> eval "enroll verify"`.
   Tokens live 15 minutes, so they are minted again before each phase and after any `401`.
3. It enrols all 1 000 subjects (`POST /v1/subjects/{id}/enrollment`). Every response must be `201`. Running it again
   adds a new active version with the same template, so the scores stay the same.
4. It submits all 5 000 pairs (`POST /v1/verifications`) with the pair's tenant token and a unique
   `Idempotency-Key: eval-<runid>-<index>`. Every response must be `202`.
5. It polls `GET /v1/verifications/{id}` until every verification is terminal, waiting longer between rounds (up to 2 s).

Connection errors and `502/503/504` are retried a few times. Exit codes: `0` = report printed; `1` = a request failed,
a verification ended `FAILED` or polling timed out (only counts and status codes are printed); `2` = setup error
(dataset generation or token minting).

## Output

Numbers go to stdout and use fixed formatting with no timestamps, so two runs against the same dataset print the
same block. Progress and the elapsed time go to stderr. The output never contains tokens, templates or per-pair data.

- Number of genuine and impostor pairs.
- FMR and FNMR at the default threshold 0.5, with the false-match and false-non-match counts. A pair is a `MATCH` when
  `score >= threshold`.
- Recommended threshold: the lowest observed score `t` with FMR(t) ≤ 0.1 %, plus FMR and FNMR at `t`.
- 95 % one-sided upper bound on the FMR at `t`. With 0 false matches it uses the rule of three (3/n). Otherwise it
  uses the exact Clopper-Pearson bound. About 2 500 impostors allow at most 2 false matches at 0.1 %, so the bound
  matters more than the point estimate.
- Score distributions per label: min, p5, median, p95, max. Percentiles use linear interpolation.
- Notable data: impostors with score ≥ 0.5 (look-alikes) and genuines below the recommended threshold (poor
  captures). A consistency check counts server decisions that disagree with `score >= recorded threshold`, which
  should be 0.
- Per tenant: pairs, genuines, impostors, `MATCH` and `NO_MATCH`, using the server's decision at the tenant's
  threshold.

## Runtime

Expect roughly 1–3 minutes on a laptop. Each of the 9 token mints starts a short `docker compose run` (a few seconds
each). Enrolling 1 000 subjects and submitting 5 000 pairs at concurrency 32 is quick. Polling depends on how fast the
matcher replicas work.

## Tests

```
python3 -m unittest eval/test_eval.py
```

These unit tests cover the pure functions on synthetic scores: FMR/FNMR, tie at the threshold counts as a match, the
threshold sweep, rule of three, Clopper-Pearson against known values, percentiles and report determinism.

## Results

The main README gets the measured numbers. The human or the main session copies them there after a run on the full
stack.
