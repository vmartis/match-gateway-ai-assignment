# Evals

> Refreshed by /ship-check and the verifier. Results must be real command output, never assumed.

Run: ship-check on commit `c76b0d3`, 2026-10-11.
- Unit + integration tests: `./gradlew clean` + `./scripts/verify.sh` → `VERIFY OK`, exit 0. JUnit XML: 517 tests,
  0 failures, 0 errors, 0 skipped (`{gateway,matcher,common,devtoken}/build/test-results/test/TEST-*.xml`).
- Acceptance (`./scripts/acceptance.sh`) on a fresh clone (compose project `shipcheck`): 66 passed, 0 failed.
- Eval (`python3 eval/eval.py`) on the fresh clone: exit 0. `python3 -m unittest eval/test_eval.py`: 21 tests OK.
- Load (`loadtest/run.sh`, full 60 s) on the fresh clone: exit 0. `python3 -m unittest loadtest/test_server_p95.py`: 3 tests OK.

Levels: **unit** = plain JUnit or Python unittest; **IT (filter chain)** = Spring Boot app over HTTP through the real
security filter chain (Testcontainers PostgreSQL behind it); **IT (Testcontainers)** = asserts persisted state in a
real PostgreSQL 17 container; **script** = black-box run against the docker compose stack.
"Last result" for tests is taken from the JUnit XML of the clean run. Test names are shortened; the AC id is kept.

