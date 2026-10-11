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
| Id | Category | Status (covered / N.A. / gap) | Test or reason |
|---|---|---|---|
| A01 | Broken Access Control | | |
| A02 | Security Misconfiguration | | |
| A03 | Software Supply Chain Failures | | |
| A04 | Cryptographic Failures | | |
| A05 | Injection | | |
| A06 | Insecure Design | | |
| A07 | Authentication Failures | | |
| A08 | Software or Data Integrity Failures | | |
| A09 | Security Logging & Alerting Failures | | |
| A10 | Mishandling of Exceptional Conditions | | |
| API1 | Broken Object Level Authorization | | |
| API2 | Broken Authentication | | |
| API3 | Broken Object Property Level Authorization | | |
| API4 | Unrestricted Resource Consumption | | |
| API5 | Broken Function Level Authorization | | |
| API6 | Unrestricted Access to Sensitive Business Flows | | |
| API7 | Server Side Request Forgery | | |
| API8 | Security Misconfiguration | | |
| API9 | Improper Inventory Management | | |
| API10 | Unsafe Consumption of APIs | | |

## ZAP results
_Not run yet._ (`./scripts/owasp-scan.sh` – paste real summary: PASS/WARN/FAIL counts, date.)
