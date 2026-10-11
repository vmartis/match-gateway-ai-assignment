# ADR-005: CSRF disabled for the stateless bearer-only API

- **Status:** Proposed
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context CLAUDE.md §4: "CSRF disabled only because the API is stateless, bearer-only, with no cookies. Record this reason in an ADR." Callers are tenant backends. There is no browser UI.

## Decision
- `csrf { disable() }`, `SessionCreationPolicy.STATELESS`, httpBasic and formLogin disabled.
- Authentication only via the `Authorization: Bearer` header, which browsers never attach automatically.
- CORS is configured with an empty allowlist.

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| Keep CSRF on | Defence if cookies ever appear | Breaks every non-browser client; no threat without ambient credentials |
| Cookie-based session + CSRF token | Browser-friendly | Not required; adds a session store, contradicts the JWT-only rule |

## Consequences
- (+) No CSRF surface while no ambient credential exists.
- Must be revisited if a cookie or browser frontend is ever added. That would also bring strict CSP and in-memory tokens (CLAUDE.md §4).