## AC → test matrix
| AC | AC text | Test(s) (file::name) | Level | Last result |
|---|---|---|---|---|
| AC-1 | postgres, gateway, 3 matchers healthy ≤ 3 min; no manual step; no key in git | Fresh-clone `docker compose up --build` (`clone-up.log`: postgres Healthy, gateway + matcher-1..3 started, gateway applied migrations); acceptance `HEALTH` on that stack; `git ls-files \| grep -E '\.(pem\|key)$'` → empty. The ≤ 3 min wall time was not recorded in the log | script | PASS (partial: 3 min bound not measured) – 2026-10-11 clean run |
| AC-2 | `200` with `"status":"UP"` | HealthEndpointIT::health is served without a token with status UP and no component details; MatcherSkeletonIT::server port serves health; acceptance `HEALTH`, `LIVENESS`, `READINESS` | IT (filter chain) + script | PASS – 2026-10-11 clean run |
| AC-3 | Migrations applied once, owned by gateway; matchers neither fail nor migrate | GatewaySkeletonIT::flyway applies V1 on startup; GatewaySkeletonIT::V1 creates the schema; MatcherSkeletonIT::matcher starts against an empty database without migrating; fresh-clone log: gateway "Successfully applied 2 migrations", matchers started | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-4 | verify.sh builds, ktlint, all tests incl. PostgreSQL ITs; prints `VERIFY OK` | `./scripts/verify.sh` after `./gradlew clean` → `VERIFY OK`, exit 0 (517 tests, 0 failed) | script | PASS – 2026-10-11 clean run |
| AC-5 | RS256 JWT with all 9 claims, exp−iat ≤ 900 s; gateway holds public key only | DevTokenTest::mint produces a verifiable RS256 token with all claims; DevTokenTest::mint rejects a 16 minute ttl; DevTokenTest::A02 keygen never writes the private key into the gateway directory; DevTokenTest::keygen creates the three key files with exact permissions; acceptance `AC-6-valid` uses a script-minted token | unit + script | PASS – 2026-10-11 clean run |
| AC-6 | `401` + `WWW-Authenticate: Bearer`, generic body without token or reason | JwtAuthenticationIT (KDoc "AC-6")::API2 "missing header" / "expired 75 s ago" / "alg none" / "hs256 signed with the public key" / "signed with a different key" / "wrong issuer" / "wrong audience" / "missing tenant_id" … is rejected with 401 and a constant problem body (24 cases); JwtAuthenticationIT::API2 the 401 body is byte-identical for every rejection reason; acceptance `AC-6-none`, `AC-6-tampered`, `AC-6-valid` | IT (filter chain) + script | PASS – 2026-10-11 clean run |
| AC-7 | `403` problem detail; no state changes | ScopeAuthorizationIT::API5 AC-7 verify-only on enrollment and delete and enroll-only on threshold are 403; EnrollmentIT::API5 AC-7 verify-only token is 403 and no row is created; ErasureIT::API5 AC-7 … get 403 on delete and nothing changes; ThresholdIT::API5 AC-7 scope ["enroll"] on threshold is 403 and changes nothing; acceptance `AC-7`, `AC-7-enrol`, `AC-7-threshold` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-8 | Authorization passes; `scope` parsed space-separated | ScopeAuthorizationIT::AC-8 API5 POST/DELETE/POST/GET/PUT … passes with scope enroll verify admin (5 cases); ScopeAuthorizationIT::API5 a scope only needs to be present among several | IT (filter chain) | PASS – 2026-10-11 clean run |
| AC-9 | `201` `{"subjectId":"subj-001","version":1}` | EnrollmentIT::AC-9 first enrolment returns 201 with subjectId and version 1 and stores the decoded bytes; EnrollmentIT::AC-9 the 201 response is not cacheable and carries nosniff; acceptance `AC-9` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-10 | `201` version 2 active, version 1 kept inactive | EnrollmentIT::AC-10 second enrolment returns version 2 and keeps version 1 inactive; EnrollmentIT::AC-10 a third enrolment returns version 3 with exactly one active row; acceptance `AC-10` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-11 | `400`, no version, no echoed base64; norm 0.99/1.01 accepted | EnrollmentIT::AC-11 A05 "511 bytes" / "NaN component" / "norm 1.02" / … is a 400 problem and no version is created (16 cases); EnrollmentIT::AC-11 boundary norm 0_99 is accepted; EnrollmentIT::AC-11 boundary norm 1_01 is accepted; TemplateCodecTest (25 unit cases); acceptance `AC-11-short` | unit + IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-12 | `400` for invalid subjectId; 64-char id accepted | EnrollmentIT::AC-12 a 64 character subjectId is accepted; EnrollmentIT::AC-12 A05 subjectId segment "a%20b" / 65 chars / "%C3%A4" … is a 400 problem and creates nothing; acceptance `AC-12-id` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-13 | Concurrent enrolments: both `201`, distinct versions, one active | EnrollmentIT::AC-13 two concurrent enrolments of the same subject both return 201 with distinct versions and one active row | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-14 | `202` PENDING with random UUID and `Location`; no decision awaited | VerificationIT::AC-14 valid submission is 202 with exactly verificationId and PENDING and a relative Location; VerificationIT::AC-14 submission persists a PENDING row …; VerificationIT::AC-14 AC-30 a submitted verification can be read back via its Location; acceptance `AC-14` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-15 | `400` for bad Idempotency-Key, nothing created; 64-char key accepted | VerificationIT::AC-15 API4 Idempotency-Key [""] / [65 chars] / ["has space"] is 400 and creates no row; VerificationIT::AC-15 Idempotency-Key of 64 printable ASCII characters is accepted; VerificationIT::AC-15 API4 repeated Idempotency-Key headers … are 400; acceptance `AC-15-key` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-16 | `400` without echoed base64; nothing created | VerificationIT::AC-16 API4 invalid probeTemplate "511 bytes" / "NaN component" / "norm 1.02" / … is 400 and creates no row; VerificationIT::AC-16 API4 invalid subjectId "65 chars" … is 400; VerificationIT::AC-16 subject id of 64 characters is accepted | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-17 | `202 PENDING`, later `FAILED`/`SUBJECT_NOT_ENROLLED`, score null | VerificationIT::AC-17 an unknown subject is still accepted with 202 and a PENDING row; MatcherIT::AC-26 and AC-29 and AC-17 no active template fails SUBJECT_NOT_ENROLLED without retry and clears probe; acceptance `AC-17-unknown`, `AC-17-final` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-18 | Replay returns V: `202` while PENDING, `200` once decided | IdempotencyIT::AC-18 replay with the same key subject and probe returns 202 with the same id while PENDING and no second row; IdempotencyIT::AC-18 AM-12 replay after the row is decided is 200 with the same id …; IdempotencyIT::AC-18 a replay never modifies the decided verification; acceptance `AC-18-first`, `AC-18` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-19 | `422` on key reuse with a different body; nothing created | IdempotencyIT::API4 AC-19 same key with a different probe is 422 and creates nothing and leaks no template; IdempotencyIT::API4 AC-19 same key with a different subjectId is 422 …; IdempotencyIT::API4 AC-19 reuse … after the verification was decided is still 422; acceptance `AC-19` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-20 | Tenant B gets its own `verificationId`; A unchanged | IdempotencyIT::API1 AC-20 tenant B using the same key gets its own new id and tenant A's record is unchanged; acceptance `AC-20` (×2) | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-21 | Concurrent same-key requests share one `verificationId`; one row | IdempotencyIT::AC-21 ten concurrent identical requests share one id and create exactly one verification and one idempotency row; IdempotencyIT::API4 AC-21 ten concurrent requests with the same key and different probes yield one winner and nine 422 | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-22 | Expired key (> 24 h) treated as new | IdempotencyIT::AC-22 AM-11 a key first used 25 hours ago is treated as new …; IdempotencyIT::AC-22 a key first used 23 hours ago is still replayed; IdempotencyIT::AC-22 an expired key may be reused with a different body … | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-23 | Idempotency record holds HMAC of (subjectId, probe), never raw probe | IdempotencyIT::A04 AC-23 stored fingerprint is the keyed HMAC and never the probe, a slice of it or SHA-256 of the body; FingerprinterTest::AC-23 fingerprint equals an independent HmacSHA256 …; FingerprinterTest::AC-23 fingerprint is not an unkeyed hash … (6 unit cases) | unit + IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-24 | float64 cosine within 1e-5; `MATCH` iff score ≥ t; score == t → MATCH | MatcherIT::AC-24 enrolled pair is decided NO_MATCH or MATCH against threshold 0_5 with float64 score; MatcherIT::AC-24 score equal to threshold is a MATCH; DecisionTest::score equal to threshold is a MATCH; CosineTest::random normalised vectors agree with the float64 reference within 1e-9; acceptance `AC-24` | unit + IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-25 | Decision uses and records 0.9 | MatcherIT::AC-25 threshold changed before drain is used and stored; MatcherIT::AC-25 higher threshold turns a mid score into NO_MATCH | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-26 | Probe template no longer stored after decision | MatcherIT::AC-26 and AC-29 and AC-17 no active template fails SUBJECT_NOT_ENROLLED without retry and clears probe | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-27 | `FAILED`/`MAX_ATTEMPTS`; never claimed again | MatcherIT::AC-27 corrupt stored template is retried three times then FAILED MAX_ATTEMPTS and never claimed again; MatcherIT::AC-27 claim never takes a job that used all attempts even before the reaper ran; MatcherIT::AC-27 probe with a non-finite value is a processing error counted as an attempt | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-28 | Stalled job reclaimed and decided; lease + poll ≤ 30 s | MatcherIT::AC-28 job claimed by a stalled worker is reclaimed after lease expiry; MatcherIT::AC-28 claim does not take a job whose lease is still valid; MatcherPropertiesTest::defaults satisfy the 30 second bound; MatcherPropertiesTest::context fails when lease plus poll interval exceeds 30 seconds | unit + IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-29 | `FAILED`/`SUBJECT_NOT_ENROLLED` on first attempt, no retry | MatcherIT::AC-26 and AC-29 and AC-17 no active template fails SUBJECT_NOT_ENROLLED without retry and clears probe | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-30 | `200` with decision fields, no `reason`, no template data | VerificationIT::AC-30 own "MATCH" verification is 200 with score threshold and decidedAt; VerificationIT::AC-30 own "NO_MATCH" verification …; VerificationIT::API3 responses never contain the probe or template bytes | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-31 | `200` PENDING with null score/threshold/decidedAt | VerificationIT::AC-31 own PENDING verification is 200 with exactly six keys and no reason; VerificationIT::AC-31 a claimed verification is still reported as PENDING without decision fields; acceptance `AC-31` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-32 | `reason` present for FAILED, one of the 3 values | VerificationIT::AC-32 own FAILED verification with reason "SUBJECT_NOT_ENROLLED" / "SUBJECT_ERASED" / "MAX_ATTEMPTS" carries the reason and has seven keys | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-33 | `404` identical to not-found (never 400/500) | VerificationIT::AC-33 API1 BOLA - another tenant's, unknown and malformed ids give byte identical 404 problems; VerificationIT::AC-33 API1 non-canonical UUID aliases of an existing id are 404 …; acceptance `AC-33-other-tenant`, `AC-33-not-uuid` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-34 | 500 jobs, 3 workers: exactly one terminal status each, counts sum to 500 | ExactlyOnceIT::AC-34 500 verifications drained by 3 concurrent workers are decided exactly once each | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-35 | Stale writer changes 0 rows; metric not incremented | ExactlyOnceIT::AC-35 stale writer after lease expiry cannot overwrite the outcome written by the new holder; ExactlyOnceIT::AC-35 stale worker blocked mid-decision writes nothing and is not counted when it resumes; MetricsIT::AC-51 INV-1 a stale writer adds … no decided count | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-36 | Terminal job never claimed again | ExactlyOnceIT::AC-36 after all rows are terminal nothing can be claimed or reaped; ExactlyOnceIT::INV-1 raw update of a decided row is rejected by the database with check_violation | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-37 | `404`; A's data unchanged | TenantIsolationIT::API1 AC-37 BOLA - tenant B cannot read tenant A's decided verification and A's row is unchanged; TenantIsolationIT::API1 AC-37 … pending verification; ErasureIT::API1 AC-37 BOLA - tenant B cannot erase tenant A's subject and gets the same 404 as unknown | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-38 | B's verification of A's `s1` fails SUBJECT_NOT_ENROLLED; B's own s1 is v1 | TenantIsolationMatcherIT::API1 AC-38 BOLA - tenant B verifying s1 with A's exact template is FAILED SUBJECT_NOT_ENROLLED and never compared; TenantIsolationIT::API1 AC-38 gateway side - tenant B enrols its own s1 as version 1 …; TenantIsolationMatcherIT::API1 AC-38 once B enrols its own s1 B is compared with B's template only | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-39 | Cross-tenant and unknown 404: status/type/title/detail identical | TenantIsolationIT::API1 AC-39 BOLA - another tenant's id and an unknown id give identical status content type and full body | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-40 | Tenant B uses its own threshold (default 0.5) | TenantIsolationMatcherIT::API1 AC-40 tenant A with threshold 0_8 and tenant B with the default are each decided with their own threshold; TenantIsolationMatcherIT::AC-40 a threshold of tenant A set after B's verification was decided does not alter B's decided row | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-41 | No base64 template, token or Authorization value in logs/bodies | LeakageIT::A09 AC-41 valid and invalid templates on enrol, verify and threshold never reach logs or bodies; LeakageIT::A09 AC-41 a valid token used on every endpoint never reaches logs or bodies; LeakageIT::A09 AC-41 non-bearer and malformed Authorization header values never reach logs or bodies (22 cases) | IT (filter chain) | PASS – 2026-10-11 clean run |
| AC-42 | `413` problem detail for body > 8 KB; body not processed | EnrollmentIT (section "AC-42")::API4 body of 8193 bytes is 413; EnrollmentIT::API4 body of exactly 8192 bytes is not rejected for size; EnrollmentIT::API4 chunked JSON body above 8 KB without Content-Length is 413 problem; VerificationIT::API4 body larger than 8 KB is 413 and creates no row; acceptance `AC-42-size`, `HOSTILE-verify-size` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-43 | `400`/`415`; no stack trace, class, SQL or echoed input | EnrollmentIT::AC-43 A05 malformed body "truncated JSON" / "number instead of string" / "empty body" / … is a 400 problem (9 cases); EnrollmentIT::A05 text plain content type is 415 with a constant problem body; TransportHardeningIT::A10 malformed request line is a 400 without stack trace or echoed input; acceptance `AC-43-type` | IT (filter chain) + script | PASS – 2026-10-11 clean run |
| AC-44 | `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` | LeakageIT::A02 AC-44 every status of every v1 endpoint carries nosniff and no-store; LeakageIT::A02 AC-44 a 422 key reuse response carries nosniff and no-store; LeakageIT::A02 AC-44 the public health endpoint also carries nosniff and no-store | IT (filter chain) | PASS – 2026-10-11 clean run |
| AC-45 | `200` `{"threshold":0.7}`; later decisions use it; default 0.5 | ThresholdIT::AC-45 admin sets 0_7 and gets 200 with exactly the stored threshold and a row for the JWT tenant; ThresholdIT::AC-45 a tenant that never set a threshold has no row so the default 0_5 applies; TenantIsolationMatcherIT::AC-45 later decisions use the stored 0_7 …; acceptance `AC-45`, `AC-45-restore` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-46 | `400`, threshold unchanged; 0.0 and 1.0 accepted | ThresholdIT::AC-46 API4 invalid threshold body "-0.01" / "1.01" / "NaN literal" / "1e999 overflow" / … is 400 and leaves 0_8 unchanged (24 cases + 24 "creates no row"); ThresholdIT::AC-46 boundary 0.0 / 1.0 is accepted and echoed; acceptance `AC-46-low`, `-high`, `-string`, `-missing` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-47 | `204`; templates, probes, fingerprints gone; decided rows readable | ErasureIT::AC-47 erasure removes versions and probes, finishes pending as SUBJECT_ERASED and keeps decided rows unchanged; ErasureIT::AC-47 idempotency rows of the erased subject are deleted …; ErasureIT::AC-47 erasure is atomic …; acceptance `AC-47-enrol`, `AC-47`, `AC-47-scope` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-48 | `404` problem detail for unknown/erased subject | ErasureIT::AC-48 unknown subject gives 404 problem detail; ErasureIT::AC-48 second delete of an erased subject gives 404 identical to unknown; ErasureIT::AC-48 malformed subject id … gives the constant 400 (D-26); acceptance `AC-48`, `AC-48-malformed` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-49 | Pending verification of erased subject ends `FAILED`/`SUBJECT_ERASED` | ErasureIT::AC-49 a late matcher write after claim and erasure is a guarded no-op and the row stays SUBJECT_ERASED; ErasureIT::AC-49 an unclaimed pending verification is never claimable after erasure | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-50 | Re-enrolment after erasure: `201` version 1 | ErasureIT::AC-50 re-enrolling an erased subject gives 201 with version 1 | IT (Testcontainers) | PASS – 2026-10-11 clean run |
| AC-51 | Counter by result, queue gauge, processing timer, latency histogram; no tenant/subject labels | MetricsIT::AC-51 scrape exposes decided counter by result, processing timer and latency histogram with exact deltas; MetricsIT::AC-51 queue depth gauge equals the number of PENDING rows after refresh; MetricsIT::API3 no tenant or subject label on any matchgw series …; QueueDepthSchedulingIT::AC-51 queue depth keeps refreshing while drain is blocked mid-decision; acceptance `AC-51-counter`, `-gauge`, `-timer`, `-histogram`, `-labels` | IT (Testcontainers) + script | PASS – 2026-10-11 clean run |
| AC-52 | Non-health actuator endpoints not served (`401`/`404`) on public port | HealthEndpointIT::API8 no other actuator endpoint is reachable without a token [`/actuator/prometheus`, `/actuator/env`, `/actuator/metrics`, …]; HealthEndpointIT::API8 no other actuator endpoint is reachable even with a valid token; acceptance `AC-52`, `ACTUATOR` | IT (filter chain) + script | PASS – 2026-10-11 clean run |
| AC-53 | Eval enrols 1 000 (all 201), submits 5 000, polls to terminal; non-zero on FAILED | `python3 eval/eval.py` on the fresh clone: enrol 1000/1000 201, submit 5000/5000 202, poll 5000/5000 terminal, exit 0; eval/test_eval.py (21 tests OK, incl. NoRedirectTest::test_redirect_is_not_followed_so_the_token_never_leaves, TokenStoreTest::test_refresh_remints_once_per_stale_token) | script + unit | PASS – 2026-10-11 clean run |
| AC-54 | Prints FMR and FNMR at 0.5 with counts | eval.py output: FMR 0.5507 % (14 / 2542 impostors), FNMR 7.6485 % (188 / 2458 genuines); ErrorCountsTest (4 unit tests) | script + unit | PASS – 2026-10-11 clean run |
| AC-55 | Lowest threshold with FMR ≤ 0.1 %, its FNMR, 95 % FMR upper bound | eval.py output: threshold 0.651113, FMR 0.0787 %, FNMR 10.6184 %, Clopper-Pearson upper bound 0.2475 %; RecommendThresholdTest (4), UpperBoundTest (6) | script + unit | PASS – 2026-10-11 clean run |
| AC-56 | Score distributions, look-alikes, poor captures; two runs identical | eval.py output: distributions printed, look-alikes 14, poor captures 261; three runs (dev stack ×2 + fresh clone) print identical numbers (only run id and elapsed time differ); ReportTest::test_report_is_deterministic_and_order_independent | script + unit | PASS – 2026-10-11 clean run |
| AC-57 | Reports error rate, POST p99, k6-polled p95; server-side p95 recorded | `loadtest/run.sh` full 60 s on the fresh clone, exit 0: error rate 0.000 %, POST p99 17.5 ms, k6-polled p95 208.0 ms, server-side p95 196.1 ms; loadtest/test_server_p95.py (3 tests OK) | script + unit | PASS – 2026-10-11 clean run |
| AC-58 | README states machine, Docker limits, NFR-1/2/3 numbers, met / not met | NONE – README.md has no load-test section yet (still "Work in progress") | – | FAIL – README section missing (numbers available, see Metrics) |

