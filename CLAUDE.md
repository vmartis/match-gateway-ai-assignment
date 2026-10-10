# CLAUDE.md – rulebook for every session and subagent

0. When a prompt from the human contains {{NAME}}, take the value from docs/CONTEXT.md; if it is missing, ask.

## 1. Context
- Take-home for a biometrics/identity company: regulated, adversarial, PII minimization. Correctness and security beat feature count.
- Time budget: 180 min total.
- Source of truth order: docs/SPEC.md > docs/PLAN.md > docs/ARCHITECTURE.md + docs/adr/ > CLAUDE.md. On conflict: stop and ask.
- Never invent requirements. Every requirement traces to a quote in docs/ASSIGNMENT.md or a human decision in
  docs/analysis/DECISIONS.md. Label everything else ASSUMPTION.
- docs/analysis/ is the upstream reasoning SPEC is derived from. Its contents depend on the route in TRIAGE.md:
  SKIP = TRIAGE.md + DECISIONS.md; LIGHT = + ANALYSIS.md (short) + BACKLOG.md; FULL = all files.
  Missing analysis files are expected on SKIP/LIGHT. Never create them unasked.

## 2. Tech stack (changing it requires an ADR)
- Backend: Kotlin 2.3.x (as managed by the Spring Boot 4.1 BOM, JVM target 25), Java 25 (Temurin), Spring Boot 4.1.x
  (Spring Framework 7, Jakarta EE 11), Gradle 9.x Kotlin DSL, toolchain 25.
- API: REST + JSON (Jackson 3, tools.jackson.*), Jakarta Validation (Hibernate Validator 9), RFC 9457 ProblemDetail,
  springdoc-openapi 3.x (Boot 4 line; verify the exact version).
- Auth: JWT only. Spring Security 7.1 OAuth2 Resource Server (spring-boot-starter-oauth2-resource-server), stateless Bearer.
- DB: PostgreSQL 17 + Flyway (spring-boot-starter-flyway + flyway-database-postgresql). Persistence: Spring Data JDBC.
- Tests: JUnit 6 (Jupiter), MockK + springmockk, AssertJ, RestTestClient, Testcontainers 2.x (@ServiceConnection), ArchUnit.
- Frontend: none. Lint: ktlint Gradle plugin. Runtime: Docker + docker compose (`docker compose up --build` starts everything).

## 3. Architecture rules
- Package by feature: `<feature>.{api,domain,infra}`. domain = pure Kotlin (no Spring, no persistence annotations).
- DTOs at the boundary; never expose entities. Input validation only in the api layer.
- Exactly one @RestControllerAdvice mapping errors to ProblemDetail. No stack traces or internal ids in responses.
- Config via env vars. Immutability by default (val, data classes, read-only collections).
- No `!!`. No speculative abstractions (no interface with one impl "for later", no unused extension points).

## 4. Security rules (non-negotiable)
- Treat all input as hostile: check type, length, range. Parameterized SQL only. Never hand-roll crypto or auth.
- Passwords only through PasswordEncoder. Owner check on every resource access (no IDOR).
- Never log PII, biometric data, tokens, or full Authorization headers.
- Uploads: size limit, magic-byte type check, ignore the client filename.
- Explicit CORS allowlist. Deny-by-default security config. Actuator exposes health only.
- Authentication = JWT only, app-issued: POST /api/auth/token, NimbusJwtEncoder, RS256 key pair from env/mounted file, TTL 15 min.
  - Never HTTP Basic, never form login, never sessions (SessionCreationPolicy.STATELESS).
  - Use oauth2ResourceServer + NimbusJwtDecoder/NimbusJwtEncoder. Never a hand-written JWT filter or parser.
  - Asymmetric signing only (RS256/ES256). Key from env or mounted file, never in the repo.
  - Pin the expected algorithm; reject alg=none.
  - Validate exp, nbf, iss, aud (JwtValidators + audience validator). TTL ≤ 15 min. Clock skew ≤ 60 s.
  - Authorities from a dedicated claim (roles/scope) via JwtAuthenticationConverter.
  - @PreAuthorize for role checks; explicit owner checks for object access.
  - 401 + WWW-Authenticate for missing/invalid token; 403 for insufficient rights.
  - Token endpoint: identical error body for "unknown user" and "wrong password".
  - CSRF disabled only because the API is stateless, bearer-only, with no cookies. Record this reason in an ADR.
