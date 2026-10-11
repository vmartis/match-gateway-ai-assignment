# SPEC – Match Gateway

> **Draft** written by /spec on 2026-10-11 from docs/ASSIGNMENT.md and docs/analysis/ (LIGHT route, D-1). Human-owned: review and promote with
> `cp docs/SPEC.draft.md docs/SPEC.md`; Claude never edits docs/SPEC.md.
> Every AC links its story (US-x, docs/analysis/BACKLOG.md) and quotes its source `[A: "…"]` (verbatim, docs/ASSIGNMENT.md) or a decision `[D-x]`
> (docs/analysis/DECISIONS.md); anything else is marked ASSUMPTION or INFERRED. BR/NFR/INV/MC ids refer to docs/analysis/ANALYSIS.md.
> Scope = D-2 as planned in D-17: all 15 backlog stories. US-5, US-13, US-15 are built after the MVP and are first in the cut order (D-17, BACKLOG "Proposals" 5).

## Goal

A multi-tenant, asynchronous 1:1 face-verification service: tenants enrol templates and request verifications through a JWT-protected
REST gateway; a horizontally scaled matcher decides each verification **exactly once**, isolated per tenant, without leaking biometric data
or tokens. Everything starts with `docker compose up --build`; accuracy (FMR/FNMR) and load numbers are measured and reported honestly.
[A: "Verification must not block the public API, and the system must scale horizontally."]
[A: "no duplicate or conflicting results even if a replica crashes mid-processing."]
[A: "Prioritise; a smaller, verified and secure solution beats a larger unverified one."]

## Actors

| Actor | Description | Source |
|---|---|---|
| Tenant backend | Machine caller holding a JWT with `tenant_id` and `scope` (`enroll`, `verify`, `admin`) | [A: "Required claims: `sub`, `tenant_id`, `scope`"] |
| Subject | Tenant's end user whose template is enrolled; never calls the API | INFERRED (ANALYSIS §2) |
| Matcher replica | Worker deciding pending verifications; 3 replicas | [A: "Runs as **3 replicas** in docker compose."] |
| Operator / evaluator | Starts the stack, mints dev tokens, runs eval tool and load test | INFERRED (ANALYSIS §2) |
| Attacker | Other tenant, holder of a stolen/forged token, malicious client (MC-1..MC-8) | ANALYSIS §2 |

## Deployables and API summary

- `gateway` (public REST API), `matcher` (worker, 3 replicas), PostgreSQL, RabbitMQ; transport = RabbitMQ quorum queue fed by a transactional outbox, outcome guarded in PostgreSQL [D-28, ADR-009; supersedes D-3]; Gradle modules
  `gateway`, `matcher`, `common` [D-7]; tokens minted by a dev script [D-4].

| Method & path | Scope | Success | Errors |
|---|---|---|---|
| `POST /v1/subjects/{subjectId}/enrollment` | `enroll` | `201` `{"subjectId","version"}` | 400, 401, 403, 413, 415 |
| `DELETE /v1/subjects/{subjectId}` | `admin` | `204` | 400 (malformed id, D-26), 401, 403, 404 |
| `POST /v1/verifications` (header `Idempotency-Key`) | `verify` | `202` `{"verificationId","status":"PENDING"}` + `Location`; replay `202`/`200` | 400, 401, 403, 413, 415, 422 |
| `GET /v1/verifications/{verificationId}` | `verify` | `200` `{"verificationId","subjectId","status","score","threshold","decidedAt"}` (+ `reason` when FAILED) | 401, 403, 404 |
| `PUT /v1/tenant/threshold` | `admin` | `200` `{"threshold"}` (ASSUMPTION A-S1) | 400, 401, 403 |
| `GET /actuator/health` (both apps) | none | `200` `{"status":"UP"}` | – |
| `GET /actuator/prometheus` (management port, compose network only) | none | Prometheus text | not served on the public port [D-6] |

All errors are RFC 9457 problem details (`application/problem+json`) with no stack trace, exception class, SQL, token or template data.

## Acceptance criteria