## Acceptance run (`./scripts/acceptance.sh`)
Fresh clone, compose project `shipcheck`, 2026-10-11: **66 passed, 0 failed.** Tokens minted by the dev tool (AC-5), never printed.
`TOKEN_ALL` = `enroll verify admin`, `TOKEN_VERIFY` / `TOKEN_ENROLL` = single scope, `TOKEN_B` = another tenant.

| Check | Command | Expected | Actual | Result |
|---|---|---|---|---|
| HEALTH | `GET /actuator/health`, no token | 200, body `"status":"UP"` | 200, body matches | PASS (2 checks) |
| ACTUATOR | `GET /actuator/env`, no token | 401 | 401 | PASS |
| LIVENESS / READINESS | `GET /actuator/health/liveness`, `/readiness`, no token | 200 / 200 | 200 / 200 | PASS (2 checks) |
| AC-6-none | `GET /v1/anything`, no token | 401 | 401 | PASS |
| AC-6-tampered | `GET /v1/verifications/{unknown}` with last signature char swapped | 401 | 401 | PASS |
| AC-6-valid | `GET /v1/verifications/{unknown}` with a valid token | 404 | 404 | PASS |
| DENY-DEFAULT | `GET /v1/anything` with a valid token | 403 | 403 | PASS |
| API5-HEAD | `HEAD /v1/verifications/{id}` with enroll-only token | 403 | 403 | PASS |
| AC-7 | `PUT /v1/tenant/threshold` with verify-only token | 403 | 403 | PASS |
| AC-9 | `POST /v1/subjects/{s}/enrollment` valid template | 201, `"version":1` | 201, body matches | PASS (2 checks) |
| AC-10 | Same enrolment again | 201, `"version":2` | 201, body matches | PASS (2 checks) |
| AC-11-short | Enrol with `{"template":"AAAA"}` | 400 | 400 | PASS |
| AC-12-id | Enrol subject `a%20b` | 400 | 400 | PASS |
| AC-42-size | Enrol with a 9 000-char template (> 8 KB) | 413 | 413 | PASS |
| AC-43-type | Enrol with `Content-Type: text/plain` | 415 | 415 | PASS |
| AC-7-enrol | Enrol with verify-only token | 403 | 403 | PASS |
| AC-14 | `POST /v1/verifications` with Idempotency-Key | 202 | 202 | PASS |
| AC-31 | `GET /v1/verifications/{id}` own | 200, `"subjectId"` of the run | 200, body matches | PASS (2 checks) |
| AC-33-other-tenant | `GET` the same id with tenant B's token | 404 | 404 | PASS |
| AC-33-not-uuid | `GET /v1/verifications/not-a-uuid` | 404 | 404 | PASS |
| AC-15-key | `POST /v1/verifications` without Idempotency-Key | 400 | 400 | PASS |
| AC-17-unknown | Submit for a never-enrolled subject | 202 | 202 | PASS |
| AC-24 | Poll the enrolled subject's verification (same template) until terminal | MATCH | MATCH | PASS |
| AC-17-final | Poll the unknown subject's verification | 200, FAILED + SUBJECT_NOT_ENROLLED | 200, body matches | PASS (2 checks) |
| AC-45 | `PUT /v1/tenant/threshold` `{"threshold":0.7}` admin | 200, `"threshold":0.7` | 200, body matches | PASS (2 checks) |
| AC-46-low / -high / -string / -missing | PUT threshold `-0.01` / `1.01` / `"abc"` / `{}` | 400 each | 400 each | PASS (4 checks) |
| AC-7-threshold | PUT threshold with enroll-only token | 403 | 403 | PASS |
| AC-45-restore | PUT threshold `0.5` | 200, `"threshold":0.5` | 200, body matches | PASS (2 checks) |
| AC-47-enrol | Enrol a throwaway subject | 201 | 201 | PASS |
| AC-47 | `DELETE /v1/subjects/{s}` admin | 204 | 204 | PASS |
| AC-48 | Second `DELETE` of the erased subject | 404 | 404 | PASS |
| AC-48-malformed | `DELETE /v1/subjects/a%20b` | 400 (D-26) | 400 | PASS |
| AC-47-scope | `DELETE` with verify-only token | 403 | 403 | PASS |
| AC-18-first | Submit with a fresh Idempotency-Key | 202 | 202 | PASS |
| AC-18 | Replay same key + body | same `verificationId`, 200 or 202 | same id (202) | PASS |
| AC-19 | Same key, different subjectId | 422 | 422 | PASS |
| AC-20 | Same key + body with tenant B's token | 202, own `verificationId` | 202, B got its own id | PASS (2 checks) |
| AC-51-counter / -gauge / -timer / -histogram | Scrape `/actuator/prometheus` on each matcher's internal 8081 (bash `/dev/tcp`) | `matchgw_verifications_decided_total{result=…}`, `matchgw_queue_depth`, `matchgw_matcher_processing_seconds_count`, `matchgw_verification_latency_seconds_bucket` present | all present | PASS (4 checks) |
| AC-51-labels | Same scrape | no tenant/subject label on `matchgw_` series | none | PASS |
| AC-52 | `GET /actuator/prometheus` on public 8080 | 401 | 401 | PASS |
| HOSTILE-* (11) | See Hostile inputs | 400 / 413 | as expected | PASS (11 checks) |

