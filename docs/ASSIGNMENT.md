# Take-home assignment: Match Gateway

**Time box:** 3 hours. You are expected to use AI coding agents. Prioritise; a smaller, verified and secure solution beats a larger unverified one.
**Deliverable:** a git repository we can run with `docker compose up --build`, plus a README.

## Context

Our customers (banks and government agencies, called *tenants*) run our mobile SDK, which turns a face image into a **template**:
a vector of 128 `float32` values, L2-normalised, sent as base64 (exactly 512 bytes before encoding, little-endian).
Tenants enrol a template for each of their end users (*subjects*). Later they ask us to verify a fresh template (*probe*) against the enrolled one (1:1 verification).

Traffic is bursty: at the start of business hours a large bank can send hundreds of verification requests per second for a few minutes.
Verification must not block the public API, and the system must scale horizontally.

## What to build

At least **two separately deployable applications** plus infrastructure, all started by `docker compose up --build`:

### 1. `gateway` – public REST API

All endpoints except health require a **JWT bearer token** (RS256). Required claims: `sub`, `tenant_id`, `scope`
(space-separated, values `enroll`, `verify`, `admin`), `exp`. For local testing, provide a way to obtain tokens
(your own token endpoint or a dev script). Every operation is scoped to the caller's `tenant_id`.

| Method & path | Scope | Behaviour |
|---|---|---|
| `POST /v1/subjects/{subjectId}/enrollment` | `enroll` | Body `{"template": "<base64>"}`. Creates or replaces the subject's active template. Replacing keeps the previous version inactive. Returns `201` with `{"subjectId","version"}`. |
| `DELETE /v1/subjects/{subjectId}` | `admin` | Right to erasure: removes all template versions of the subject and its verification probes. Verification results stay, without template data. Returns `204`, or `404` if unknown. |
| `POST /v1/verifications` | `verify` | Header `Idempotency-Key` (required, max 64 chars). Body `{"subjectId","probeTemplate"}`. Returns `202` with `{"verificationId","status":"PENDING"}` and a `Location` header. |
| `GET /v1/verifications/{verificationId}` | `verify` | Returns `{"verificationId","subjectId","status","score","threshold","decidedAt"}`. `status` is one of `PENDING`, `MATCH`, `NO_MATCH`, `FAILED`. |
| `PUT /v1/tenant/threshold` | `admin` | Body `{"threshold": 0.0–1.0}`. Sets the tenant's decision threshold. Default `0.5`. |

Rules:

- **Template validation:** the template must decode to exactly 512 bytes, all values finite, L2 norm in `[0.99, 1.01]`. Otherwise return `400` (RFC 9457 problem detail).
- **Tenant isolation:** a tenant must never see, verify against or delete another tenant's subjects or verifications. Use `404`, not `403`, for other tenants' resources.
- **Idempotency:** the same `Idempotency-Key` with the same body within 24 h returns the original `verificationId` (`200` or `202`). The same key with a different body returns `422`.
- **Verification of an unknown subject:** accepted (`202`), then finishes with `FAILED` and `reason: SUBJECT_NOT_ENROLLED`.
- **Rate limiting** per tenant, default 300 requests/s (configurable). Excess requests get `429` with `Retry-After`.
- Template data and tokens must never appear in logs or error responses.

### 2. `matcher` – verification worker

- Consumes pending verifications, loads the subject's active template, computes **cosine similarity** (`score`) and decides `MATCH` if `score >= threshold` of the tenant at decision time.
- Runs as **3 replicas** in docker compose. A verification must be decided **exactly once from the client's point of view**: no duplicate or conflicting results even if a replica crashes mid-processing.
- If a replica dies while holding a job, the job must be retried and decided within 30 s.
- A job that fails 3 times ends as `FAILED` with a reason.

### 3. Infrastructure

- PostgreSQL for persistent state.
- The transport between gateway and matcher is **your choice** (PostgreSQL `SKIP LOCKED` queue, Redis Streams, RabbitMQ, Kafka, …). Justify it in an ADR, including how you guarantee the exactly-once outcome.

## Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | Sustain **300 verifications/s for 60 s** on a developer laptop (docker compose, 3 matcher replicas), error rate < 0.1 %. |
| NFR-2 | p95 time from `POST /v1/verifications` to final status ≤ **500 ms** under NFR-1 load. |
| NFR-3 | `POST /v1/verifications` p99 ≤ **100 ms** under NFR-1 load. |
| NFR-4 | `/actuator/health` on both apps, plus metrics: verifications by result, queue depth or lag, processing duration. |
| NFR-5 | Templates are biometric data (special category under GDPR). Store only what is needed; templates at rest should be encrypted (Should). |

Provide a **load test** (k6, Gatling or similar) under `loadtest/` and put the measured numbers in the README, including the machine you ran it on.
If you do not meet an NFR, say so and explain why and what you would change.

## Evaluation data

`dataset/gen_dataset.py` generates synthetic data (`python3 gen_dataset.py`; deterministic, seed 42):

- `enrollments.jsonl`: 1 000 subjects across 3 tenants
- `pairs.jsonl`: 5 000 labelled probes (`GENUINE` / `IMPOSTOR`)

Provide an **eval tool** (any language) that enrols the subjects through the API, runs all pairs through the running system and reports:

- FMR (false match rate) and FNMR (false non-match rate) at the default threshold,
- a recommended threshold for FMR ≤ 0.1 % and the resulting FNMR,
- anything notable you observe in the data.

## Priorities

| Priority | Items |
|---|---|
| Must | Gateway endpoints with JWT and tenant isolation, template validation, async verification through matcher replicas, exactly-once outcome, docker compose, automated tests, eval tool with FMR/FNMR |
| Should | Idempotency, rate limiting, crash-recovery test, load test with measured numbers, erasure endpoint, encryption at rest |
| Could | Long-poll or webhook notification of results, Grafana dashboard, per-tenant metrics |

## What we look at

- Correctness under concurrency, and how you **proved** it (tests, not claims).
- Security of a multi-tenant biometric API.
- Your decisions and trade-offs (ADRs, README).
- How you used AI agents and how you verified their output.

In the README, list known limitations and what you would do next for production.
