# Security

## Authentication design
- JWT only, app-issued: `POST /api/auth/token`, NimbusJwtEncoder, RS256 key pair from env/mounted file, TTL ≤ 15 min.
- Resource server: `oauth2ResourceServer { jwt }`, algorithm pinned, iss/aud/exp/nbf validated, clock skew ≤ 60 s, stateless.
- Authorities from a dedicated claim via JwtAuthenticationConverter; @PreAuthorize for roles; explicit owner checks.
- CSRF disabled – stateless bearer-only API without cookies (ADR).

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
