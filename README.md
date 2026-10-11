# Match Gateway

Multi-tenant asynchronous 1:1 face-template verification: a JWT-protected `gateway` REST API and horizontally scaled `matcher`
workers over PostgreSQL. Specification: [docs/SPEC.md](docs/SPEC.md); design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/adr/](docs/adr/); plan and progress: [docs/PLAN.md](docs/PLAN.md); AI usage: [docs/AI_LOG.md](docs/AI_LOG.md).

> Work in progress – API usage, evaluation and load-test results are added as the plan steps land.

## Run

```
docker compose up --build        # postgres, gateway (http://127.0.0.1:8080) and 3 matcher replicas
curl http://127.0.0.1:8080/actuator/health
TOKEN=$(./scripts/token.sh bank-alpha client-1 "enroll verify admin")   # dev RS256 token, 15 min
docker compose down -v           # also deletes the generated DB password and all data
```

Prerequisite: Docker with Compose v2 – no local JDK needed. The DB password is generated on first start into the `dbcreds`
volume and never leaves the compose network; `down -v` resets it together with the data (removing only one of the two
volumes leaves postgres with a password it no longer accepts).

## Known limitations

Accepted review findings and deliberate scope cuts, each with the risk and what we would do next.

| Finding | Risk | Next step for production |
|---|---|---|
| Public `GET /actuator/health` runs the full health check (incl. a DB connection) for anonymous callers (security review, step 1a) | Anonymous health floods compete with API traffic for the Hikari pool; no rate limiting (D-2) | Cache the status for ~1 s or expose only a `ping` group publicly; per-client rate limiting at the edge |
| Build plugin classpath (Kotlin, Boot, dependency-management, ktlint plugins) is not dependency-locked or osv-scanned; ktlint tool configurations are excluded on purpose (D-20) | A compromised build plugin runs at build time (not shipped in images) | Lock the buildscript/settings classpath and scan it as well |
| Gateway and matcher connect as the bootstrap role `matchgw`, which is a **Postgres superuser** (security review 1b proved `COPY … FROM PROGRAM 'id'` runs as uid 999 in the DB container) | A future SQL injection or a compromised app container would get full data access and command execution in the DB container | Split roles: superuser only for bootstrap, a schema-owner role for Flyway, NOSUPERUSER DML-only login roles per app (second generated password + `docker-entrypoint-initdb.d` script) |
| The INV-1 trigger (V2) blocks only UPDATEs of decided rows: the owner/superuser role can disable it, and DELETE-then-INSERT or TRUNCATE can still replace or remove a decided row (security review, step 6) | Integrity of decisions against someone holding the app's DB credentials; not reachable via the API (gateway inserts only PENDING rows with server UUIDs, matcher writes are status/lease guarded) | Split roles as above; app roles get no TRUNCATE and no ownership; BEFORE INSERT trigger forcing `status = 'PENDING'`; DELETE only through an erasure role |
| Spring's own DEBUG loggers (`org.springframework.web`, `org.springframework.security`) log request URLs and Jackson parse messages, which echo client input; the leakage scan (AC-41) runs at the shipped log levels plus DEBUG for our code (D-25) | Enabling those loggers in production would write any hostile input – including template data a client misplaced into a URL or a wrong field – to the logs | Never enable them in production (log-level changes reviewed like code); ship logs to a store with access control and retention; optionally a log-pipeline redaction stage |
| Schema CHECKs on `verification.subject_id`, `idempotency_key.idem_key` and `tenant_id` formats are left to the API layer | Defence in depth only – the API validates these inputs | Add matching CHECK constraints |
| Base image `eclipse-temurin:25-jre` (Ubuntu 26.04) carries OS-package CVEs: 86 advisories in 19 packages, 0 critical, 15 fixable (Go stdlib in `pebble`, freetype, libpng) – none in our layers (security review 1b, `osv-scanner scan image`) | Exploitable only via those OS components, which the JVM app does not invoke | Rebuild on new base releases (digest bump); evaluate a chiseled/distroless JRE (needs an ADR – healthchecks use bash) |
| The API is plain HTTP (no TLS in compose); bound to 127.0.0.1 only | Bearer tokens in cleartext if the port were exposed beyond localhost | Terminate TLS at an ingress/reverse proxy; enable HSTS there |
| Requests Tomcat rejects itself (e.g. `%00`, invalid method, oversized header) get Boot's hardened minimal HTML 400 – no trace, no input, no server info – but not the problem+json body and not the Spring Security headers. Requests Spring Security's firewall rejects (`//`, `;`, `%2F`, `..`) get the constant problem+json 400 but also without `nosniff`/`no-store` (security review step 7: deviation from AC-44 for these rejections only) | Inconsistent error format for malformed HTTP only; bodies are static and carry no input | Find why the error dispatch after a firewall rejection skips the header writer and fix it there; terminate at a reverse proxy/ingress that normalises such errors and adds the headers |
| Thresholds are parsed as IEEE-754 doubles: `-0.0` is stored and echoed as `-0.0`, `1.0000000000000001` rounds to `1.0` (security review step 7) | Cosmetic; decisions are identical (`score >= -0.0` ⇔ `score >= 0`), DB CHECK holds | Normalise `-0.0`, or parse as BigDecimal and reject values that are not exactly representable |
