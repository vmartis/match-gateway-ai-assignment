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

Refreshed by /ship-check on 2026-10-11 (commit c76b0d3). Evidence = automated tests from the clean run (517 tests, 0 failed),
`scripts/acceptance.sh` on a fresh clone (66/66) and the per-step security reviews (live probes, recorded in docs/AI_LOG.md).
Residual risks are listed in README "Known limitations".

| Id | Category | Status | Evidence (test / ZAP rule / review) | Notes |
|---|---|---|---|---|
| A01 | Broken Access Control | covered | `TenantIsolationIT`, `TenantIsolationMatcherIT`, `ErasureIT` (API1 AC-37), `IdempotencyIT` (AC-20), `ThresholdIT` (tenant from JWT only), `ScopeAuthorizationIT`; acceptance AC-33/AC-20/AC-7 | Tenant in every SQL statement from the JWT; other tenants' resources → identical 404 |
| A02 | Security Misconfiguration | covered | `SecurityHeadersIT`, `LeakageIT` (AC-44 headers on every /v1 status), `HealthEndpointIT` (only health exposed), `TransportHardeningIT`; review step 10: 30+ actuator path variants → 401/403/400 | Gap: firewall-rejected 400s lack nosniff/no-store; Tomcat-level 400s are HTML; no TLS/HSTS in compose (README) |
| A03 | Software Supply Chain Failures | covered | `verify.sh`: osv-scanner over all Gradle lockfiles ("No issues found"), dependency locking, base images and k6 pinned by digest | Build-plugin classpath not locked; base-image OS CVEs (README) |
| A04 | Cryptographic Failures | covered | `JwtAuthenticationIT` (RS256 pinned, alg=none and HS256 confusion rejected), `FingerprinterTest` + `IdempotencyIT` (HMAC-SHA256, 32-byte key, constant-time compare), `DevTokenTest` | Keys generated at first start, never in git (gitleaks clean incl. history); encryption at rest = Won't (D-2) |
| A05 | Injection | covered | Parameterised `JdbcClient` only; `EnrollmentIT`/`VerificationIT`/`ThresholdIT` hostile inputs; StrictJsonConfig (no coercion, duplicate keys rejected); review step 9: SQL payload in Idempotency-Key stored verbatim | No dynamic SQL; subject id regex at the boundary |
| A06 | Insecure Design | covered | `ExactlyOnceIT` (AC-34/35/36, V2 immutability trigger), `ErasureIT` (atomicity, AC-49 ordering), `IdempotencyIT` (DB-uniqueness concurrency) | Residual: AM-9 submit/erase race, possible reaper/erasure deadlock (README) |
| A07 | Authentication Failures | covered | `JwtAuthenticationIT` (12 cases incl. expired, nbf, wrong iss/aud, missing tenant_id/iat, lifetime > 15 min), acceptance AC-6 | No token endpoint; dev tokens only (ADR-004) |
| A08 | Software or Data Integrity Failures | covered | V2 trigger (`ExactlyOnceIT` INV-1), guarded terminal writes, duplicate JSON keys rejected (`VerificationIT`, acceptance HOSTILE-verify-dupkey) | Superuser DB role can disable the trigger (README) |
| A09 | Security Logging & Alerting Failures | covered | `LeakageIT` (logs + bodies of every endpoint scanned for templates/tokens, with a capture canary), `RequestRedactionTest`, `TenantIsolationMatcherIT` (matcher log scan incl. decoded shapes), `MetricsIT` (no tenant/subject labels) | Scan at shipped log levels (D-25); no alerting rules shipped |
| A10 | Mishandling of Exceptional Conditions | covered | Single advice → constant problem bodies; `TransportHardeningIT`, `VerificationIT` (non-ASCII key → 400, no ERROR log), `ErasureIT` (fault injection → 500, rolled back), `MatcherIT` (poison template → MAX_ATTEMPTS) | — |
| API1 | Broken Object Level Authorization | covered | `TenantIsolationIT` (AC-37/39 byte-identical 404), `ErasureIT` BOLA, `ThresholdIT` BOLA, `IdempotencyIT` AC-20 | — |
| API2 | Broken Authentication | covered | `JwtAuthenticationIT`, `ErasureIT`/`ThresholdIT` invalid-token sets, `LeakageIT` (12 token kinds on every endpoint) | — |
| API3 | Broken Object Property Level Authorization | covered | DTOs at the boundary; `VerificationIT` (exact response keys, `reason` only when FAILED), `IdempotencyIT` (replay body exactly `{verificationId,status}`), mass-assignment tests (tenant fields in bodies ignored) | — |
| API4 | Unrestricted Resource Consumption | gap (partial) | 8 KB body limit (`TransportHardeningIT`, acceptance 413s), key/id length limits, strict template length; Hikari pool caps | No rate limiting, no cleanup of expired idempotency rows (D-2, README) |
| API5 | Broken Function Level Authorization | covered | `ScopeAuthorizationIT` (per-path rules for all methods incl. HEAD, denyAll fallback), `ThresholdIT`/`ErasureIT` scope tests, acceptance AC-7/API5-HEAD | 403 before the body is read (D-18) |
| API6 | Unrestricted Access to Sensitive Business Flows | gap (accepted) | Admin scope required for threshold and erasure | No rate limit or anti-automation; score oracle (MC-7) documented in ARCHITECTURE |
| API7 | Server Side Request Forgery | N/A | No outbound HTTP from gateway or matcher | eval refuses redirects (`test_eval.py::NoRedirectTest`) |
| API8 | Security Misconfiguration | covered | Same as A02; matcher port 8081 unpublished (review step 10), CORS allowlist empty, CSRF disabled per ADR-005 | Matcher prometheus unauthenticated inside the compose network (D-27) |
| API9 | Improper Inventory Management | covered | ARCHITECTURE API table matches the endpoints; deny-by-default (`ScopeAuthorizationIT` unmapped → 403); `HealthEndpointIT` | SPEC DELETE error list still lacks 400 (D-26, proposed change) |
| API10 | Unsafe Consumption of APIs | N/A | No third-party API consumed | — |

## ZAP results
**Not run** (ship-check 2026-10-11): `./scripts/owasp-scan.sh` does not exist in the repository, so no ZAP baseline/API scan
was executed. No ZAP results are claimed in the matrix above.