### US-1 Run everything with one command (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-1 | A fresh clone, no keys, no DB volume, no local JDK | `docker compose up --build` | `postgres`, `gateway` and 3 `matcher` replicas are healthy within 3 min; no manual step; no secret or key tracked by git | [A: "all started by `docker compose up --build`"] [A: "Runs as **3 replicas** in docker compose."] NFR-11 |
| AC-2 | The stack is running | `GET /actuator/health` without a token on the gateway and on each matcher | `200` with `"status":"UP"` | [A: "`/actuator/health` on both apps, plus metrics: verifications by result, queue depth or lag, processing duration."] |
| AC-3 | An empty database | The gateway starts (matchers start concurrently) | Schema migrations are applied exactly once, owned by the gateway; matchers do not fail or migrate | [D-7] |
| AC-4 | The repository | `./scripts/verify.sh` | Builds `gateway`, `matcher`, `common`; runs `ktlintCheck` and all tests incl. PostgreSQL integration tests; exits 0 and prints `VERIFY OK` | [A: "automated tests"] CLAUDE.md §6 |

### US-2 Authentication and scopes (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-5 | The stack is up | The dev token script is run with `tenant_id=bank-alpha`, `sub=client-1`, `scope="enroll verify"` | It prints an RS256 JWT containing `sub`, `tenant_id`, `scope`, `exp`, `iss`, `aud`, `nbf`, `iat`, `jti` with `exp − iat ≤ 900 s`; the signing key was generated at startup into a shared volume; the gateway holds only the public key; no key file is tracked by git | [A: "(your own token endpoint or a dev script)"] [D-4] NFR-10 |
| AC-6 | Any `/v1/**` endpoint | The token is missing, malformed, expired by more than 60 s, `alg=none`, HS256-signed with the public key as secret, signed by a different RSA key, has a wrong `iss` or `aud`, or lacks `tenant_id` | `401` with `WWW-Authenticate: Bearer…` and a generic problem detail that does not contain the token or the reason for rejection | [A: "All endpoints except health require a **JWT bearer token** (RS256)."] MC-2 |
| AC-7 | A valid token whose `scope` lacks the endpoint's scope (`verify`-only on enrolment, `enroll`-only on threshold, `verify`-only on delete) | The request is sent | `403` problem detail; no state changes | [D-5] BR-1 |
| AC-8 | A valid token with `scope="enroll verify admin"` | Any `/v1` endpoint is called | Authorization passes; `scope` is parsed space-separated | [A: "(space-separated, values `enroll`, `verify`, `admin`)"] [D-5] |

### US-3 Enrol / replace a template (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-9 | Tenant T, subject `subj-001` not enrolled | `POST /v1/subjects/subj-001/enrollment` with `{"template":"<base64 of 512 bytes = 128 finite little-endian float32, L2 norm 1.0>"}` | `201` `{"subjectId":"subj-001","version":1}` | [A: "Returns `201` with `{"subjectId","version"}`."] |
| AC-10 | `subj-001` has active version 1 | A second valid template is enrolled | `201` with `"version":2`; version 2 active; version 1 kept inactive; later decisions score against version 2 | [A: "Creates or replaces the subject's active template. Replacing keeps the previous version inactive."] |
| AC-11 | An enrolment request | `template` is missing or null, not valid standard base64, decodes to 511 or 513 bytes, contains NaN or ±Infinity, or has L2 norm 0.98 or 1.02 | `400` problem detail; no version created; body does not contain the submitted base64. Boundaries: norm 0.99 and 1.01 → `201` | [A: "the template must decode to exactly 512 bytes, all values finite, L2 norm in `[0.99, 1.01]`"] [A: "Otherwise return `400` (RFC 9457 problem detail)."] |
| AC-12 | An enrolment request | Path `subjectId` has 65 chars or a char outside `[A-Za-z0-9._-]` (`a b`, `a%2Fb`, `ä`) | `400` problem detail; a 64-char id is accepted | [D-15] |
| AC-13 | Subject `s1` of tenant T | Two enrolments are committed concurrently | Both `201` with distinct versions; exactly one version is active (enforced by a DB uniqueness guarantee) | INV-2 [D-17] |

