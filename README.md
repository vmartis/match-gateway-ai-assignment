# Match Gateway

Multi-tenant asynchronous 1:1 face-template verification: a JWT-protected `gateway` REST API and horizontally scaled `matcher`
workers over PostgreSQL. Specification: [docs/SPEC.md](docs/SPEC.md); design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/adr/](docs/adr/); plan and progress: [docs/PLAN.md](docs/PLAN.md); AI usage: [docs/AI_LOG.md](docs/AI_LOG.md).

> Work in progress – run instructions, API usage, evaluation and load-test results are added as the plan steps land.

## Known limitations

Accepted review findings and deliberate scope cuts, each with the risk and what we would do next.

| Finding | Risk | Next step for production |
|---|---|---|
| Public `GET /actuator/health` runs the full health check (incl. a DB connection) for anonymous callers (security review, step 1a) | Anonymous health floods compete with API traffic for the Hikari pool; no rate limiting (D-2) | Cache the status for ~1 s or expose only a `ping` group publicly; per-client rate limiting at the edge |
| Build plugin classpath (Kotlin, Boot, dependency-management, ktlint plugins) is not dependency-locked or osv-scanned; ktlint tool configurations are excluded on purpose (D-20) | A compromised build plugin runs at build time (not shipped in images) | Lock the buildscript/settings classpath and scan it as well |
| Gateway and matcher share one DB user that also owns the schema (Flyway DDL rights) | A compromised matcher or a future SQL injection would have DDL rights | Separate roles: migration owner for Flyway only, DML-only runtime roles per app |
| Schema CHECKs on `verification.subject_id`, `idempotency_key.idem_key` and `tenant_id` formats are left to the API layer | Defence in depth only – the API validates these inputs | Add matching CHECK constraints |
