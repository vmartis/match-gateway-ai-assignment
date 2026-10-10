# CONTEXT
- {{BACKEND}}        = Kotlin 2.3.x – the version managed by the Spring Boot 4.1 BOM (JVM target 25), Java 25 (Temurin), Spring Boot 4.1.x (Spring Framework 7, Jakarta EE 11),
  Gradle 9.x Kotlin DSL with toolchain 25
- {{API}}            = REST + JSON (Jackson 3, packages tools.jackson.*), Jakarta Validation (Hibernate Validator 9),
  RFC 9457 ProblemDetail, springdoc-openapi 3.x (Boot 4 line – verify exact version)
- {{AUTH}}           = JWT only (never HTTP Basic, never session cookies): Spring Security 7.1 OAuth2 Resource Server
  (spring-boot-starter-oauth2-resource-server), stateless, Bearer token in Authorization header
- {{JWT_ISSUER}}     = app-issued: POST /api/auth/token, NimbusJwtEncoder, RS256 key pair from env/mounted file, TTL 15 min
  (alt: Keycloak in docker compose as issuer – realistic, but +15 min setup)
- {{DB}}             = PostgreSQL 17 + Flyway (spring-boot-starter-flyway + flyway-database-postgresql)
- {{PERSISTENCE}}    = Spring Data JDBC   (alt: JPA / Hibernate 7)
- {{TESTS}}          = JUnit 6 (Jupiter), MockK + springmockk, AssertJ, RestTestClient, Testcontainers 2.x
  (@ServiceConnection), (ArchUnit)
- {{FRONTEND}}       = none | React 18 + TypeScript strict + Vite + TanStack Query + Zod + Vitest
- {{RUNTIME}}        = Docker + docker compose (`docker compose up --build` starts everything)
- {{ARCH_STYLE}}     = package by feature: <feature>.{api,domain,infra}; domain = pure Kotlin
- {{LINT}}           = ktlint (Gradle plugin) | spotless ; eslint + prettier for web
- {{TIME_BUDGET}}    = 180 min total
- {{IMPLEMENTATION_MINUTES}} = ≈ 90 (SKIP) / ≈ 80 (LIGHT) / ≈ 55 (FULL) – upřesní triage
- {{DOMAIN_NOTES}}   = biometrics / identity verification; regulated, adversarial; PII minimization
- {{EXTRA}}          = (cokoliv dalšího: Python servis pro ML část, message queue, …)