### US-4 Submit a verification (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-14 | T has enrolled `subj-001` | `POST /v1/verifications`, `Idempotency-Key: k-1`, `{"subjectId":"subj-001","probeTemplate":"<valid>"}` | `202` `{"verificationId":"<random UUID>","status":"PENDING"}` and `Location: /v1/verifications/{verificationId}`; no matcher decision is awaited | [A: "Returns `202` with `{"verificationId","status":"PENDING"}` and a `Location` header."] [D-16] |
| AC-15 | A valid body | `Idempotency-Key` missing, empty or 65 chars | `400` problem detail; nothing created; a 64-char key is accepted | [A: "Header `Idempotency-Key` (required, max 64 chars)"] |
| AC-16 | A valid key | `probeTemplate` violates AC-11 or `subjectId` violates AC-12 | `400` problem detail without the submitted base64; nothing created | BR-3 (INFERRED: rule applies to probes) |
| AC-17 | `ghost` is not enrolled for T | A valid verification is posted | `202 PENDING`; it later ends `"status":"FAILED","reason":"SUBJECT_NOT_ENROLLED"`, `score` null | [A: "accepted (`202`), then finishes with `FAILED` and `reason: SUBJECT_NOT_ENROLLED`."] [D-9] |

### US-5 Idempotency (Should, after MVP)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-18 | T posted `k-1` with body B and got V | Same key + identical B within 24 h | Returns V: `202` while PENDING, `200` once decided; no second verification | [A: "the same `Idempotency-Key` with the same body within 24 h returns the original `verificationId` (`200` or `202`)."] [D-10] |
| AC-19 | T used `k-1` with body B | `k-1` with a different `subjectId` or `probeTemplate` | `422` problem detail; nothing created | [A: "The same key with a different body returns `422`."] |
| AC-20 | Tenant A used `k-1` | Tenant B posts `k-1` | B gets its own new `verificationId` (`202`), never A's; A's record unchanged | INV-6, MC-4 |
| AC-21 | New key `k-par` | 10 identical requests are sent concurrently | All responses carry the same `verificationId`; exactly one verification exists (DB uniqueness guarantee) | INV-6 [D-17] |
| AC-22 | `k-1` first used > 24 h ago (injected clock) | `k-1` is reused | Treated as new: new `verificationId` | [A: "within 24 h"] (INFERRED from L37) |
| AC-23 | Any stored idempotency record | Persisted state is inspected | It holds a keyed hash (HMAC) of (subjectId, probe bytes), never the raw probe | [D-10] NFR-5 |

### US-6 Matcher decides (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-24 | A PENDING verification for an enrolled subject, tenant threshold t | A matcher replica processes it | `score` = float64 reference cosine of probe vs active template within 1e-5; `MATCH` if `score >= t`, else `NO_MATCH`; `threshold` = t; `decidedAt` set. Boundary: score == t → `MATCH` | [A: "computes **cosine similarity** (`score`) and decides `MATCH` if `score >= threshold` of the tenant at decision time."] NFR-8 |
| AC-25 | A verification posted while threshold = 0.5 | Admin sets 0.9 before it is decided | Decision uses and records 0.9 | INV-3 (INFERRED from "at decision time") |
| AC-26 | Any verification | It reaches a terminal status | Its probe template is no longer stored | [D-11] |
| AC-27 | Processing fails on every attempt (injected fault) | 3 deliveries have been made (a redelivery after a crash counts as an attempt) | `"status":"FAILED","reason":"MAX_ATTEMPTS"` via the dead-letter queue; never processed again | [A: "A job that fails 3 times ends as `FAILED` with a reason."] A-2 |
| AC-28 | A worker holding a job crashes (connection closed) or stalls past its processing deadline | RabbitMQ redelivers the job | Another worker decides it within 30 s; the processing deadline is ≤ 30 s (asserted on config; test uses a shortened deadline) | [A: "If a replica dies while holding a job, the job must be retried and decided within 30 s."] |
| AC-29 | The subject has no active template | The job is processed | `FAILED` / `SUBJECT_NOT_ENROLLED` on the first attempt, no retry | BR-6 |

