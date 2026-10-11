# ADR-004: RS256 tokens minted by a dev script; gateway is resource server only

- **Status:** Proposed
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context "All endpoints except health require a **JWT bearer token** (RS256). Required claims: `sub`, `tenant_id`, `scope` … provide a way to obtain tokens (your own token endpoint or a dev script)." D-4 chose a dev script with the gateway as a pure resource server and keys generated at startup, never in git. D-5 maps `scope` to `SCOPE_*`.

## Decision
- A one-shot `keygen` compose service (devtoken image) creates an RSA key pair, unless one already exists, and a 32-byte HMAC key into named volumes. The private key goes only to `issuer-keys`; the gateway mounts `gateway-keys` (public key and HMAC key) read-only.
- `scripts/token.sh <tenant> <sub> "<scopes>"` runs `docker compose run --rm devtoken mint …`. It uses `NimbusJwtEncoder` with `JwsHeader` RS256 and claims `iss=match-gateway-dev`, `aud=match-gateway`, `sub`, `tenant_id`, `scope`, `iat`, `nbf`, `exp = iat + 900 s`, `jti`.
- The gateway builds `NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256)` with `JwtValidators.createDefaultWithValidators(JwtTimestampValidator(60 s), JwtIssuerValidator, JwtClaimValidator("aud" contains), JwtClaimValidator("tenant_id" pattern), required sub/exp/iat, TTL ≤ 900 s)`.
- It uses the default `JwtGrantedAuthoritiesConverter` (`scope` → `SCOPE_*`) and `@PreAuthorize`.
- A custom entry point returns 401, `WWW-Authenticate: Bearer` and a constant problem body (no `error_description`).

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| B1 dev endpoint in the gateway | Easiest for eval and k6 | The gateway holds the private key; a leaked flag breaks isolation |
| B3 separate issuer service / Keycloak | Realistic | A third deployable; +15 min |
| HS256 shared secret | Trivial | Brief mandates RS256; symmetric key everywhere |

## Consequences
- (+) The gateway cannot mint tokens.
- (+) No keys in git (gitleaks-checked).
- (−) Getting a token needs Docker (`token.sh`). The eval tool and k6 call it.
- (−) A replay within the TTL is accepted (residual risk).
- Production: a real IdP, JWKS rotation via `jwk-set-uri`.
