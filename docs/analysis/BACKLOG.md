# Backlog

> LIGHT route [D-1]. Proposed by the product-owner subagent, written by the main session; the human decides.
> Sources: docs/ASSIGNMENT.md, docs/analysis/TRIAGE.md, ANALYSIS.md, DECISIONS.md (D-1..D-16, binding), CLAUDE.md.
> Labels: `[A: "…"]` verbatim single-line quote from ASSIGNMENT.md; `[D-x]` human decision; BR-x / NFR-x / INV-x / MC-x / OQ-x / A-x refer to ANALYSIS.md; anything else ASSUMPTION or INFERRED.
> Implementation budget: **≈ 80 min incl. tests** (TRIAGE.md, LIGHT). Estimates = wall-clock minutes with AI agents, implementation + tests. ADR/README prose is counted in Plan / Ship-check, not here.

**Product goal:** a tenant backend can enrol face templates and get asynchronous 1:1 verification results that are decided exactly once, isolated per tenant, and never leak biometric data or tokens – started with one `docker compose up --build`, with honestly measured accuracy and load numbers.
[A: "Verification must not block the public API"] [A: "no duplicate or conflicting results even if a replica crashes mid-processing."] [A: "Template data and tokens must never appear in logs or error responses."]

## Success metrics

| Outcome | Measurable signal | Trace |
|---|---|---|
| Runs out of the box | Fresh clone: `docker compose up --build` brings postgres, gateway and 3 matcher replicas to healthy ≤ 3 min, zero manual steps | [A: "all started by `docker compose up --build`"], NFR-11 |
| Exactly-once proven, not claimed | Competing-workers and stale-lease tests (AC-34..AC-36) green in `./scripts/verify.sh`; 0 duplicate or overwritten terminal outcomes | [A: "Correctness under concurrency, and how you **proved** it (tests, not claims)."], INV-1 |
| Tenant isolation holds | 100 % of cross-tenant tests (AC-37..AC-40) get 404 identical to "not found" | BR-2 |
| No biometric/token leakage | Log + error-body scan (AC-41) finds 0 matches for submitted base64 templates, bearer tokens or Authorization header values | BR-12 |
| Accuracy understood | Eval tool reports FMR/FNMR at 0.5, recommended threshold for FMR ≤ 0.1 % with its FNMR, and observations; deterministic across two runs | [A: "a recommended threshold for FMR ≤ 0.1 % and the resulting FNMR,"] |
| Performance honestly reported | README has measured NFR-1/2/3 numbers + machine spec; each NFR marked met / not met with reason | [A: "If you do not meet an NFR, say so and explain why and what you would change."] |
| Quality gate holds | `./scripts/verify.sh` green, no new warnings on every commit | CLAUDE.md §7 |

## Story map

| Backbone → | Run & access | Enrol | Request verification | Decide (matcher) | Read result | Govern data & tenant | Prove & measure |
|---|---|---|---|---|---|---|---|
| **Walking skeleton (thin slice)** | US-1 skeleton, US-2 JWT + dev tokens | US-3 enrol + validation | US-4 submit (202) | US-6 claim/score/decide | US-7 GET result | US-11 threshold | – |
| **MVP hardening** | US-10 no leakage + input limits | – | – | US-8 exactly-once proof | – | US-9 tenant isolation, US-12 erasure | US-14 eval tool |
| **In scope after MVP [D-2]** | – | – | US-5 idempotency | – | – | – | US-13 metrics, US-15 load test |
| **Won't now** | rate limiting | encryption at rest | – | container-kill crash test | long-poll / webhook | retention job, audit | Grafana, per-tenant metrics |

## Stories

AC numbering is global. "Problem detail" = RFC 9457 `application/problem+json`. ACs describe externally observable behaviour or persisted state, not design.