### US-7 Read a result (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-30 | T's verification V is decided | `GET /v1/verifications/V` | `200` with `verificationId`, `subjectId`, `status` (`MATCH`/`NO_MATCH`), `score`, `threshold`, `decidedAt`; no `reason`; no template data | [A: "Returns `{"verificationId","subjectId","status","score","threshold","decidedAt"}`."] |
| AC-31 | V is pending or claimed | It is read | `200`, `"status":"PENDING"`, `score`/`threshold`/`decidedAt` null | [A: "`status` is one of `PENDING`, `MATCH`, `NO_MATCH`, `FAILED`."] A-4 |
| AC-32 | V is FAILED | It is read | `reason` present, one of `SUBJECT_NOT_ENROLLED`, `SUBJECT_ERASED`, `MAX_ATTEMPTS` | [D-9] |
| AC-33 | A non-existent UUID, or a path id that is not a UUID | It is read | `404` problem detail identical to "not found" (never `400`/`500`) | ASSUMPTION A-S2 |

### US-8 Exactly-once proof (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-34 | 500 PENDING verifications in PostgreSQL with their messages in RabbitMQ (integration test), 3 concurrent consumers | All are processed | Each has exactly one terminal status; applied terminal transitions = 500; result counts sum to 500 | [A: "Correctness under concurrency, and how you **proved** it (tests, not claims)."] INV-1 |
| AC-35 | Worker B decided J; J's message is delivered again (duplicate publish or redelivery) and worker A processes it | A attempts its terminal write with a different status/score | 0 rows change; J keeps B's `status`, `score`, `threshold`, `decidedAt`; the result metric is not incremented for A | [A: "no duplicate or conflicting results even if a replica crashes mid-processing."] |
| AC-36 | J is terminal | J's message is delivered again | It is acknowledged without any change or metric; J is never decided again | INV-1 |

### US-9 Tenant isolation (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-37 | Tenant A has subject `s1` and verification `v1` | Tenant B (all scopes) calls `GET /v1/verifications/v1` or `DELETE /v1/subjects/s1` | `404`; A's data unchanged | [A: "Use `404`, not `403`, for other tenants' resources."] |
| AC-38 | B has no subject `s1` | B verifies against `s1` with a probe equal to A's template; then B enrols its own `s1` | Verification ends `FAILED`/`SUBJECT_NOT_ENROLLED`, never compared with A's template; B's `s1` gets version 1; A's `s1` unchanged | [A: "Every operation is scoped to the caller's `tenant_id`."] INV-4 |
| AC-39 | A 404 for another tenant's id and a 404 for a non-existent id | The responses are compared | Status, `type`, `title`, `detail` identical | MC-1 |
| AC-40 | A sets threshold 0.8 | B's next verification is decided | B uses its own threshold (default 0.5) | [A: "a tenant must never see, verify against or delete another tenant's subjects or verifications."] |

### US-10 No leakage, input limits (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-41 | A test run exercising every endpoint with valid/invalid templates and tokens | Captured gateway + matcher logs and all error bodies are scanned | No submitted base64 template, bearer token or `Authorization` header value appears | [A: "Template data and tokens must never appear in logs or error responses."] |
| AC-42 | A request body > 8 KB | Sent to any `/v1` endpoint | `413` problem detail; body not processed | NFR-9 (ASSUMPTION) |
| AC-43 | Malformed JSON, wrong types (`"template": 123`), or unsupported `Content-Type` | Sent | `400` or `415` problem detail; no stack trace, exception class, SQL or echoed input | MC-6, CLAUDE.md §3 |
| AC-44 | Any `/v1` response | – | Carries `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` | CLAUDE.md §4 |

### US-11 Tenant threshold (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-45 | Token with scope `admin` for T | `PUT /v1/tenant/threshold` `{"threshold":0.7}` | `200` `{"threshold":0.7}`; T's later decisions use 0.7; a tenant that never set one uses 0.5 | [A: "Sets the tenant's decision threshold. Default `0.5`."] A-S1 |
| AC-46 | Same | Values `-0.01`, `1.01`, `"abc"`, `null`, NaN or missing field | `400` problem detail; threshold unchanged; `0.0` and `1.0` accepted | [A: "Body `{"threshold": 0.0–1.0}`."] |