## Hostile inputs
Script rows: `HOSTILE-*` checks from the fresh-clone acceptance run. IT rows: JUnit XML of the clean run (all PASS).

| Endpoint | Input | Expected | Actual | Result |
|---|---|---|---|---|
| POST /v1/verifications | `probeTemplate":"AAAA"` (HOSTILE-verify-short) | 400 | 400 | PASS |
| POST /v1/verifications | `subjectId":"a b"` (HOSTILE-verify-id) | 400 | 400 | PASS |
| POST /v1/verifications | `subjectId":123` (HOSTILE-verify-type) | 400 | 400 | PASS |
| POST /v1/verifications | 9 000-char probe, > 8 KB (HOSTILE-verify-size) | 413 | 413 | PASS |
| POST /v1/verifications | duplicate JSON key `subjectId` (HOSTILE-verify-dupkey) | 400 | 400 | PASS |
| POST /v1/verifications | VerificationIT::AC-16 API4 invalid probeTemplate (missing, null, 511 bytes, NaN, norm 1.02, URL-safe, no padding) and invalid subjectId (65 chars, space, missing, null, number, float, boolean, duplicate key, empty); VerificationIT::AC-15 bad Idempotency-Key (empty, 65 chars, space, non-ASCII, repeated header); VerificationIT::API3 mass assignment - extra body fields cannot set status, score, tenant or id; VerificationIT::API4 malformed JSON | 400, no row, no echoed input | 400, no row | PASS |
| POST /v1/subjects/{id}/enrollment | `{"template":` truncated JSON (HOSTILE-enrol-json) | 400 | 400 | PASS |
| POST /v1/subjects/{id}/enrollment | `{"template":null}` (HOSTILE-enrol-null) | 400 | 400 | PASS |
| POST /v1/subjects/{id}/enrollment | EnrollmentIT::AC-11 A05 invalid template (16 cases: 511/513 bytes, NaN, ±Inf, norm 0.98/1.02, all zero, URL-safe, padding, whitespace); EnrollmentIT::AC-43 malformed bodies (9); EnrollmentIT::AC-12 bad ids (65 chars, space, `ä`, `;`, `@`); EnrollmentIT::API4 multipart is 415 and not parsed; EnrollmentIT::A05 Accept text/html is 406 and creates nothing | 400 / 406 / 415, no version created, constant body | as expected | PASS |
| PUT /v1/tenant/threshold | `{"threshold":NaN}` (HOSTILE-threshold-nan) | 400 | 400 | PASS |
| PUT /v1/tenant/threshold | `{"threshold":null}` (HOSTILE-threshold-null) | 400 | 400 | PASS |
| PUT /v1/tenant/threshold | ThresholdIT::AC-46 API4 invalid threshold body (24 cases: ±0.01 out of range, integer 2, string, numeric string, null, missing, NaN/Infinity literal and string, ±1e999 overflow, boolean, array, object, bare number, JSON null body, array body) – each "leaves 0_8 unchanged" and "creates no row"; ThresholdIT::API5 a forbidden request is rejected before the body is read so a malformed body is still 403 | 400 (403 without scope), state unchanged | as expected | PASS |
| GET /v1/verifications/{id} | `..%2F..%2Fetc` path traversal (HOSTILE-get-traversal) | 400 | 400 | PASS |
| GET /v1/verifications/{id} | VerificationIT::AC-33 API1 non-canonical UUID aliases and malformed ids; TenantIsolationIT::API1 AC-39 another tenant's id vs unknown id | identical 404 | identical 404 | PASS |
| DELETE /v1/subjects/{id} | 65-char id (HOSTILE-delete-id) | 400 | 400 | PASS |
| DELETE /v1/subjects/{id} | ErasureIT::AC-48 malformed subject id (`bad%20id`, `s%24`, `caf%C3%A9`, 65 chars); ErasureIT::API1 AC-37 other tenant's subject | 400 constant body / 404 as unknown, nothing changes | as expected | PASS |
| All /v1 | LeakageIT::A09 AC-41 (22 cases): templates, tokens and Authorization values on every endpoint, every status, replay/422, erasure, oversized and wrongly typed bodies never appear in logs or bodies | no echo | no echo | PASS |
| All /v1 (transport) | TransportHardeningIT: malformed request line → 400 without stack trace; oversized header line → 400; anonymous unfinished chunked body on PUT/PATCH/DELETE/POST → 401 without reading the body; anonymous 200 KB chunked form → 401; 200 KB multipart on health → size limit; invalid multipart boundary → 415 never 400/500 | as listed | as listed | PASS |
| All /v1 (auth) | JwtAuthenticationIT: 24 negative tokens (alg none, HS256 key confusion, other key, tampered payload, wrong iss/aud, missing/malformed tenant_id incl. newline, slash, quote, 65 chars, number; TTL > 900 s; future iat/nbf); `access_token` query parameter; non-bearer schemes | 401, byte-identical body | as expected | PASS |