| Id | Story (As a … I want … so that …) | AC (Given/When/Then, AC-x numbered globally) | BR/NFR | MoSCoW | Estimate (min) | MVP |
|---|---|---|---|---|---|---|
| US-1 | As an **evaluator** I want one command to build and start the gateway, 3 matcher replicas and PostgreSQL with schema in place, so that I can run and test the system without manual setup. | **AC-1** Given a fresh clone with no keys, no DB volume and no local JDK, When `docker compose up --build` runs, Then `postgres`, `gateway` and 3 `matcher` replicas reach healthy within 3 min, with no manual step and no secret committed to git.<br>**AC-2** Given the stack is running, When `GET /actuator/health` is called without a token on the gateway and on each matcher replica, Then each returns `200` with `"status":"UP"`.<br>**AC-3** Given the gateway starts against an empty database, When startup finishes, Then schema migrations are applied once, owned by the gateway [D-7], and concurrently started matchers do not fail.<br>**AC-4** Given the repository, When `./scripts/verify.sh` runs, Then it builds `gateway`, `matcher`, `common`, runs `ktlintCheck` and all tests (incl. PostgreSQL integration tests) and exits 0. | [A: "At least **two separately deployable applications** plus infrastructure, all started by `docker compose up --build`:"] [A: "Runs as **3 replicas** in docker compose."] [A: "PostgreSQL for persistent state."] [D-3] [D-7] NFR-4 (health), NFR-11 | Must | 15 | Yes |
| US-2 | As a **tenant backend developer** I want a dev script that mints RS256 tokens for my tenant and scopes, and a gateway that rejects any other token, so that only authenticated callers with the right scope reach tenant data. | **AC-5** Given the stack is up, When the operator runs the dev token script with `tenant_id=bank-alpha`, `sub=client-1`, `scope="enroll verify"`, Then it prints an RS256 JWT with `sub`, `tenant_id`, `scope`, `exp`, `iss`, `aud`, `nbf`, `iat`, `jti`, TTL ≤ 15 min; the signing key was generated at startup into a shared volume, the gateway holds only the public key, and no key file is tracked by git [D-4].<br>**AC-6** Given any `/v1/**` endpoint, When the token is missing, malformed, expired (> 60 s past `exp`), `alg=none`, HS256-signed with the public key as secret, signed by a different RSA key, has wrong `iss` or `aud`, or lacks `tenant_id`, Then `401` with a `WWW-Authenticate` header and a problem detail that does not contain the token.<br>**AC-7** Given a valid token whose `scope` lacks the endpoint's scope (e.g. `verify` only on `POST /v1/subjects/s1/enrollment`, `enroll` only on `PUT /v1/tenant/threshold`, `verify` only on `DELETE /v1/subjects/s1`), When called, Then `403` problem detail [D-5] and nothing is changed.<br>**AC-8** Given a valid token with the required scope, When called, Then authorization passes; `scope` values are read space-separated (`"enroll verify admin"` grants all three). | BR-1, NFR-10, MC-2 [A: "All endpoints except health require a **JWT bearer token** (RS256)."] [A: "Required claims: `sub`, `tenant_id`, `scope`"] [A: "(your own token endpoint or a dev script)"] [D-4] [D-5] | Must | 10 | Yes |
| US-3 | As a **tenant backend** with scope `enroll` I want to enrol or replace a subject's template, with invalid templates rejected, so that verifications compare against one valid active template. | **AC-9** Given tenant T and subject `subj-001` with no enrolment, When `POST /v1/subjects/subj-001/enrollment` with `{"template":"<valid base64: 512 bytes, 128 finite LE float32, L2 norm 1.0>"}`, Then `201` with `{"subjectId":"subj-001","version":1}`.<br>**AC-10** Given `subj-001` has active version 1, When a second valid template is enrolled, Then `201` with `"version":2`, version 2 is active, version 1 is kept inactive, and a verification decided afterwards scores against version 2.<br>**AC-11** Given an enrolment, When `template` is missing/null, not valid standard base64, decodes to 511 or 513 bytes, contains NaN or ±Infinity, or has L2 norm 0.98 or 1.02, Then `400` problem detail, no version is created, and the body does not contain the submitted base64. Boundary: norm 0.99 and 1.01 → `201`.<br>**AC-12** Given a path `subjectId` of 65 chars or with a character outside `[A-Za-z0-9._-]` (e.g. `a b`, `a%2Fb`, `ä`), When enrolling, Then `400` problem detail; a 64-char id is accepted [D-15].<br>**AC-13** Given two concurrent enrolments for the same subject, When both complete, Then both return `201` with distinct versions and exactly one version is active (INV-2). | BR-3, BR-4, INV-2, MC-6 [A: "Creates or replaces the subject's active template. Replacing keeps the previous version inactive. Returns `201` with `{"subjectId","version"}`."] [A: "the template must decode to exactly 512 bytes, all values finite, L2 norm in `[0.99, 1.01]`"] [A: "Otherwise return `400` (RFC 9457 problem detail)."] [D-15] | Must | 10 | Yes |
| US-4 | As a **tenant backend** with scope `verify` I want to submit a probe and immediately get an id, so that bursts never wait for matching. | **AC-14** Given tenant T with enrolled `subj-001`, When `POST /v1/verifications` with header `Idempotency-Key: k-1` and `{"subjectId":"subj-001","probeTemplate":"<valid>"}`, Then `202` with `{"verificationId":"<random UUID>","status":"PENDING"}` [D-16] and `Location: /v1/verifications/{verificationId}`; the response does not wait for a matcher decision.<br>**AC-15** Given a valid body, When `Idempotency-Key` is missing, empty or 65 chars, Then `400` problem detail and no verification is created; a 64-char key is accepted.<br>**AC-16** Given a valid key, When `probeTemplate` violates any rule of AC-11 or `subjectId` violates AC-12, Then `400` problem detail without the submitted base64; no verification is created.<br>**AC-17** Given subject `ghost` is not enrolled for T, When a valid verification is posted, Then `202 PENDING`, and it later ends `"status":"FAILED","reason":"SUBJECT_NOT_ENROLLED"` with null `score` [D-9]. | BR-3, BR-5 (header), BR-6, NFR-3 [A: "Header `Idempotency-Key` (required, max 64 chars)"] [A: "Returns `202` with `{"verificationId","status":"PENDING"}` and a `Location` header."] [A: "accepted (`202`), then finishes with `FAILED` and `reason: SUBJECT_NOT_ENROLLED`."] [D-16] | Must | 6 | Yes |
| US-5 | As a **tenant backend** retrying after a timeout I want the same `Idempotency-Key` to return the original verification, so that retries never create duplicate decisions. | **AC-18** Given T posted key `k-1` with body B and got V, When the same key and identical B are posted within 24 h, Then the response returns V: `202` while PENDING, `200` once decided [D-10]; no second verification is created.<br>**AC-19** Given T used `k-1` with body B, When `k-1` is posted with a different `subjectId` or `probeTemplate`, Then `422` problem detail; no verification is created.<br>**AC-20** Given tenant A used `k-1`, When tenant B posts `k-1` with any body, Then B gets its own new `verificationId` (`202`), never A's, and A's record is unaffected (MC-4).<br>**AC-21** Given 10 concurrent identical requests with new key `k-par`, When all complete, Then all responses carry the same `verificationId` and exactly one verification exists.<br>**AC-22** Given `k-1` was first used > 24 h ago (injected clock), When reused, Then it is treated as a new key with a new `verificationId`.<br>**AC-23** Given any idempotency record, When persisted state is inspected, Then it holds a keyed hash of (subjectId, probe bytes), not the raw probe [D-10]. | BR-5, INV-6, MC-4, NFR-5 [A: "the same `Idempotency-Key` with the same body within 24 h returns the original `verificationId` (`200` or `202`)."] [A: "The same key with a different body returns `422`."] [D-2] [D-10] | Should (in scope [D-2]) | 8 | No (after MVP) |
| US-6 | As a **tenant** I want each pending verification decided by any matcher replica using cosine similarity and my threshold at decision time, so that results are correct and scale horizontally. | **AC-24** Given a PENDING verification for an enrolled subject and tenant threshold t, When a matcher replica processes it, Then `score` equals the float64 reference cosine of probe and active template within 1e-5; status `MATCH` if `score >= t`, else `NO_MATCH`; `threshold` = t; `decidedAt` set. Boundary: score == t → `MATCH`.<br>**AC-25** Given a verification posted while threshold = 0.5, When an admin sets 0.9 before the matcher decides, Then the decision uses and records 0.9 (INV-3).<br>**AC-26** Given a verification reaches any terminal status, When persisted state is inspected, Then its probe template is no longer stored [D-11].<br>**AC-27** Given processing fails on every attempt (injected fault), When attempted 3 times (lease expiry counts as an attempt, A-2), Then it ends `FAILED` with `reason` `MAX_ATTEMPTS` and is never claimed again.<br>**AC-28** Given a worker claimed a job and never completes it, When its lease expires, Then another worker reclaims and decides it; with the default lease + poll configuration the stall-to-decision time is ≤ 30 s (test uses a shortened lease and asserts default lease + poll interval ≤ 30 s).<br>**AC-29** Given the subject has no active template, When processed, Then `FAILED` / `SUBJECT_NOT_ENROLLED` on the first attempt, no retry. | BR-6, BR-7, BR-8, BR-11, INV-3, INV-4, INV-7, NFR-6, NFR-8 [A: "decides `MATCH` if `score >= threshold` of the tenant at decision time."] [A: "If a replica dies while holding a job, the job must be retried and decided within 30 s."] [A: "A job that fails 3 times ends as `FAILED` with a reason."] [D-3] [D-11] | Must | 12 | Yes |
| US-7 | As a **tenant backend** with scope `verify` I want to read a verification's result, so that I can act on the decision. | **AC-30** Given T's verification V is decided, When `GET /v1/verifications/V`, Then `200` with `verificationId`, `subjectId`, `status` (`MATCH`/`NO_MATCH`), `score`, `threshold`, `decidedAt`; no `reason` field and no template data.<br>**AC-31** Given V is pending, When read, Then `200` with `"status":"PENDING"` and `score`, `threshold`, `decidedAt` null (ASSUMPTION, UC-3); claimed jobs also show `PENDING` (A-4).<br>**AC-32** Given V is `FAILED`, When read, Then `reason` is present and one of `SUBJECT_NOT_ENROLLED`, `SUBJECT_ERASED`, `MAX_ATTEMPTS` [D-9].<br>**AC-33** Given a random non-existent UUID, When read, Then `404` problem detail; a non-UUID id → `404` or `400`, never `500` (ASSUMPTION – fix in SPEC). | BR-2, BR-6 [A: "Returns `{"verificationId","subjectId","status","score","threshold","decidedAt"}`."] [D-9] | Must | 3 | Yes |
| US-8 | As an **evaluator** I want automated tests showing that competing workers and a late stale-lease writer cannot produce duplicate or conflicting outcomes, so that exactly-once is proven rather than claimed. | **AC-34** Given 500 PENDING verifications in PostgreSQL (integration test) and 3 workers polling concurrently, When all are processed, Then every verification has exactly one terminal status, terminal transitions applied = 500, and result counts sum to 500.<br>**AC-35** Given worker A claimed job J, A's lease expired, and worker B reclaimed and decided J, When A attempts its terminal write with a different status/score, Then 0 rows change, J keeps B's `status`, `score`, `threshold`, `decidedAt`, and the result metric is not incremented for A.<br>**AC-36** Given J is terminal, When any worker polls, Then J is never claimed again. | INV-1, NFR-7 (scaled to the test), BR-11 [A: "no duplicate or conflicting results even if a replica crashes mid-processing."] [A: "Correctness under concurrency, and how you **proved** it (tests, not claims)."] [D-2] [D-3] | Must | 8 | Yes |
| US-9 | As a **tenant** I want my subjects, verifications and threshold to be invisible and untouchable for other tenants, so that biometric data never crosses tenants. | **AC-37** Given tenant A has subject `s1` and verification `v1`, When tenant B (all scopes) calls `GET /v1/verifications/v1` or `DELETE /v1/subjects/s1`, Then `404`, and A's template versions and verification are unchanged.<br>**AC-38** Given B has no subject `s1`, When B posts a verification for `s1` with a probe equal to A's template, Then `202` and it ends `FAILED` / `SUBJECT_NOT_ENROLLED` – never compared with A's template. When B enrols its own `s1`, it gets version 1 in B's namespace; A's `s1` is unchanged.<br>**AC-39** Given a 404 for another tenant's id and a 404 for a non-existent id, When compared, Then status, problem `type`, `title` and `detail` are identical.<br>**AC-40** Given A sets threshold 0.8, When B's next verification is decided, Then B uses its own threshold (default 0.5). | BR-2, INV-4, MC-1 [A: "Every operation is scoped to the caller's `tenant_id`."] [A: "Use `404`, not `403`, for other tenants' resources."] | Must | 4 | Yes |
| US-10 | As a **data protection officer** I want templates and tokens kept out of logs and error responses, and oversized or malformed input rejected, so that biometric data cannot leak and the API cannot be abused with hostile payloads. | **AC-41** Given a test run exercising every endpoint with valid and invalid templates and tokens, When captured gateway and matcher logs and all error bodies are scanned, Then none contains any submitted base64 template, bearer token or `Authorization` header value.<br>**AC-42** Given a request body > 8 KB, When sent to any `/v1` endpoint, Then `413` problem detail and the body is not processed (NFR-9, ASSUMPTION limit).<br>**AC-43** Given malformed JSON, wrong JSON types (e.g. `"template": 123`) or an unsupported `Content-Type`, When sent, Then `400` or `415` problem detail with no stack trace, exception class name, SQL or echoed input.<br>**AC-44** Given any `/v1` response, Then it carries `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` (CLAUDE.md §4). | BR-12, NFR-5, NFR-9, MC-3, MC-6 [A: "Template data and tokens must never appear in logs or error responses."] | Must | 5 | Yes |
| US-11 | As a **tenant admin** I want to set my tenant's decision threshold, so that I can tune false matches against false non-matches. | **AC-45** Given a token with scope `admin` for T, When `PUT /v1/tenant/threshold` with `{"threshold":0.7}`, Then success (`200` with `{"threshold":0.7}` – ASSUMPTION, brief gives no status) and T's later decisions use 0.7; a tenant that never set one uses 0.5.<br>**AC-46** Given values `-0.01`, `1.01`, `"abc"`, `null` or a missing field, When put, Then `400` problem detail and the threshold is unchanged; `0.0` and `1.0` are accepted. | BR-7 [A: "Sets the tenant's decision threshold. Default `0.5`."] [A: "Body `{"threshold": 0.0–1.0}`."] | Must | 3 | Yes |
| US-12 | As a **tenant admin** handling a GDPR erasure request I want to delete all biometric data of a subject, so that the right to erasure is honoured while decision history stays intact. | **AC-47** Given T's subject `s1` has 2 template versions, one PENDING verification with a stored probe and decided verifications, When `DELETE /v1/subjects/s1` with scope `admin`, Then `204`; no template version and no probe of `s1` remain in persisted state; decided verifications remain readable with `status`, `score`, `threshold`, `decidedAt`; idempotency fingerprints for `s1` are purged (ASSUMPTION, ANALYSIS data inventory).<br>**AC-48** Given `s1` is unknown or already erased, When deleted, Then `404` problem detail.<br>**AC-49** Given a verification of `s1` is PENDING or claimed by a worker at the time of the `204`, When the worker finishes or the job is next claimed, Then it ends `FAILED` / `SUBJECT_ERASED`, never `MATCH`/`NO_MATCH` [D-8] (deterministic race test: the worker's terminal write is attempted after the delete commits).<br>**AC-50** Given `s1` was erased, When enrolled again, Then `201` with `"version":1` (A-3, ASSUMPTION). | BR-8, BR-9, INV-5, NFR-5 [A: "Right to erasure: removes all template versions of the subject and its verification probes."] [A: "Verification results stay, without template data. Returns `204`, or `404` if unknown."] [D-2] [D-8] [D-11] | Should (in scope [D-2]); MVP – privacy/deletion never cut | 7 | Yes |
| US-13 | As an **operator** I want health plus metrics for results, queue depth and processing time, so that I see backlog and latency under bursts and can measure NFR-2 server-side. | **AC-51** Given the stack is running, When `/actuator/prometheus` is scraped on the management port inside the compose network, Then it exposes a verification counter tagged by result (`MATCH`, `NO_MATCH`, `FAILED`), a queue-depth gauge (non-terminal verifications), a matcher processing-duration timer, and a `decidedAt − createdAt` histogram [D-13]; no tenant or subject ids as labels.<br>**AC-52** Given the public gateway port, When `/actuator/prometheus` or any actuator endpoint other than health is requested, Then it is not served (`401`/`404`) [D-6]. | NFR-4, NFR-2 (measurement) [A: "`/actuator/health` on both apps, plus metrics: verifications by result, queue depth or lag, processing duration."] [D-6] [D-13] | Should (INFERRED: NFR not ranked in the brief's priority table) | 3 | No (after MVP) |
| US-14 | As an **evaluator** I want a tool that enrols the dataset through the API, runs all pairs and reports accuracy, so that I can judge the default threshold and choose a better one. | **AC-53** Given the stack is up and `dataset/gen_dataset.py` produced `enrollments.jsonl` and `pairs.jsonl`, When the Python 3 stdlib eval tool runs [D-12], Then it obtains tokens for the 3 tenants via the dev script [D-4], enrols all 1 000 subjects through the API (all `201`), submits all 5 000 pairs with a unique `Idempotency-Key` per pair, polls until every pair is terminal, and exits non-zero if any pair ends `FAILED` or times out.<br>**AC-54** Then it prints FMR and FNMR at threshold 0.5 with counts (impostors, genuines, false matches, false non-matches).<br>**AC-55** Then it prints the lowest threshold with FMR ≤ 0.1 % (from returned scores), the FNMR at that threshold, and the 95 % upper bound on FMR given the impostor count (rule of three when 0 errors).<br>**AC-56** Then it prints observations: genuine/impostor score distributions (min, p5, median, p95, max), the number of impostors scoring ≥ 0.5 (look-alikes) and of genuines below the recommended threshold (poor captures); two consecutive runs print identical numbers. | [A: "enrols the subjects through the API, runs all pairs through the running system and reports:"] [A: "FMR (false match rate) and FNMR (false non-match rate) at the default threshold,"] [A: "a recommended threshold for FMR ≤ 0.1 % and the resulting FNMR,"] [A: "anything notable you observe in the data."] [D-12] R-6 | Must | 8 | Yes |
| US-15 | As an **evaluator** I want a reproducible load test with measured numbers in the README, so that I can see whether the system absorbs a 300/s burst and where it falls short. | **AC-57** Given the stack is up with pre-enrolled subjects in 3 tenants, When the k6 script under `loadtest/` runs via the `grafana/k6` Docker image [D-12] at a constant 300 `POST /v1/verifications`/s for 60 s (unique keys, spread across 3 tenants), Then it reports error rate, POST p99 and k6-polled p95 to final status; the operator also records the server-side p95 of the `decidedAt − createdAt` histogram [D-13].<br>**AC-58** Given a completed run, Then the README states the machine (CPU model, cores, RAM, OS, Docker resource limits), the NFR-1/2/3 numbers, and per NFR met / not met with reason and planned change. | NFR-1, NFR-2, NFR-3 [A: "Provide a **load test** (k6, Gatling or similar) under `loadtest/` and put the measured numbers in the README, including the machine you ran it on."] [A: "If you do not meet an NFR, say so and explain why and what you would change."] [D-2] [D-12] [D-13] | Should (in scope [D-2]) | 7 | No (after MVP) |

## MoSCoW

| MoSCoW | Stories | Minutes | Note |
|---|---|---|---|
| Must | US-1, US-2, US-3, US-4, US-6, US-7, US-8, US-9, US-10, US-11, US-14 | 84 | The brief's Must row; US-8 is the exactly-once proof [D-2]; US-9/US-10 are security and never cut |
| Should – in scope [D-2] | US-12 erasure (MVP: privacy), US-5 idempotency, US-15 load test, US-13 metrics | 25 | US-13 is INFERRED Should (supports NFR-4 and the D-13 measurement) |
| Should – Won't now [D-2] | container-kill crash-recovery test, rate limiting, encryption at rest | 0 | See Won't have now |
| Could – Won't now | long-poll / webhook, Grafana dashboard, per-tenant metrics | 0 | See Won't have now |
| **Total in scope** | 15 stories | **109** | **29 min over the 80 min budget (+36 %)** |

## MVP

**Thinnest end-to-end slice** (core value: enrol, verify asynchronously, read an exactly-once decision): US-1 → US-2 → US-3 → US-4 → US-6 → US-7 = **56 min**. Checkpoint 1: green and committed before anything else starts.

**MVP** = slice + proof + security + privacy + eval:

| Order | Story | Min | Cumulative |
|---|---|---|---|
| 1 | US-1 Walking skeleton (multi-module, Postgres, Flyway, compose with 3 replicas, verify.sh) | 15 | 15 |
| 2 | US-2 JWT, dev token script, negative set | 10 | 25 |
| 3 | US-3 Enrol + template validation | 10 | 35 |
| 4 | US-4 Submit verification | 6 | 41 |
| 5 | US-6 Matcher decide, lease, attempts | 12 | 53 |
| 6 | US-7 GET result | 3 | 56 (**checkpoint 1: e2e slice green**) |
| 7 | US-8 Exactly-once proof | 8 | 64 |
| 8 | US-9 Tenant isolation | 4 | 68 |
| 9 | US-10 No leakage + input limits | 5 | 73 |
| 10 | US-11 Threshold | 3 | 76 |
| 11 | US-14 Eval tool | 8 | 84 |
| 12 | US-12 Erasure + SUBJECT_ERASED race | 7 | 91 (**MVP done**) |
| 13 | US-5 Idempotency | 8 | 99 |
| 14 | US-13 Metrics | 3 | 102 |
| 15 | US-15 Load test + README numbers | 7 | 109 |

**Honest verdict:** MVP = **91 min vs 80 min budget (+11)**; full D-2 scope = **109 min (+29)**. Estimates were not shaved to fit. The Spring Boot 4 multi-module walking skeleton with Testcontainers is the largest risk; 15 min is already optimistic.

**Proposals to close the gap** (human decides; record as D-17):

1. **Use the 15 min buffer for implementation** (TRIAGE.md time split): closes the MVP gap (91 ≤ 95); D-2 scope stays 14 min over. Cost: no slack for review fixes.
2. **Move the load-test run into Ship-check** (−4): the k6 script is written during implementation; the 60 s run + README numbers happen in the 15 min Ship-check, which runs compose anyway.
3. **Run US-14 and US-15 scripting in parallel** with a subagent once US-4/US-7 are green (−8 to −12 wall clock); they depend only on the API contract. Risk: merge/review cost and contract drift – the main session reviews them against the ACs.
4. **Simplify without dropping ACs:** AC-13/AC-21 as one test each relying on a DB uniqueness guarantee rather than a stress loop (−2); AC-49 deterministic ordering, not a timing race (already assumed); US-13 uses built-in timers where possible (−1).
5. **Cut order if checkpoint 1 (min 56) slips by > 10 min** – stop and ask the human before cutting: (a) US-13 → k6-only NFR-2 measurement (contradicts D-13, needs a decision); (b) US-15 → Won't, README states NFR-1..3 unmeasured (contradicts D-2, needs a decision); (c) US-5 reduced to AC-18..AC-20. **Never cut:** US-2, US-8, US-9, US-10, US-12, US-14.

With 1 + 2 + 4: ≈ 109 − 15 − 4 − 3 = **87 min** of implementation-phase time – still ≈ 7 min over, so option 3 or a cut from 5 is realistically needed.

## Won't have now

| Item | Reason |
|---|---|
| Crash-recovery test killing a matcher container | [D-2]; exactly-once and reclaim are proven by integration tests AC-28, AC-34..AC-36; README lists the missing container-kill test as a limitation |
| Per-tenant rate limiting (429 + Retry-After) | [D-2]; also the main threat to NFR-1 (OQ-3). README Known limitations: MC-5 noisy neighbour; a shared limiter for a horizontally scaled gateway (A-5) |
| Encryption of templates at rest | [D-2]; NFR-5 marks it Should. README: envelope encryption with a KMS as production next step |
| Long-poll / webhook notification | Could [A: "Long-poll or webhook notification of results, Grafana dashboard, per-tenant metrics"]; polling GET suffices for eval and load test |
| Grafana dashboard, per-tenant metrics | Could (same quote); per-tenant labels also raise cardinality and privacy questions |
| Retention job for results and inactive versions | [D-14]; documented as a production policy gap |
| Token endpoint / user-password store | [D-4]; machine callers, dev script suffices |
| Audit trail for enrol-replace and threshold changes (MC-8) | Not in the brief; production next step |
| Hiding/quantising `score` against hill-climbing (MC-7) | Brief requires `score` in GET; residual risk documented |
| Fair queueing across tenants | ASSUMPTION/Could (MC-5); relevant only together with rate limiting |

## Roadmap

- **Increment 2 – hardening (next half-day):**
  - Container-kill crash-recovery test asserting a decision ≤ 30 s with no duplicates (NFR-6, NFR-7).
  - Per-tenant rate limiting with a shared store, `429` + `Retry-After` (BR-10); re-run the load test across tenants (resolves OQ-3).
  - Encryption at rest for template versions and probes (NFR-5).
  - Tuning against the measured NFR-1..3 gaps.
- **Increment 3 – production readiness:**
  - A real IdP / client-credentials issuer replacing the dev script.
  - Audit log for enrol, replace, threshold and erasure.
  - Retention policy for results and inactive versions (D-14).
  - Long-poll or webhook delivery of results.
  - Per-tenant dashboards and alerting on queue depth.
  - Evaluate broker scale-out, as recorded in the transport ADR [D-3].

## Definition of Ready

- [ ] Story traces to `[A: "…"]`, `[D-x]` or BR/NFR; every other element labelled ASSUMPTION/INFERRED
- [ ] ACs are Given/When/Then with concrete inputs, status codes and fields; testable without human judgement
- [ ] Open questions affecting the ACs resolved in DECISIONS.md, or the default labelled ASSUMPTION and accepted in SPEC
- [ ] Estimate ≤ 15 min (else split)
- [ ] Dependencies done (US-1 before everything; US-4 + US-6 before US-8, US-12, US-14)
- [ ] Security impact flagged (auth, tenant scope, biometric data) so security-reviewer is scheduled
- [ ] Approved step exists in docs/PLAN.md (CLAUDE.md §5)

## Definition of Done (consistent with CLAUDE.md §7)

- [ ] Every AC of the story is covered by automated tests incl. ≥ 2 edge/error cases; AC ids referenced in test names or comments
- [ ] `./scripts/verify.sh` green, real output shown
- [ ] No new warnings (compiler, ktlint, tests)
- [ ] Reviewed by code-reviewer (+ security-reviewer for auth, tenant scoping, template persistence, logging, erasure)
- [ ] docs/AI_LOG.md updated; security-sensitive code marked "human-reviewed"
- [ ] No template data or tokens in logs/error responses for the touched endpoints (AC-41 still green)
- [ ] Committed per CLAUDE.md §5b (Conventional Commits, tests in the same commit, footer `Refs: US-x, AC-y`)

## Demo script (onsite, ≈ 8 min)

1. **Clean start:**
   - `git clone` → `docker compose up --build`.
   - Show postgres, the gateway and 3 matcher replicas healthy; `GET /actuator/health` is `UP` on both apps (US-1).
2. **Happy path + idempotency:**
   - Mint tokens for `bank-alpha` with the dev script.
   - Enrol `subj-001` (`201`, v1), then re-enrol (v2).
   - `POST /v1/verifications` with `Idempotency-Key: demo-1` → `202` + Location; `GET` it → `MATCH` with score/threshold/decidedAt.
   - Replay the same key → `200`, same id; same key with a different probe → `422` (US-2..US-7, US-5).
3. **Security:**
   - A `bank-beta` token on alpha's verification → `404`.
   - A `verify`-only token on `PUT /v1/tenant/threshold` → `403`; an expired or `alg=none` token → `401`.
   - A 511-byte template → `400` with no base64 in the body.
   - `docker compose logs | grep` for the submitted base64 → nothing (US-2, US-9, US-10).
4. **Exactly-once + erasure proof:**
   - Run the competing-workers and stale-lease tests (AC-34..AC-36) and the SUBJECT_ERASED race test (AC-49) – green.
   - Live: `DELETE /v1/subjects/subj-001` → `204`; the old verification is still readable without template data; a second `DELETE` → `404` (US-8, US-12).
5. **Numbers:**
   - Run the eval tool: FMR/FNMR at 0.5, the recommended threshold for FMR ≤ 0.1 % with its FNMR, the look-alike and poor-capture observations.
   - The README load-test table: NFR-1..3 measured vs target, machine, met / not met (US-14, US-15).

## Open decisions

- **D-17 – budget overrun:** MVP 91 min, full D-2 scope 109 min vs 80 min; choose among the MVP proposals.
- **Confirm in SPEC:** `PUT /v1/tenant/threshold` success status (AC-45); status for a non-UUID verification id (AC-33); purging idempotency fingerprints on erasure (AC-47).