### US-12 Erasure (Should, in MVP)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-47 | T's `s1` has 2 versions, a PENDING verification with a stored probe, and decided verifications | `DELETE /v1/subjects/s1` with scope `admin` | `204`; no template version and no probe of `s1` remain; decided verifications stay readable with `status`, `score`, `threshold`, `decidedAt`; idempotency fingerprints for `s1` are deleted | [A: "Right to erasure: removes all template versions of the subject and its verification probes."] [A: "Verification results stay, without template data. Returns `204`, or `404` if unknown."] A-S3 |
| AC-48 | `s1` unknown or already erased; or the id is malformed | Deleted | `404` problem detail; a malformed id gets `400` (D-26) | [A: "Returns `204`, or `404` if unknown."] |
| AC-49 | A verification of `s1` is PENDING or claimed when the `204` commits | The worker writes its result / the job is next claimed | It ends `FAILED`/`SUBJECT_ERASED`, never `MATCH`/`NO_MATCH` (deterministic ordering test) | [D-8] INV-5 |
| AC-50 | `s1` was erased | It is enrolled again | `201` with `"version":1` | A-3 (ASSUMPTION) |

### US-13 Metrics (Should, after MVP)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-51 | The stack is running | `/actuator/prometheus` is scraped on the management port inside the compose network | Exposes a verification counter by result (`MATCH`, `NO_MATCH`, `FAILED`), a queue-depth gauge (non-terminal verifications), a processing-duration timer, and a `decidedAt − createdAt` histogram; no tenant or subject ids as labels | [A: "`/actuator/health` on both apps, plus metrics: verifications by result, queue depth or lag, processing duration."] [D-6] [D-13] |
| AC-52 | The public gateway port | Any actuator endpoint other than `health` is requested | Not served (`401` or `404`) | [D-6] |

### US-14 Eval tool (Must)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-53 | Stack up; `dataset/gen_dataset.py` produced `enrollments.jsonl`, `pairs.jsonl` | The Python 3 stdlib eval tool runs | Gets tokens for the 3 tenants via the dev script; enrols all 1 000 subjects via the API (all `201`); submits all 5 000 pairs with a unique `Idempotency-Key` each; polls until all terminal; exits non-zero if any pair ends `FAILED` or times out | [A: "enrols the subjects through the API, runs all pairs through the running system and reports:"] [D-12] |
| AC-54 | Run completed | – | Prints FMR and FNMR at 0.5 with counts (impostors, genuines, false matches, false non-matches) | [A: "FMR (false match rate) and FNMR (false non-match rate) at the default threshold,"] |
| AC-55 | Run completed | – | Prints the lowest threshold with FMR ≤ 0.1 %, its FNMR, and the 95 % upper bound on FMR for the impostor count (rule of three when 0 errors) | [A: "a recommended threshold for FMR ≤ 0.1 % and the resulting FNMR,"] |
| AC-56 | Run completed | – | Prints genuine/impostor score distributions (min, p5, median, p95, max), impostors ≥ 0.5 (look-alikes), genuines below the recommended threshold (poor captures); two runs print identical numbers | [A: "anything notable you observe in the data."] |

### US-15 Load test (Should, after MVP; run in Ship-check per D-17)

| AC | Given | When | Then | Source |
|---|---|---|---|---|
| AC-57 | Stack up, subjects pre-enrolled in 3 tenants | `loadtest/` k6 script runs via `grafana/k6` at a constant 300 `POST /v1/verifications`/s for 60 s, unique keys, spread across 3 tenants | Reports error rate, POST p99, k6-polled p95 to final status; server-side p95 of `decidedAt − createdAt` recorded too | [A: "Provide a **load test** (k6, Gatling or similar) under `loadtest/` and put the measured numbers in the README, including the machine you ran it on."] [D-12] [D-13] |
| AC-58 | A completed run | – | README states the machine (CPU, cores, RAM, OS, Docker limits), NFR-1/2/3 numbers, and per NFR met / not met with reason and planned change | [A: "If you do not meet an NFR, say so and explain why and what you would change."] |

## NFRs

