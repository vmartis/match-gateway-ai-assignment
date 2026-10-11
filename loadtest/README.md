# Load test (k6)

k6 script for AC-57: a constant 300 `POST /v1/verifications`/s for 60 s, spread evenly over the tenants
`bank-alpha`, `bank-beta`, `gov-gamma`, with a unique `Idempotency-Key` per request. k6 runs from the `grafana/k6`
Docker image (D-12), so you don't need a local install.

## Prerequisites

- The stack is running: `docker compose up --build` (gateway + 3 matcher replicas).
- Docker. `python3` is used only once, to generate the gitignored dataset (`dataset/*.jsonl`) when it is missing.

## Run

```
./loadtest/run.sh --smoke   # 10/s for 10 s, checks the wiring
./loadtest/run.sh           # full run: 300/s for 60 s (about 2 min including setup)
```

`run.sh` does the following:

1. It runs `python3 gen_dataset.py` in `dataset/` if the dataset is missing.
2. It mints one token per tenant through `./scripts/token.sh` with scope `enroll verify`. Tokens last 15 min. They are
   never printed, and they reach k6 only as environment variables.
3. It runs the pinned k6 image `grafana/k6:1.8.0@sha256:b992f241…` on the gateway's compose network (normally
   `match-gateway_default`, read from the running gateway container) against `http://gateway:8080`. `loadtest/` and
   `dataset/` are mounted read-only.

What the script does:

- `setup()` enrols the first 100 subjects of each tenant. It expects `201` (re-enrolling also gives `201`) and stops
  the run if any enrolment fails.
- In the `submit` scenario, each iteration picks a pair whose subject was enrolled for that tenant and POSTs it
  (tag `name=submit`).
- 10 % of the iterations then poll `GET /v1/verifications/{id}` every 50 ms (tag `name=poll`) until the status is no
  longer `PENDING`, with a 5 s cap. The time from POST to the final status goes into the `time_to_final_ms` Trend. A
  poll that times out is recorded too.

Output: a short text summary on stdout, plus the full k6 summary in `loadtest/results/summary-<timestamp>.json`.
`results/` holds run output and is git-ignored. After k6, `run.sh` waits for the backlog to drain and prints the
server-side p95 of `decidedAt − createdAt` for the run window (`server_p95.py`: before/after scrape of every matcher replica,
bucket deltas summed and interpolated). Tokens reach the k6 container as environment variables, so `docker inspect` shows
them during a run (dev tool; anyone with Docker socket access is root-equivalent anyway; tokens expire after 15 min). If a
threshold is crossed, k6 exits non-zero. Thresholds never abort the run early.

## Thresholds and NFRs

| Threshold | NFR | Note |
|---|---|---|
| `http_req_failed{name:submit}` rate < 0.001 | NFR-1: 300/s for 60 s, error rate < 0.1 % | Counts only the verification submits (setup enrolments and polling GETs are excluded, code review step 10). The summary also prints accepted submits/s and `dropped_iterations` (non-zero means the target rate was not reached). |
| `http_req_duration{name:submit}` p(99) < 100 ms | NFR-3: POST p99 ≤ 100 ms | |
| `time_to_final_ms` p(95) < 500 ms | NFR-2: p95 POST → final status ≤ 500 ms | Client-side cross-check only. It is inflated by up to one poll interval (50 ms), and the polling adds about 10 % extra GET load. The primary measure is the server-side `decidedAt − createdAt` histogram (D-13, AC-51), printed by `run.sh` from before/after scrapes of every matcher replica's unpublished port 8081 (D-27). |

## Reporting (AC-58)

The full 60 s run happens in ship-check (D-17). Its numbers go into the main `README.md`, together with:

- the machine: CPU, cores, RAM, OS and Docker resource limits
- the NFR-1/2/3 values (k6 summary + server-side p95)
- for each NFR: met or not met, and if not, the reason and the planned change