## Metrics
Load test: fresh clone, `loadtest/run.sh` full mode (k6 `grafana/k6`, constant 300 iterations/s for 60 s, 3 tenants, 3 matcher replicas), exit 0.
Machine: Apple M3 Pro, 12 cores, 36 GiB RAM, macOS 26.6.2, Docker Desktop 29.8.0 (VM: 12 CPUs, 7.65 GiB).
Compose limits (`compose.yaml`): gateway `mem_limit: 768m`, each of 3 matchers `mem_limit: 512m`, gateway and matchers
`pids_limit: 512` and `/tmp` tmpfs 64 MB; postgres has no limit; no CPU limits.

| Metric | Definition | Value | Target | Source |
|---|---|---|---|---|
| NFR-1 error rate | `http_req_failed{name:submit}` over the 60 s window | 0.000 % of 18 000 submits (~300.0/s, 0 dropped iterations) | < 0.1 % at 300/s for 60 s | clone-k6.txt – met |
| NFR-2 server-side p95 | p95 of `decidedAt − createdAt` (matcher histogram, before/after scrape of every replica) | 196.1 ms over 18 000 decisions | ≤ 500 ms (POST → final status) | clone-k6.txt – met |
| NFR-2 k6-polled p95 | Client-side time to final status, 10 % of submits polled every 50 ms | 208.0 ms (MATCH 821, NO_MATCH 991, FAILED 0, timeout 0) | ≤ 500 ms (cross-check) | clone-k6.txt – met |
| NFR-3 POST latency | `POST /v1/verifications` duration | p95 4.1 ms, p99 17.5 ms | p99 ≤ 100 ms | clone-k6.txt – met |
| FMR @ 0.5 | false matches / impostors at the default threshold | 0.5507 % (14 / 2 542) | – (reported) | clone-eval.txt |
| FNMR @ 0.5 | false non-matches / genuines at the default threshold | 7.6485 % (188 / 2 458) | – (reported) | clone-eval.txt |
| Recommended threshold | lowest observed score with FMR ≤ 0.1 % | 0.651113 | FMR ≤ 0.1 % | clone-eval.txt |
| FMR @ recommended | false matches / impostors | 0.0787 % (2 / 2 542) | ≤ 0.1 % | clone-eval.txt |
| FNMR @ recommended | false non-matches / genuines | 10.6184 % (261 / 2 458) | – (reported) | clone-eval.txt |
| FMR 95 % upper bound | one-sided Clopper-Pearson, n = 2 542, 2 errors | 0.2475 % | – (reported; above the 0.1 % point estimate target) | clone-eval.txt |
| Look-alikes | impostors with score ≥ 0.5 | 14 | – | clone-eval.txt |
| Poor captures | genuines below the recommended threshold | 261 | – | clone-eval.txt |
| Decision consistency | server decisions inconsistent with score ≥ recorded threshold | 0 | 0 | clone-eval.txt |
| Eval reproducibility | numbers of independent runs | identical across 3 runs (dev stack ×2, fresh clone) | identical | eval1.txt, eval2.txt, clone-eval.txt |
| Score distributions | min / p5 / median / p95 / max | GENUINE 0.276182 / 0.460709 / 0.739424 / 0.792079 / 0.847938; IMPOSTOR −0.276409 / −0.151657 / −0.000487 / 0.147158 / 0.706647 | – | clone-eval.txt |
| Test suite | JUnit tests in the clean run | 517 run, 0 failed, 0 skipped | 0 failed | build/test-results XML |