| NFR | Requirement | Verified by | Source |
|---|---|---|---|
| NFR-1 | 300 verifications/s for 60 s, error rate < 0.1 % (laptop, compose, 3 matchers) | AC-57 | [A: "Sustain **300 verifications/s for 60 s** on a developer laptop (docker compose, 3 matcher replicas), error rate < 0.1 %."] |
| NFR-2 | p95 POST → final status ≤ 500 ms under NFR-1 load | AC-51, AC-57 | [A: "p95 time from `POST /v1/verifications` to final status ≤ **500 ms** under NFR-1 load."] |
| NFR-3 | `POST /v1/verifications` p99 ≤ 100 ms under NFR-1 load | AC-57 | [A: "`POST /v1/verifications` p99 ≤ **100 ms** under NFR-1 load."] |
| NFR-4 | Health on both apps; metrics for results, queue depth, processing duration | AC-2, AC-51 | [A: "`/actuator/health` on both apps, plus metrics: verifications by result, queue depth or lag, processing duration."] |
| NFR-5 | Store only needed biometric data: probe deleted once decided; erasure removes all templates and probes; fingerprints keyed-hashed. Encryption at rest = Won't now | AC-23, AC-26, AC-47 | [A: "Store only what is needed; templates at rest should be encrypted (Should)."] [D-2] [D-11] |
| NFR-6 | A job held by a stalled/crashed worker is decided ≤ 30 s later | AC-28 | [A: "If a replica dies while holding a job, the job must be retried and decided within 30 s."] |
| NFR-7 | 0 duplicate or conflicting outcomes (500-job, 3-consumer test + duplicate/redelivered message) | AC-34..AC-36 | ASSUMPTION (scaled-down test target for L45) |
| NFR-8 | `score` within 1e-5 of the float64 reference cosine | AC-24 | ASSUMPTION |
| NFR-9 | Request body ≤ 8 KB, else `413` | AC-42 | ASSUMPTION |
| NFR-10 | Token TTL ≤ 15 min; clock skew ≤ 60 s | AC-5, AC-6 | ASSUMPTION (CLAUDE.md §4) |
| NFR-11 | `docker compose up --build` healthy ≤ 3 min, no manual steps | AC-1 | INFERRED from [A: "a git repository we can run with `docker compose up --build`"] |

## Non-goals (Won't have now)

- Container-kill crash-recovery test – exactly-once and redelivery are proven by integration tests AC-28, AC-34..AC-36 [D-2].
- Per-tenant rate limiting (`429` + `Retry-After`) [D-2].
- Encryption of templates at rest [D-2].
- Long-poll / webhook notification, Grafana dashboard, per-tenant metrics (Could).
- Retention job for results and inactive versions [D-14].
- Token endpoint or user/password store [D-4]; audit trail (MC-8); hiding or quantising `score` (MC-7); fair queueing across tenants.

All of these go to README "Known limitations" with the production next step (BACKLOG "Won't have now", "Roadmap").

## Assumptions

| A | Assumption | Why | Risk if wrong |
|---|---|---|---|
| A-S1 | `PUT /v1/tenant/threshold` returns `200` with `{"threshold":x}` | Brief gives no status; echoing the stored value is testable | Graders expect `204`; trivial change |
| A-S2 | A non-UUID verification id returns `404`, same body as an unknown id | No difference between "malformed" and "unknown" leaks to callers (MC-1) | Graders expect `400`; trivial change |
| A-S3 | Erasure also deletes the subject's idempotency fingerprints | Fingerprints derive from biometric data (ANALYSIS data inventory) | A replay after erasure creates a new verification instead of returning the old id – acceptable |
| A-2 | A redelivery after a crash, a stall past the deadline or a processing error counts as one of the 3 attempts (quorum-queue delivery count, D-28) | Prevents poison jobs looping forever | Healthy jobs could fail early under repeated crashes |
| A-3 | Version numbering restarts at 1 after erasure | Erased subject has no history to continue | Clients caching versions may be confused |
| A-4 | Claimed jobs are reported as `PENDING` | Brief lists only 4 statuses | None |
| A-5 | Single gateway instance in compose | Rate limiting is out of scope; nothing else is instance-local | Horizontal gateway scaling needs no change except a future shared limiter |
| NFR-7..NFR-10 | Numeric targets not in the brief | Make the brief's qualitative rules testable | Targets too strict/lenient – adjust in SPEC |

## Open questions (max 7)

| # | Question | Proposed default |
|---|---|---|
| 1 | Accept A-S1 (threshold PUT → `200` + body)? | Yes |
| 2 | Accept A-S2 (non-UUID id → `404`)? | Yes |
| 3 | Accept A-S3 (erasure deletes idempotency fingerprints)? | Yes |
| 4 | Is 8 KB the right body limit (a template is ≈ 684 base64 chars)? | Yes |
