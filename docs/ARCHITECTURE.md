# Architecture

> From the architect subagent via /plan (2026-10-11), with the staff-engineer self-critique applied (see PLAN.md).
> Decisions with lasting impact: docs/adr/ADR-001..ADR-008. Domain model, invariants (INV-x) and misuse cases (MC-x): docs/analysis/ANALYSIS.md.

## 1. Ambiguities & assumptions

| id | question / assumption | impact | default chosen | must-confirm |
|---|---|---|---|---|
| AM-1 | **Where does the dev token tool live?** D-7 names three modules (`gateway`, `matcher`, `common`). D-4 needs a minting tool. That tool must not sit in the gateway, because the gateway must never hold the private key. Python stdlib cannot sign RS256. A shell/openssl JWT would be hand-rolled, which CLAUDE.md §4 forbids. | Module layout, AC-1, AC-5 | A 4th Gradle module **`devtoken`**: plain Kotlin, no Spring Boot, uses `NimbusJwtEncoder` from `spring-security-oauth2-jose`. It has two commands. `keygen` runs as a one-shot compose service. `mint` runs as `docker compose run --rm devtoken mint …`, wrapped by `scripts/token.sh`. | **yes** |
| AM-2 | **Dev DB password.** No literal password in git (AC-1); the guard hook blocks literal defaults in YAML. | AC-1, gitleaks | **Resolved by D-21 (step 1b):** one-shot `db-init` writes 32 random bytes (hex) into the `dbcreds` volume; postgres reads `POSTGRES_PASSWORD_FILE`, the apps use `SPRING_CONFIG_IMPORT=optional:configtree:/run/dbcreds/`. DB port not published. | no (resolved) |
| AM-3 | **Scope check order.** `@PreAuthorize` runs after `@RequestBody` binding, so a caller without the scope gets body parsing and a 400 validation oracle instead of 403. | AC-7 semantics, A01/API5 | **Critique recommends:** URL-level `authorizeHttpRequests` rules (`hasAuthority('SCOPE_x')` per method + path) in the SecurityFilterChain – reject before the body is read; tenant ownership stays in SQL. Requires changing CLAUDE.md §4 "@PreAuthorize for role checks". | **yes** |
| AM-4 | **osv-scanner lockfile.** `verify.sh` runs `osv-scanner … --lockfile gradle.lockfile`. A multi-module build writes per-module lockfiles. | verify.sh fails wherever osv-scanner is installed | Turn on `dependencyLocking { lockAllConfigurations() }` in every module. Change the line to `osv-scanner scan source -r .`, which auto-detects lockfiles. | **yes** (human's gate script) |
| AM-5 | **Public health path.** | AC-2 | **Resolved by D-23** (supersedes D-19): standard actuator health on the API port (no separate management port), probes enabled, `EndpointRequest.to(HealthEndpoint)` permitted; see ADR-006 amendment 2. | no (resolved) |
| AM-6 | Persistence uses `JdbcClient` (via the data-jdbc starter) with hand-written SQL, not Spring Data JDBC repositories. Every write is a locking, conditional or upsert statement (`SKIP LOCKED`, `ON CONFLICT`, guarded `UPDATE`). | Code style | Use JdbcClient only; recorded in ADR-003. | no |
| AM-7 | ASSUMPTION: a DB trigger makes terminal verification rows immutable. This is defence in depth for INV-1. | AC-34..36, AC-49 | Add it in V2 (step 6). | no |
| AM-8 | How D-8 is implemented: erasure **terminally fails** PENDING/claimed verifications (`FAILED`/`SUBJECT_ERASED`, probe NULL) in the same transaction as the delete. A worker's later guarded write then affects 0 rows. | AC-49 | As stated. | no |
| AM-9 | Residual race: a verification POSTed *concurrently* with erasure may commit after the erasure statement. It then ends `SUBJECT_NOT_ENROLLED`, not `SUBJECT_ERASED`. Its probe lives until that decision, which takes milliseconds. | AC-49 edge | Accept and document. | no |
| AM-10 | ASSUMPTION – token hardening beyond AC-6: require `sub`, `iat`, `exp`; reject `exp − iat > 900 s`; `tenant_id` must match `^[A-Za-z0-9._-]{1,64}$`. | AC-6 / NFR-10 | Enforce in JwtDecoder validators. | no |
| AM-11 | AC-22 says "injected clock". All timestamps use the DB `now()`, so there is one clock across containers. The test back-dates the stored idempotency row by 25 h instead of injecting a `Clock`. | AC-22 test technique | Back-date the row. | no |
| AM-12 | Body of an idempotent replay: `{"verificationId","status":<current>}` plus `Location`. Status code 202 if PENDING, else 200. | AC-18 | As stated. | no |
| AM-13 | Strict base64: exactly 684 chars, standard alphabet, `=` padding, no whitespace. Java's basic decoder alone accepts missing padding. The dataset uses padded `b64encode`. | AC-11 | Strict check. | no |
| AM-14 | 401 always sends a plain `WWW-Authenticate: Bearer` and a constant body. Spring's default `BearerTokenAuthenticationEntryPoint` puts the rejection reason into `error_description`. | AC-6 | Custom entry point. | no |
| AM-15 | `a%2Fb`: Tomcat or Spring Security's `StrictHttpFirewall` may reject before MVC. The body may then not be problem+json. | AC-12 | Register a `RequestRejectedHandler` that writes the constant 400 problem body. Also permit `/error` and set `server.error.include-*=never`. Verify the Tomcat-level behaviour in the test. | no |
| AM-16 | springdoc-openapi is listed in the stack, but no Boot-4-verified version is known. It is not in the scope budget. | API docs, ZAP API scan | Won't now (README limitation). | no |
| AM-17 | ZAP: `scripts/owasp-scan.sh` (referenced in SECURITY.md) does not exist. | Evals | Ship-check decides. It is of low value without an OpenAPI doc. | no |
| AM-18 | D-16: JDK 25 has no built-in UUIDv7 as far as I know (verify). | Ids | `UUID.randomUUID()` (v4, SecureRandom). | no |
| AM-19 | **Superseded by D-27 (gateway exposes no metrics; erasure transitions are not counted).** FAILED/SUBJECT_ERASED transitions happen in the gateway. The gateway therefore also increments `matchgw_verifications_decided_total{result="FAILED"}`, and Prometheus sums across apps. | AC-51 | As stated. | no |
| AM-20 | AC-34 runs "3 workers" as 3 concurrent claimer threads against one Postgres in one JVM. Postgres is the only coordination point. Real 3-JVM competition happens in compose (eval run, acceptance). | AC-34 evidence strength | As stated; README says so. | no |
| AM-21 | Matcher defaults: lease 10 s, idle poll 200 ms, batch 20, retry backoff after an exception 1 s. Lease + poll = 10.2 s, which is ≤ 30 s. NFR-2 risk: on average +100 ms of poll wait. | AC-28, NFR-2 | As stated. LISTEN/NOTIFY wake-up is a tuning lever, Won't now. | no |
| AM-22 | Matchers depend on postgres only, not on the gateway. They tolerate missing tables: a poll error is logged with SQLState only and retried. This satisfies "start concurrently" in AC-3. | AC-3 | As stated. | no |
| AM-23 | The body limit is an app filter registered **after** bearer authentication. Unauthenticated large bodies get 401 without being read. | AC-42 | As stated. | no |
| AM-24 | Token TTL is 15 min. The eval tool re-mints tokens per phase (enrol / submit / poll). | AC-53 | As stated. | no |
| AM-25 | Gateway-only `securityTest` Gradle task: runs JUnit tests tagged `security`, and they still run in `build`. | Evals | As stated. | no |

## 2. Scope cut for 180 min

**Must (sequential, critical path):**
- US-1 skeleton: AC-1..4
- US-2 JWT + dev tokens: AC-5..8
- US-3 enrol + validation: AC-9..13
- US-4 submit: AC-14..17
- US-6 matcher: AC-24..29
- US-7 read: AC-30..33
- US-8 exactly-once proof: AC-34..36
- US-9 isolation: AC-37..40
- US-10 leakage and limits: AC-41..44
- US-11 threshold: AC-45..46
- US-14 eval tool: AC-53..56. Written in parallel by a subagent (D-17 option 3). The run is in Ship-check if step 10 has no time.

**Should (in scope, D-2 / D-17):**
- US-12 erasure: AC-47..50. In the MVP and never cut.
- US-5 idempotency: AC-18..23.
- US-13 metrics: AC-51..52.
- US-15 k6 script: AC-57. Written in parallel. The 60 s run and the README numbers (AC-58) happen in Ship-check (D-17 option 2).

**Won't now** (SPEC "Non-goals", D-2, D-14): container-kill crash test, rate limiting / 429, encryption at rest, long-poll/webhook, Grafana, per-tenant metrics, retention job, token endpoint or user store, audit trail (MC-8), score hiding (MC-7), fair queueing, springdoc (AM-16), ArchUnit, LISTEN/NOTIFY.

## 3. Domain model (reused from ANALYSIS.md §4)

- **Entities** (ANALYSIS classDiagram): Tenant (threshold, default 0.5), Subject (tenant-scoped), TemplateVersion (version, active, template – special category), Verification (status, score, thresholdUsed, reason, createdAt, decidedAt, attempts, lease), Probe (transient, stored as a column on Verification), IdempotencyRecord (key ≤ 64, HMAC fingerprint, 24 h).
- **States** (ANALYSIS stateDiagram): `PENDING → (CLAIMED) → MATCH | NO_MATCH | FAILED`.
  - CLAIMED is not a stored status. It is `status='PENDING' AND lease_token IS NOT NULL AND lease_until >= now()`. The API shows it as PENDING (A-4).
  - `PENDING → FAILED(SUBJECT_ERASED)` is done by the gateway's erasure transaction (D-8).
- **How each invariant is enforced:**

| INV | Enforcement |
|---|---|
| INV-1 exactly-once | Guarded terminal `UPDATE … WHERE status='PENDING' AND lease_token=:token`. The applied count must be 1 before the metric is incremented. A V2 trigger rejects any UPDATE of a terminal row. CHECK `(status='PENDING') = (decided_at IS NULL)`. |
| INV-2 one active version | `subject` row upsert serialises enrolments of one subject. Partial UNIQUE index `(tenant_id, subject_id) WHERE active`. |
| INV-3 threshold at decision time | The matcher reads `tenant_settings` in the same transaction as the terminal write and stores it in `verification.threshold`. |
| INV-4 tenant isolation | Every SQL statement has a `tenant_id = :t` predicate, taken from the JWT `tenant_id` claim, never from the request body or path. The matcher loads the template by `(tenant_id, subject_id)` of the job row. |
| INV-5 erasure | One transaction: delete the `subject` row (cascades to `template_version`), terminally fail pending verifications with `probe=NULL`, delete idempotency rows. |
| INV-6 idempotency scope | PK `(tenant_id, idem_key)`; `ON CONFLICT … DO UPDATE … WHERE created_at < now()-24h`. |
| INV-7 attempts | Every claim does `attempts+1`; claims only take `attempts < 3`. A reaper fails rows with `attempts ≥ 3` whose lease has expired or been released, with `MAX_ATTEMPTS`. CHECK `attempts BETWEEN 0 AND 3`. |

## 4. Architecture

### Components and packages

```
common/    com.vmartis.matchgateway.common.template   TemplateCodec (strict base64 → 512 B → FloatArray LE; finite; norm 0.99..1.01), Cosine (float64)
           com.vmartis.matchgateway.common.verification VerificationStatus, FailureReason (enums)
           – pure Kotlin, NO Spring dependency (Gradle-enforced domain purity)
gateway/   …gateway.auth.infra        SecurityConfig, JwtConfig (decoder + validators), ProblemAuthenticationEntryPoint, ProblemAccessDeniedHandler
           …gateway.auth.api          TenantPrincipal (tenant_id from Jwt)
           …gateway.shared.api        ProblemDetailsAdvice (the ONE @RestControllerAdvice), BodySizeLimitFilter, ProblemTypes
           …gateway.subject.{api,infra}       EnrollmentController + SubjectController(DELETE), SubjectId validation; SubjectStore (enrol, erase)
           …gateway.verification.{api,domain,infra} VerificationController (POST/GET), DTOs; IdempotencyOutcome; VerificationStore, Fingerprinter (HMAC)
           …gateway.tenant.{api,infra}        ThresholdController; TenantSettingsStore
           …gateway.metrics.infra     QueueDepthGauge
           resources/db/migration     V1__schema.sql, V2__terminal_immutable.sql   (Flyway owned by gateway, D-7)
matcher/   …matcher.decision.domain   Decision (score, threshold → MATCH/NO_MATCH), Job
           …matcher.decision.infra    JobQueue (claim, reap, complete, release), MatcherWorker (drain loop, @Scheduled), MatcherProperties
devtoken/  …devtoken                  Main: `keygen`, `mint --tenant --sub --scope [--ttl]` (AM-1)
```

**Compose services:**
- `postgres` (17, pinned by digest, `-c log_error_verbosity=terse`, no host port)
- `keygen` (devtoken image, one-shot). It writes:
  - volume `issuer-keys`: `jwt-private.pem` mode 0400 and `jwt-public.pem`
  - volume `gateway-keys`: `jwt-public.pem` and `idempotency-hmac.key` (32 random bytes)
- `gateway` (8080 published; 8081 `expose` only; mounts `gateway-keys:ro`; `depends_on` keygen `service_completed_successfully` and postgres `service_healthy`)
- `matcher` (`deploy.replicas: 3`, 8081 internal only, `depends_on` postgres)
- `devtoken` (`profiles: [tools]`, mounts `issuer-keys:ro`)

Other build and runtime choices:
- One multi-stage `Dockerfile`: a build stage on `eclipse-temurin:25-jdk` with a BuildKit Gradle cache mount, and targets `gateway`, `matcher`, `devtoken` on `eclipse-temurin:25-jre`. Non-root uid 10001. Check whether curl is in the image for healthchecks; if not, `apt-get install curl`.
- No JDK needed on the host (AC-1).
- `.gitignore` covers `*.pem`, `*.key` and `dataset/*.jsonl`.

**Gateway configuration:**
- JDBC: Hikari `data-source-properties.logServerErrorDetail=false`, so PSQLException messages never inline row values. Without it, a "Failing row contains" message would include the probe bytes.
- Virtual threads on.
- `server.error.include-*=never`.
- No request-body logging.
- An empty CORS allowlist (machine callers only).

### Matcher protocol (D-3, ADR-003)

> **Superseded by D-28 / ADR-009 (RabbitMQ + transactional outbox).** This section describes the implemented
> state until PLAN steps 11–14 land; step 14 rewrites it.

**Claim** (one short transaction):

```sql
WITH next AS (
  SELECT id FROM verification
  WHERE status = 'PENDING' AND attempts < 3
    AND (lease_until IS NULL OR lease_until < now())
  ORDER BY created_at
  LIMIT :batch
  FOR UPDATE SKIP LOCKED)
UPDATE verification v
   SET attempts = v.attempts + 1, lease_token = :token, lease_until = now() + :lease
  FROM next WHERE v.id = next.id
RETURNING v.id, v.tenant_id, v.subject_id, v.probe, v.attempts, v.created_at;
```

`:token` is a fresh random UUID per claim call.

**Reaper** (start of each poll):

```sql
UPDATE verification
   SET status='FAILED', reason='MAX_ATTEMPTS', probe=NULL, lease_token=NULL, lease_until=NULL, decided_at=now()
 WHERE status='PENDING' AND attempts >= 3 AND (lease_until IS NULL OR lease_until < now())
RETURNING id, created_at, decided_at;
```

A lease expiry and a processing exception count the same way: every claim that does not end in a terminal write is a failed attempt (A-2).

**Process** (per job, one transaction):
1. `SELECT template FROM template_version WHERE tenant_id=:t AND subject_id=:s AND active`.
2. `SELECT threshold FROM tenant_settings WHERE tenant_id=:t`; use 0.5 if there is no row.
3. Decide in the domain:
   - no template → `FAILED`/`SUBJECT_NOT_ENROLLED` (AC-29, no retry);
   - otherwise compute the cosine in float64; `score >= t` → MATCH.

**Terminal write (the guard):**

```sql
UPDATE verification
   SET status=:status, score=:score, threshold=:threshold, reason=:reason,
       probe=NULL, lease_token=NULL, lease_until=NULL, decided_at=now()
 WHERE id=:id AND status='PENDING' AND lease_token=:token
RETURNING created_at, decided_at;
```

A stale writer (worker A) affects 0 rows in either case:
- worker B's reclaim replaced `lease_token`, or
- the row is already terminal (B decided it, the reaper failed it, or erasure failed it).

The result counter and the latency histogram are recorded only when 1 row is returned.

**Release on exception:**

```sql
UPDATE verification SET lease_token=NULL, lease_until=now()+:retryDelay
 WHERE id=:id AND status='PENDING' AND lease_token=:token
```

Only the exception class and the job UUID are logged, never the message.

**Erasure race (D-8):**
- Erasure UPDATEs pending rows to `FAILED`/`SUBJECT_ERASED` with `probe=NULL, lease_token=NULL`.
- A worker that loaded the template before the delete and writes after it gets 0 rows, so the job is never MATCH/NO_MATCH after the 204.
- A worker whose write committed before the erasure produced a decision before the 204. That is allowed. Its probe is already NULL.
- Lock order is the same everywhere: the `subject` row first (enrol and erase), then verification rows. Claim skips locked rows. The terminal write waits for erasure's row lock, then re-evaluates `status='PENDING'` and gets false.

**30 s bound (AC-28):**
- A crash at time t leaves `lease_until ≤ t+10 s`.
- The next idle poll (≤ 200 ms later) reclaims the job and decides it in milliseconds: ≤ t+10.3 s.
- If the crash happened on attempt 3, the reaper turns it into `FAILED`/`MAX_ATTEMPTS` in the same window, which is still "decided".
- `MatcherProperties` checks `lease + pollInterval ≤ 30 s` at startup and fails fast otherwise.
- All lease arithmetic uses the DB `now()`, so there is no clock skew between containers.

**Loop:** `@Scheduled(fixedDelay = poll-interval)` calls `drain()`. `drain()` runs reap, then claims batches until a batch comes back short. Disabled in tests with `matcher.scheduling.enabled=false`; tests call `drain()` from N threads.

### Data model (V1, plus V2 trigger)

| Table | Column | Type | Constraints | PII / biometric – why stored |
|---|---|---|---|---|
| tenant_settings | tenant_id | varchar(64) | PK | internal |
| | threshold | double precision | NOT NULL, CHECK 0 ≤ x ≤ 1 | internal – brief L31 |
| | updated_at | timestamptz | NOT NULL | – |
| subject | tenant_id, subject_id | varchar(64), varchar(64) | PK (tenant_id, subject_id); CHECK subject_id ~ `^[A-Za-z0-9._-]{1,64}$` | subject_id = pseudonymous PII – needed for the per-subject version counter and erasure (404 if absent) |
| | last_version | int | NOT NULL, CHECK ≥ 1 | – |
| | created_at | timestamptz | NOT NULL | – |
| template_version | tenant_id, subject_id, version | varchar, varchar, int | PK (all three); FK (tenant_id, subject_id) → subject ON DELETE CASCADE | – |
| | template | bytea | NOT NULL, CHECK octet_length = 512 | **biometric, special category** – needed to match; kept until erasure (D-14); raw LE float32, not base64 |
| | active | boolean | NOT NULL; **UNIQUE INDEX (tenant_id, subject_id) WHERE active** | – |
| | created_at | timestamptz | NOT NULL | – |
| verification | id | uuid | PK (random v4, gateway-generated) | – |
| | tenant_id, subject_id | varchar(64) | NOT NULL (no FK – the subject may be unknown, BR-6) | subject_id PII; kept with results (brief L28) |
| | status | varchar(16) | NOT NULL, CHECK IN (PENDING, MATCH, NO_MATCH, FAILED) | – |
| | probe | bytea | CHECK `(status='PENDING') = (probe IS NOT NULL)`; CHECK length 512 | **biometric** – needed only until decided (D-11); the schema enforces AC-26 |
| | score, threshold | double precision | CHECK `status NOT IN (MATCH,NO_MATCH) OR (score, threshold NOT NULL)` | biometric-derived score; kept as the result |
| | reason | varchar(32) | CHECK IN (SUBJECT_NOT_ENROLLED, SUBJECT_ERASED, MAX_ATTEMPTS); CHECK `(status='FAILED') = (reason IS NOT NULL)` | – |
| | attempts | int | NOT NULL DEFAULT 0, CHECK 0..3 | – |
| | lease_token | uuid | NULL; CHECK `status='PENDING' OR lease_token IS NULL` | – |
| | lease_until | timestamptz | NULL | – |
| | created_at | timestamptz | NOT NULL DEFAULT now() | – |
| | decided_at | timestamptz | CHECK `(status='PENDING') = (decided_at IS NULL)` | – |
| | indexes | | `(created_at) WHERE status='PENDING'` (claim and queue depth); `(tenant_id, subject_id)` (erasure) | |
| idempotency_key | tenant_id, idem_key | varchar(64), varchar(64) | PK (tenant_id, idem_key) | – |
| | fingerprint | bytea | NOT NULL, CHECK length 32 | HMAC-SHA256(tenant ‖ 0 ‖ subjectId ‖ 0 ‖ probe bytes) – biometric derivative, keyed so it cannot be linked (D-10); 24 h; deleted on erasure (A-S3) |
| | verification_id | uuid | NOT NULL, FK → verification(id); INDEX | – |
| | created_at | timestamptz | NOT NULL | – |

V2 (step 6) adds a `BEFORE UPDATE ON verification FOR EACH ROW WHEN (OLD.status <> 'PENDING')` trigger that raises an exception.

Not stored: tokens, JWT `sub`, request bodies, raw idempotency body.

### API

Problem types are URNs `urn:matchgw:problem:<slug>`. Every body is a constant per type. Bodies never echo input, exception text, SQL, token or template.

| method | path | request | response | errors (status + type) | role | owner check |
|---|---|---|---|---|---|---|
| GET | /actuator/health (8080 via group path; 8081) | – | 200 `{"status":"UP"}` | – | public | – |
| GET | /actuator/prometheus (8081, compose network only) | – | Prometheus text | on 8080: 401 `unauthorized` (denyAll) | none (network-isolated, D-6) | – |
| POST | /v1/subjects/{subjectId}/enrollment | `application/json` `{"template": base64}` ≤ 8 KB | 201 `{"subjectId","version"}` | 400 `invalid-request`; 401 `unauthorized`; 403 `forbidden`; 413 `payload-too-large`; 415 `unsupported-media-type` | SCOPE_enroll | the subject is created or updated **in the caller's tenant only** (namespace = tenant_id) |
| DELETE | /v1/subjects/{subjectId} | – | 204 | 400 `invalid-request` (bad id); 401; 403; 404 `not-found` | SCOPE_admin | `DELETE … WHERE tenant_id=:jwtTenant AND subject_id=:s`; 0 rows → 404 |
| POST | /v1/verifications | header `Idempotency-Key` 1..64 printable ASCII; `{"subjectId","probeTemplate"}` | 202 `{"verificationId","status":"PENDING"}` + `Location: /v1/verifications/{id}`; replay 202/200 | 400; 401; 403; 413; 415; 422 `idempotency-key-reused` | SCOPE_verify | the verification is stored with the JWT tenant; idempotency PK includes the tenant |
| GET | /v1/verifications/{verificationId} | – | 200 `{"verificationId","subjectId","status","score","threshold","decidedAt"}` + `reason` only when FAILED (`@JsonInclude(NON_NULL)` on that field only; annotations stay in `com.fasterxml.jackson.annotation` under Jackson 3) | 401; 403; 404 `not-found` (unknown, other tenant, or non-UUID – all identical, A-S2) | SCOPE_verify | `WHERE id=:id AND tenant_id=:jwtTenant` |
| PUT | /v1/tenant/threshold | `{"threshold": number}` | 200 `{"threshold"}` (A-S1) | 400; 401; 403; 413; 415 | SCOPE_admin | the row key is the JWT tenant only |

All `/v1` responses carry the Spring Security default headers: `nosniff`, and `Cache-Control: no-cache, no-store, …`. Added on top: `Content-Security-Policy: frame-ancestors 'none'` and `Referrer-Policy: no-referrer`. HSTS is only sent over HTTPS; TLS is not terminated in compose, which is a known limitation.

**Error mapping in `ProblemDetailsAdvice`** (extends `ResponseEntityExceptionHandler`):
- It overrides `handleExceptionInternal` and replaces every body with the constant one for that status.
- It has an **explicit** `@ExceptionHandler(AccessDeniedException)` → 403. Without it, the catch-all would turn `@PreAuthorize`'s `AuthorizationDeniedException` into a 500.
- The catch-all → 500 `internal`. It logs only the exception class.

## 5. Threat model (STRIDE-lite)

**Assets:**
- enrolled templates and probes (GDPR special category)
- HMAC fingerprints
- results and scores
- the RS256 private key and the HMAC key
- bearer tokens
- decision integrity (exactly-once)
- availability under bursts

**Trust boundaries:**

```
[Tenant backend / attacker] --HTTP + Bearer--> (TB1) gateway:8080 --JDBC--> (TB2) postgres
                                                  gateway:8081 (mgmt, compose network only)
matcher x3 --JDBC--> postgres ; matcher:8081 (compose network only)
[Operator] --docker compose run devtoken--> issuer-keys volume (TB3: private key never mounted into gateway/matcher)
logs (stdout of gateway/matcher/postgres) (TB4: leaves the trust zone to operators/log stores)
```

| id | STRIDE | OWASP | threat | mitigation | plan step | test |
|---|---|---|---|---|---|---|
| T1 | S | A07, API2 | Token forgery/replay (MC-2): alg=none, HS256 with the public key as secret, a foreign RSA key, wrong iss/aud, expired, missing tenant_id | `NimbusJwtDecoder.withPublicKey(..).signatureAlgorithm(RS256)`; validators: timestamp (60 s skew), issuer, `aud` claim validator, required `sub`/`tenant_id`/`exp`/`iat`, TTL ≤ 900 s; the gateway holds only the public key; constant 401 body | 2 | AC-6 (parameterised negative set), AC-5 |
| T2 | I, E | A01, API1, API5 | Cross-tenant BOLA/enumeration (MC-1) and missing-scope calls | Tenant only from the JWT; `tenant_id` predicate in every SQL; 404 identical for foreign and unknown ids; random UUIDs; URL scope rules per path (all methods) in the filter chain, `denyAll` fallback (D-18) | 2, 4, 7, 8 | AC-7, AC-37, AC-38, AC-39, AC-40, AC-20 |
| T3 | I | A09, A10, API3 | Template or token leakage via logs or error bodies (MC-3): Jackson messages echoing tokens, PSQLException "Failing row contains", Spring's default `error_description`, stack traces | Constant problem bodies; never log exception messages from parse or DB paths; `logServerErrorDetail=false`; postgres `log_error_verbosity=terse`; no body or header logging; `server.error.include-*=never`; response DTOs have no template fields | 3, 7 (step 8 and 9 tests extend the scan) | AC-41 (log and body scan), AC-11, AC-43, AC-30 |
| T4 | T | A06, A08 | Conflicting or duplicate results from a zombie replica, a double claim, or an erasure race (MC: R-3, R-5) | `SKIP LOCKED` claim with lease token; guarded terminal UPDATE (token + PENDING); reaper with attempts cap; erasure terminal-fails in one transaction; immutable-terminal trigger | 5, 6, 8 | AC-34, AC-35, AC-36, AC-49, AC-27 |
| T5 | D | API4, A10 | Resource exhaustion and hostile payloads (MC-5, MC-6): huge or nested bodies, NaN/Inf, wrong length, chunked uploads, unique-key floods | 8 KB limit filter (Content-Length plus a bounded read); strict base64 length precheck; finite and norm validation; no NaN in JSON (Jackson default); 415 on non-JSON; Hikari pool caps. **Residual:** no rate limit and no idempotency TTL cleanup (Won't, D-2) – README | 3, 4 | AC-42, AC-43, AC-11, AC-16, AC-46 |
| T6 | T, E | A03 | Supply chain (added by critique): hallucinated or typosquatted dependency coordinates, known-vulnerable transitive deps, secrets committed to git | Exact coordinates verified before adding (CLAUDE.md §4); Gradle dependency locking in every module; osv-scanner + gitleaks in `verify.sh`; keys generated at runtime, `*.pem`/`*.key` gitignored | 1a, 2 | `./scripts/verify.sh` output (osv-scanner, gitleaks); `git ls-files` key check (step 2) |

Also covered: MC-4a idempotency across tenants (AC-20); the private key never in the gateway or in git (AC-5, gitleaks); actuator exposure (AC-52). Residual and documented: MC-7 score oracle, MC-8 in-tenant misuse, no TLS in compose.

## 6. Evals approach

| AC group | In-process (`./scripts/verify.sh`) | Black-box (`scripts/acceptance.sh` against compose) | Other |
|---|---|---|---|
| AC-1..4 | Skeleton ITs; ktlint; build | HEALTH, ACTUATOR (`/actuator/env` → 401); `docker compose ps` shows 5 healthy within 3 min (timed) | gitleaks in verify.sh; `git ls-files` key check |
| AC-5..8 | `DevTokenTest`, `JwtAuthIT` (tag `security` → `./gradlew :gateway:securityTest`) | token.sh-minted tokens; hostile: missing token, `alg=none` token built in bash (base64url header `{"alg":"none"}`), expired (`--ttl -120`), wrong-scope → 401/403 | – |
| AC-9..13, 42..44 | `TemplateCodecTest`, `EnrollmentIT` | Hostile set via python3 one-liners: 511-byte template, NaN template, norm 1.02, 65-char id, `a%2Fb`, 9 KB body → 413, `text/plain` → 415, `{"template":123}` → 400; every error body grepped for the submitted base64 | – |
| AC-14..17, 30..33 | `VerificationApiIT`, `MatcherIT` | POST → 202 + Location; poll GET → MATCH; `ghost` → FAILED/SUBJECT_NOT_ENROLLED; `not-a-uuid` → 404; key 65 → 400 | – |
| AC-18..23 | `IdempotencyIT` | replay → same id, 200; different body → 422; other tenant same key → new id | – |
| AC-24..29, 34..36 | `MatcherIT`, `ExactlyOnceIT` (500 jobs × 3 claimers, stale writer, trigger) | 3 real replicas decide the eval run: every one of 5000 pairs ends terminal exactly once (eval asserts no FAILED or timeout) | README limitation: no container-kill test (D-2) |
| AC-37..41 | `TenantIsolationIT` (both modules), `LeakageIT` (OutputCaptureExtension) | Cross-tenant GET/DELETE → 404 bodies byte-compared with an unknown id; then `docker compose logs gateway matcher \| grep -F` for every submitted base64, token and `Bearer ` → 0 hits | – |
| AC-45..50 | `ThresholdIT`, `ErasureIT` | threshold "abc", 1.01 → 400; DELETE → 204, second → 404 | – |
| AC-51..52 | `MetricsIT`, `QueueDepthSchedulingIT` (matcher, D-27), `HealthEndpointIT` | public `/actuator/prometheus` → 401; `scripts/acceptance.sh` scrapes all matcher replicas on 8081 → names present, no tenant/subject labels | – |
| AC-53..56 | `eval/test_eval.py` | `python3 eval/eval.py`, run twice, outputs diffed | Ship-check if not run in step 10 |
| AC-57..58 | – | `loadtest/run.sh` 60 s in Ship-check; README records machine, numbers, met / not met; server p95 from `matchgw_verification_latency` buckets | – |
| ZAP | – | Ship-check only, if `scripts/owasp-scan.sh` is added. Of limited value without an OpenAPI doc (AM-16/17). Report honestly in SECURITY.md, or "not run". | – |

Each step adds its AC blocks and at least 3 hostile inputs per endpoint to `scripts/acceptance.sh`. Its current `check`/`assert_body` helpers are sufficient. acceptance.sh uses curl, so the human runs it if Claude's permission blocks it.

**Things to verify before naming them in code:**
- Testcontainers 2 artifact and package names (use what start.spring.io generates)
- RestTestClient setup in Boot 4.1 (`@AutoConfigureRestTestClient` vs `RestTestClient.bindToServer()`)
- whether the health group `additional-path` accepts `server:/actuator/health`
- the Boot 4 module or starter that provides the Prometheus endpoint
- whether `eclipse-temurin:25-jre` contains curl
- the ktlint Gradle plugin version
- which module contains `RsaKeyConverters` (believed spring-security-core)
- Jackson 3 scalar-coercion configuration
- Gradle 9 running on JDK 25 inside the build image
