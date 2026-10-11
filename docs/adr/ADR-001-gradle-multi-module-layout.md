# ADR-001: Gradle multi-module layout

- **Status:** Proposed
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context The brief asks for "At least **two separately deployable applications**". D-7 chose a Gradle multi-module build with `gateway`, `matcher` and a small `common`, with Flyway owned by the gateway. D-4 needs a dev token minter that must never ship inside the gateway.

## Decision
- Modules: `common` (pure Kotlin, no Spring: template codec, cosine, status/reason enums), `gateway` (Boot app, owns `db/migration`), `matcher` (Boot app, no Flyway at runtime; its tests apply the gateway's migrations via a test resource dir) and `devtoken` (plain Kotlin CLI: keygen + mint).
- One multi-stage Dockerfile with one target per deployable.
- Kotlin and Boot versions come from the Boot 4.1 BOM. Dependency locking is on in every module.

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| Two separate Gradle builds | Full independence | Duplicated build logic; harder shared codec |
| One jar, two Spring profiles | Least build work | Not "separately deployable"; matcher ships the public API code |
| Minter inside the gateway behind a flag (B1) | No extra module | The gateway holds the private key and can mint any tenant's token |

## Consequences
- (+) `common` purity is enforced by Gradle (no Spring on its classpath).
- (+) Images are minimal per role.
- (−) The 4th module `devtoken` goes beyond D-7's list and needs human confirmation (AM-1).
- (−) Matcher tests depend on the gateway's migration directory path.
