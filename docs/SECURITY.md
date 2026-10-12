# Security

## Authentication design

- JWT only, RS256 (ADR-004). Tokens are minted by the dev tool `devtoken` (`./scripts/token.sh <tenant_id> <sub> "<scopes>"`),
  never by the gateway: the gateway mounts only the public key (and the idempotency HMAC key); the private key lives in the
  `issuer-keys` volume, mounted only into the token tool. Keys are generated on first start by the one-shot `keygen` service.
- Resource server: `oauth2ResourceServer { jwt }` with `NimbusJwtDecoder.withPublicKey(..).signatureAlgorithm(RS256)` – rejects
  `alg=none` and HS256 key confusion. Validators: `exp`/`nbf` with 60 s skew, `iss`, `aud`, required `sub`, `iat`, `exp`,
  `tenant_id` matching `^[A-Za-z0-9._-]{1,64}$`, lifetime `exp − iat` ≤ 15 min. Stateless; no sessions, Basic or form login.
- Authorities from the `scope` claim → `SCOPE_*` (D-5). Scope checks are URL rules in the filter chain, so a caller without the
  scope is rejected before the body is read (D-18). Tenant ownership is enforced in SQL by each feature (`tenant_id` from the JWT only).
- 401: `WWW-Authenticate: Bearer` and a constant problem body (never why the token failed); 403: constant problem body.
- CSRF disabled – stateless bearer-only API without cookies (ADR-005). CORS: empty allowlist.
- Headers: `X-Content-Type-Options: nosniff`, `Content-Security-Policy: frame-ancestors 'none'`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store`. HSTS only over HTTPS – compose has no TLS (README Known limitations).

## Threat model summary
See docs/ARCHITECTURE.md § Threat model.

## OWASP matrix

Refreshed by /ship-check on 2026-10-12 (commit faf98ce, RabbitMQ design D-28/ADR-009). Evidence = automated tests of the clean
run, `scripts/acceptance.sh` on a fresh clone (70/70) and the per-step security reviews (live probes, docs/AI_LOG.md). Residual
risks are listed in README "Known limitations".

| Id | Category | Status | Evidence (test / ZAP rule / review) | Notes |
|---|---|---|---|---|
| A01 | Broken Access Control | covered | `TenantIsolationIT`, `TenantIsolationMatcherIT`, `ErasureIT` (API1 AC-37), `IdempotencyIT` (AC-20), `ThresholdIT`, `ScopeAuthorizationIT`, `DecisionProcessorIT` (tenant from the DB row, never from a message); acceptance AC-33/AC-20/AC-7 | A forged job id cannot cross tenants: the matcher takes tenant/subject from the PENDING row |
| A02 | Security Misconfiguration | covered | `SecurityHeadersIT`, `LeakageIT` (AC-44), `HealthEndpointIT`, `TransportHardeningIT`, `BrokerTopologyIT`/`BrokerDownIT`; reviews step 11: broker unpublished, generated credentials, no guest/admin, no plugins, Erlang distribution on loopback, hardened container | Gaps: firewall 400s lack nosniff/no-store; no TLS/HSTS in compose; AMQP plaintext inside the network |
| A03 | Software Supply Chain Failures | covered | `verify.sh`: osv-scanner over all lockfiles ("No issues found"), dependency locking, amqp-client raised to 5.37.0 (Boot BOM 5.30.0 had 10 advisories), images pinned by digest (postgres, rabbitmq 4.3, k6) | Build-plugin classpath not locked; base-image OS CVEs |
| A04 | Cryptographic Failures | covered | `JwtAuthenticationIT` (RS256 pinned, alg=none and HS256 confusion rejected), `FingerprinterTest` + `IdempotencyIT` (HMAC-SHA256, constant-time compare), `DevTokenTest` | Keys and broker/DB passwords generated at first start, never in git (gitleaks clean incl. history); no TLS on AMQP/JDBC inside compose; encryption at rest = Won't |
| A05 | Injection | covered | Parameterised `JdbcClient` only; hostile-input ITs; StrictJsonConfig; acceptance HOSTILE-* | No dynamic SQL; broker messages are parsed as a canonical UUID only |
| A06 | Insecure Design | covered | `OutboxIT`/`OutboxRelayIT` (no lost job: broker down, hung, unroutable, rollback), `ExactlyOnceIT` (500 messages × 3 consumers, duplicates, stale writer), `MatcherIT`/`MatcherStallIT` (crash, stall, stuck consumer), `ErasureIT`/`ErasureOrderingIT` | Residual: no reconciler for messages lost after a confirmed publish; AM-9 submit/erase race; burst tail latency via the relay |
| A07 | Authentication Failures | covered | `JwtAuthenticationIT` (12 cases), acceptance AC-6 | No token endpoint; dev tokens only (ADR-004) |
| A08 | Software or Data Integrity Failures | covered | Guarded terminal write + V2 trigger (`ExactlyOnceIT`, `JobQueueIT`), duplicate JSON keys rejected, publisher confirms before the outbox row is deleted (`OutboxIT`) | Shared broker identity can forge dead letters (forced MAX_ATTEMPTS) or purge the queue; superuser DB role can disable the trigger |
| A09 | Security Logging & Alerting Failures | covered | `LeakageIT` (with canary), `RequestRedactionTest`, `TenantIsolationMatcherIT` (matcher logs incl. failure paths), `MetricsIT` (no tenant/subject labels); consumers log only ids + exception classes | Scan at shipped log levels (D-25); poison messages log one WARN each; no alerting rules |
| A10 | Mishandling of Exceptional Conditions | covered | `TransportHardeningIT`, `VerificationIT` (non-ASCII key → 400), `ErasureIT` (fault injection), `OutboxIT` (broker hung: bounded), `MatcherStallIT` (deadline), `DeadLetterListenerIT` (failing writes retried, never dropped) | Reconnect churn can fail an in-flight job with MAX_ATTEMPTS |
| API1 | Broken Object Level Authorization | covered | `TenantIsolationIT` (AC-37/39), `ErasureIT` BOLA, `ThresholdIT` BOLA, `IdempotencyIT` AC-20 | — |
| API2 | Broken Authentication | covered | `JwtAuthenticationIT`, invalid-token sets in `ErasureIT`/`ThresholdIT`, `LeakageIT` (12 token kinds) | — |
| API3 | Broken Object Property Level Authorization | covered | DTOs at the boundary; `VerificationIT` (exact keys), `IdempotencyIT` (replay body), mass-assignment tests; job message = id only (`OutboxIT` A09) | — |
| API4 | Unrestricted Resource Consumption | gap (partial) | 8 KB body limit, length limits; bounded publish executor + broker timeouts (`OutboxIT`); prefetch 1 + 36-byte body check + broker `max_message_size` 4096 | No rate limiting; no queue max length; expired idempotency rows never cleaned |
| API5 | Broken Function Level Authorization | covered | `ScopeAuthorizationIT` (per-path rules for all methods, denyAll), scope tests in `ThresholdIT`/`ErasureIT` | 403 before the body is read (D-18) |
| API6 | Unrestricted Access to Sensitive Business Flows | gap (accepted) | Admin scope for threshold and erasure | No anti-automation; score oracle (MC-7) |
| API7 | Server Side Request Forgery | N/A | No outbound HTTP from gateway or matcher; broker host is fixed config | eval refuses redirects (`test_eval.py::NoRedirectTest`) |
| API8 | Security Misconfiguration | covered | Same as A02; matcher port 8081 and broker unpublished; CORS allowlist empty; CSRF disabled per ADR-005 | Matcher prometheus unauthenticated inside the compose network (D-27) |
| API9 | Improper Inventory Management | covered | ARCHITECTURE API table + protocol + data model match the code (reviews steps 13–14); deny-by-default | — |
| API10 | Unsafe Consumption of APIs | covered | Broker messages are untrusted input: canonical-UUID only, tenant from the DB row, guarded write (`MatcherIT` poison bodies, `DecisionProcessorIT`) | Dead-letter consumer trusts the queue (no `x-death` check) |

## ZAP results

`./scripts/owasp-scan.sh` – OWASP ZAP API scan (passive + active), image `ghcr.io/zaproxy/zaproxy:stable` pinned by digest,
against the running compose stack (`http://gateway:8080` on the compose network), driven by `scripts/zap/openapi.yaml`
(5 endpoints + health, 12 imported URLs), authenticated with a dev token of a dedicated tenant `zap-scan`
(`ZAP_AUTH_HEADER_VALUE`, scopes enroll verify admin).

Run 2026-10-12 (commit 9246788 + script): **FAIL 0, WARN 1, PASS 118** (exit 0).

| Alert | Risk | Where | Assessment |
|---|---|---|---|
| 90004 Cross-Origin-Resource-Policy header missing or invalid | Low | `/actuator/health`, `/v1/tenant/threshold` | Valid: responses carry no `Cross-Origin-Resource-Policy`. Low impact for a JSON API without browser clients; fix proposal: `Cross-Origin-Resource-Policy: same-origin` via Spring Security's header writer |
| 100000 Client error response code | Info | probes of cloud-metadata paths (`/latest/meta-data/`, `/computeMetadata/v1/`) | Expected: denied / not found |
| 10049 Non-storable content | Info | `/v1/**`, health | Expected: `Cache-Control: no-store` is intentional |

Coverage limits: authentication worked (the scan's threshold PUT was stored), but enrolment and verification requests never
passed input validation – no subject or verification was created for `zap-scan` – so for those endpoints ZAP exercised the
validation/error paths, not the happy path (likely cause, not confirmed: ZAP generates its own body values instead of the
684-character template example). Full reports: `zap-report/report.{html,json,md}` (git-ignored).