## Coverage summary
- ACs in SPEC: 58. Each appears exactly once in the matrix.
- Covered with passing evidence: 57. Uncovered / failing: 1 – **AC-58** (README has no load-test section; the numbers
  exist in Metrics above).
- AC-1 is PASS with partial evidence: the fresh-clone stack came up healthy and passed acceptance, and no key file is
  tracked, but the "≤ 3 min" startup bound was not timed in the captured log.
- Evidence only from scripts / manual runs (no JUnit test): AC-1 (fresh-clone compose), AC-4 (verify.sh itself),
  AC-53..AC-57 (eval and k6 runs; their pure-function parts have Python unit tests, but the end-to-end behaviour is only
  proven by the scripts). AC-24's end-to-end MATCH via 3 replicas is also script-only, its logic is covered by ITs.
- Tests whose names do not carry the AC id (mapped via KDoc or behaviour): AC-2, AC-3, AC-5, AC-6 (KDoc "AC-6" in
  JwtAuthenticationIT), AC-42 (EnrollmentIT section comment "AC-42"), AC-52 (HealthEndpointIT).
- Inconsistency: CosineTest and DecisionTest KDoc cite "AC-3" for the cosine / MATCH rule, but SPEC AC-3 is migration
  ownership; that behaviour belongs to AC-24 (DecisionTest also cites AC-24). No test references an AC id beyond AC-58.
