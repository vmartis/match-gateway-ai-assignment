# ADR-004: RS256 tokens minted by a dev script; gateway is resource server only

- **Status:** Proposed
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context "All endpoints except health require a **JWT bearer token** (RS256). Required claims: `sub`, `tenant_id`, `scope` … provide a way to obtain tokens (your own token endpoint or a dev script)." D-4 chose a dev script with the gateway as a pure resource server and keys generated at startup, never in git. D-5 maps `scope` to `SCOPE_*`.

## Decision
- A one-shot `keygen` compose service (devtoken image) creates an RSA key pair, unless one already exists, and a 32-byte HMAC key into named volumes. The private key goes only to `issuer-keys`; the gateway mounts `gateway-keys` (public key and HMAC key) read-only.
- `scripts/token.sh <tenant> <sub> "<scopes>"` runs `docker compose run --rm devtoken mint …`. It uses `NimbusJwtEncoder` with `JwsHeader` RS256 and claims `iss=https://match-gateway.invalid/dev-issuer (Spring's encoder requires a URL-valued iss; .invalid is reserved, RFC 2606)`, `aud=match-gateway`, `sub`, `tenant_id`, `scope`, `iat`, `nbf`, `exp = iat + 900 s`, `jti`.
- The gateway builds `NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256)` with a `DelegatingOAuth2TokenValidator`: `JwtTimestampValidator(60 s)`, `JwtIssuerValidator`, `JwtAudienceValidator`, `RequiredClaimValidator` for `sub`, `exp` and `tenant_id` (`^[A-Za-z0-9._-]{1,64}$`); `iat` presence is enforced in a claim-set converter wrapper *before* `MappedJwtClaimSetConverter.withDefaults`, because the default converter fills a missing `iat` from `exp` (a validator would never see it missing – found by mutation testing), `MaxLifetimeValidator` (exp − iat ≤ 900 s) and `NotIssuedInFutureValidator` (iat ≤ now + 60 s).
- An explicit `JwtAuthenticationConverter` maps only the `scope` claim to `SCOPE_*` (D-5; `scp` ignored). Scope checks are URL rules per path for all methods in the SecurityFilterChain, with `denyAll` as the fallback (D-18, review step 2: a GET-only rule let HEAD through).
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