- Security headers on every response: HSTS, X-Content-Type-Options, frame-ancestors 'none', Referrer-Policy,
  Cache-Control: no-store on auth/PII responses.
- If a web frontend is ever added: strict CSP (no unsafe-inline/eval), token in memory only (never localStorage),
  no dangerouslySetInnerHTML.
- Dependencies: well-known libs only. Before adding one, state the exact coordinates and verify they resolve. Never guess package names.
- Mark security-sensitive code "human-reviewed" in docs/AI_LOG.md.

## 5. Workflow rules for Claude
- Never write code without an approved step in docs/PLAN.md. One step at a time.
- Tests first.
- Run ./scripts/verify.sh before claiming done and show its real output.
- Never weaken, skip, or delete tests. Never add @Disabled.
- Never edit docs/SPEC.md. Propose the change instead.
- Unsure about a library API: check the sources/docs. If still unsure, say "not sure" instead of guessing.
- Append to docs/AI_LOG.md after each step.

## 5b. Git & commits
- Conventional Commits 1.0: `<type>(<scope>): <summary>`.
  - type: feat, fix, test, refactor, docs, chore, build, ci, perf, security.
  - scope: feature/area, e.g. verification, auth, matcher, analysis, setup.
  - summary: imperative, lower case, no period, ≤ 72 chars.
  - body: explain WHY when not obvious. Footer: `Refs: step-N` / `Refs: AC-3, US-2` / `BREAKING CHANGE:` when relevant.
- Commit after every completed logical unit: one finished plan step, one review-fix round, one docs milestone
  (analysis, spec, plan), one setup block. A logical unit compiles, has ./scripts/verify.sh green (docs-only commits excepted),
  and can be explained in one sentence.
- Tests go in the same commit as the code they cover. Never mix unrelated changes. Never commit WIP or a red build.
- Never amend or rewrite pushed history.
- Stage files explicitly (no `git add -A` of unknown files). Check `git status` and `git diff --staged` before committing.
- After committing, show the commit hash and message. Never push unless the human asks.

## 6. Commands
- Build: `./gradlew build`
- Test: `./gradlew test`
- Lint: `./gradlew ktlintCheck` (fix: `./gradlew ktlintFormat`)
- Frontend checks: none (no frontend).
- Full gate: `./scripts/verify.sh`
- Run everything: `docker compose up --build`

## 7. Definition of done (per step)
- [ ] Every AC of the step is covered by tests, including ≥ 2 edge/error cases
- [ ] ./scripts/verify.sh green (output shown)
- [ ] No new warnings (compiler, lint, tests)
- [ ] Reviewed by code-reviewer (+ security-reviewer if security-relevant)
- [ ] docs/AI_LOG.md updated

## 8. Extra constraints
- None yet. Add only through docs/analysis/DECISIONS.md + an ADR.

## 9. Version rules (Java 25 / Kotlin 2.3 / Spring Boot 4.1)
Most training data is Spring Boot 3. Assume your memory is outdated.
- Boot 4 starters are modular: every auto-configured feature needs its own starter (e.g. spring-boot-starter-flyway,
  spring-boot-starter-webmvc-test). If a feature "silently does nothing", check for a missing starter first.
- Jackson 3: databind classes live in tools.jackson.* (JsonMapper). Never import com.fasterxml.jackson.databind.
- Tests: RestTestClient, @MockitoBean / @MockkBean (never @MockBean), Testcontainers 2.x with @ServiceConnection.
- Spring Security 7: lambda / Kotlin DSL only. No .and(), no WebSecurityConfigurerAdapter.
- Spring APIs are JSpecify-annotated: trust Kotlin nullability. No defensive !! or ?. on Spring return types.
- Kotlin 2.3.x exactly as managed by the Boot 4.1 BOM. Kotlin Gradle plugin version = BOM kotlin version. Never override kotlin.version.
- Prefer built-ins over extra libs: @Retryable / @ConcurrencyLimit, InetAddressFilter for outbound HTTP (SSRF), virtual threads.
- For any API you are not certain exists in these versions, check the sources JAR / official docs for that exact version.
