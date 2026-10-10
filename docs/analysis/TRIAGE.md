# Triage

> Written by /triage on 2026-10-11. Evidence is quoted verbatim from docs/ASSIGNMENT.md (`> "…"`, line numbers in brackets).
> Inferences are labelled INFERRED. The route is decided by the human (D-1 in DECISIONS.md).

## Type of brief

**Type: A – implementation-ready spec** (with small design/report deliverables, but not an analysis deliverable).

The brief specifies the API surface endpoint by endpoint with scopes, bodies and status codes
(> "`POST /v1/verifications` | `verify` | Header `Idempotency-Key` (required, max 64 chars)…" [L29]), exact validation rules
(> "the template must decode to exactly 512 bytes, all values finite, L2 norm in `[0.99, 1.01]`" [L35]), numeric NFRs
(> "Sustain **300 verifications/s for 60 s**" [L58]) and an explicit MoSCoW table [L80–86]. The design work it asks for is narrow:
one transport ADR (> "Justify it in an ADR, including how you guarantee the exactly-once outcome." [L52]), an eval report and README notes.
It is *not* type C – nobody asks for an analysis document – but it leans towards D because ADRs and evaluation of decisions are graded
(> "Your decisions and trade-offs (ADRs, README)." [L92]).

## Expected deliverables

| # | Deliverable | Explicit / INFERRED |
|---|---|---|
| 1 | Git repo runnable with `docker compose up --build` | > "a git repository we can run with `docker compose up --build`" [L4] |
| 2 | README | > "plus a README" [L4] |
| 3 | `gateway` app – public REST API (5 endpoints) | > "### 1. `gateway` – public REST API" [L19] |
| 4 | `matcher` app – verification worker, 3 replicas | > "Runs as **3 replicas** in docker compose." [L45] |
| 5 | PostgreSQL + a gateway→matcher transport | > "PostgreSQL for persistent state." [L51] |
| 6 | Token endpoint or dev script | > "provide a way to obtain tokens" [L22] > "(your own token endpoint or a dev script)" [L23] |
| 7 | ADR on transport + exactly-once guarantee | > "Justify it in an ADR, including how you guarantee the exactly-once outcome." [L52] |
| 8 | Health + metrics on both apps | > "`/actuator/health` on both apps, plus metrics: verifications by result, queue depth or lag, processing duration." [L61] |
| 9 | Load test under `loadtest/` + measured numbers in README (incl. machine) | > "Provide a **load test** (k6, Gatling or similar) under `loadtest/` and put the measured numbers in the README, including the machine you ran it on." [L64] |
| 10 | Eval tool: enrol, run pairs, FMR/FNMR, recommended threshold, observations | > "Provide an **eval tool** (any language) that enrols the subjects through the API, runs all pairs through the running system and reports" [L74] |
| 11 | Automated tests | > "automated tests" [L84] |
| 12 | Known limitations + next steps for production in README | > "In the README, list known limitations and what you would do next for production." [L95] |
| 13 | Honest NFR misses | > "If you do not meet an NFR, say so and explain why and what you would change." [L65] |
| 14 | Evidence of AI usage and verification (AI_LOG, reviewer output) | INFERRED from > "How you used AI agents and how you verified their output." [L93] |
| 15 | Concurrency / crash-recovery tests as *proof*, not prose | INFERRED from > "Correctness under concurrency, and how you **proved** it (tests, not claims)." [L90] |

## Evaluation criteria

| # | Criterion | Explicit / INFERRED |
|---|---|---|
| 1 | Correctness under concurrency, proven by tests | > "Correctness under concurrency, and how you **proved** it (tests, not claims)." [L90] |
| 2 | Security of a multi-tenant biometric API | > "Security of a multi-tenant biometric API." [L91] |
| 3 | Decisions and trade-offs | > "Your decisions and trade-offs (ADRs, README)." [L92] |
| 4 | AI usage and verification | > "How you used AI agents and how you verified their output." [L93] |
| 5 | Prioritisation – small verified secure beats large unverified | > "Prioritise; a smaller, verified and secure solution beats a larger unverified one." [L3] |
| 6 | Honesty about unmet NFRs | INFERRED from > "If you do not meet an NFR, say so and explain why" [L65] – overclaiming is penalised more than missing |
| 7 | Data understanding (domain sense) | INFERRED from > "anything notable you observe in the data." [L78] – the generator contains look-alike subjects and poor-quality captures on purpose |
| 8 | Runs out of the box | INFERRED from > "a git repository we can run with `docker compose up --build`" [L4] – a broken first run is likely fatal |

## Recommended time split (180 min, LIGHT route)

| Phase | Minutes |
|---|---|
| Triage | 10 |
| Analysis (analyst + product-owner, short) | 15 |
| Spec | 10 |
| Plan (architect + self-critique) | 15 |
| Implementation | 80 |
| Review (code + security + verifier) | 20 |
| Ship-check (fresh clone, README, evals, ZAP) | 15 |
| Buffer | 15 |

ASSUMPTION: the 180 min clock starts now; time already spent on tooling setup is not counted. If it is, cut buffer first, then review depth.

## Minimum analysis artifacts

| Artifact | Produce? | Why |
|---|---|---|
| TRIAGE.md | yes | always |
| DECISIONS.md | yes | always; the brief leaves real choices (transport, token issuance, Should cut) |
| ANALYSIS.md (short) | yes | domain model with lifecycle (subject → template versions; verification PENDING → MATCH/NO_MATCH/FAILED, attempts, lease) and the exactly-once invariants are the core of grading [L90]; data inventory needed for NFR-5 / erasure [L28, L62] |
| DOMAIN.md | no | the brief already defines the biometric terms precisely (1:1, cosine, FMR/FNMR, threshold) [L10, L44, L76]; GDPR points are explicit [L62]. Domain risks go into ANALYSIS.md |
| BACKLOG.md | yes | scope does not fit (checklist item 6); a costed MoSCoW cut is the main lever [L3] |

## Top 5 scope-changing questions

| # | Question | Impact | Proposed default |
|---|---|---|---|
| 1 | Which **Should** items fit after the Musts? [L85] | Decides ~40 % of the work | Must + **idempotency** (lives in the same POST) + **erasure** (small, GDPR story) + **crash-recovery test** (it is the proof for exactly-once [L45–46, L90]). Rate limiting = simple per-tenant limiter if time remains. Load test = k6 script + honest numbers. Encryption at rest = Won't, documented as known limitation |
| 2 | Transport between gateway and matcher? [L52] | Infra, exactly-once design, failure modes | **PostgreSQL `SKIP LOCKED` queue**: no extra infra, job state and result in one transaction, lease (`locked_until`) gives retry ≤ 30 s [L46], attempts counter gives "FAILED after 3" [L47], conditional final `UPDATE … WHERE status = 'PENDING'` gives exactly-once outcome. ADR records why not Redis/Rabbit/Kafka |
| 3 | How are tokens obtained? [L22–23] | User store vs none; endpoint security surface | **Dev-only token issuance** (profile-gated endpoint or script) minting RS256 tokens with `sub`, `tenant_id`, `scope`, `exp` (+ iss/aud/nbf/iat/jti as ASSUMPTION). Callers are tenant backends, not humans – no password/user store. Supersedes the pre-brief chat choice of a BCrypt `app_user` table (see Conflicts) |
| 4 | Where and how long is the **probe template** kept? | Schema, GDPR minimisation, erasure semantics | Probe stored on the verification job only until it is decided, then nulled; erasure deletes template versions and any undecided probes, keeps results without template data [L28, L62] |
| 5 | Eval tool and load test tooling? [L64, L74] | Language/runtime added to the repo | **Python 3 stdlib** eval tool next to the dataset generator (no new deps); **k6** via its Docker image for the load test |

## Readiness checklist

| # | Item | yes/no | Evidence |
|---|---|---|---|
| 1 | Concrete actors and use cases | yes | > "Tenants enrol a template for each of their end users (*subjects*). Later they ask us to verify a fresh template (*probe*) against the enrolled one (1:1 verification)." [L10] |
| 2 | Data/entities named | yes | > "a vector of 128 `float32` values, L2-normalised, sent as base64 (exactly 512 bytes before encoding, little-endian)" [L9]; > "`{"verificationId","subjectId","status","score","threshold","decidedAt"}`" [L30] |
| 3 | API or UI behaviour described | yes | Endpoint table [L25–31], e.g. > "Returns `201` with `{"subjectId","version"}`." [L27] |
| 4 | Testable acceptance criteria or examples | yes | > "Use `404`, not `403`, for other tenants' resources." [L36]; > "The same key with a different body returns `422`." [L37] |
| 5 | NFR/security expectations stated | yes | NFR-1…5 [L58–62]; > "All endpoints except health require a **JWT bearer token** (RS256)." [L21]; > "Template data and tokens must never appear in logs or error responses." [L40] |
| 6 | Scope fits 180 min | **no** | > "At least **two separately deployable applications** plus infrastructure" [L17] + exactly-once with crash recovery [L45–46] + load test [L64] + eval tool [L74] – the brief itself says > "Prioritise" [L3] |
| 7 | At most 3 open questions that change scope | yes | Scope is largely fixed by the MoSCoW table [L82–86]; the real scope questions are #1–#3 above (Should cut, transport, token issuance); #4–#5 are design-level |
| 8 | No explicit request for analysis/design deliverables | **no** (strict reading) | > "Justify it in an ADR, including how you guarantee the exactly-once outcome." [L52]; > "Your decisions and trade-offs (ADRs, README)." [L92] |

**Score: 6/8** (items 6 and 8 = no).
**Rule applied:** 8/8 → SKIP, 5–7 → LIGHT, ≤ 4 or item 8 = no → FULL ⇒ **rule result: FULL**.
Implementation budget: ≈ 90 min SKIP / ≈ 80 min LIGHT / ≈ 55 min FULL.

## Route recommendation

**Recommended: LIGHT** – deliberate deviation from the rule result (FULL). The human decides (D-1).

Why deviate: item 8 exists to catch type-C briefs where analysis documents *are* the product. Here the only design deliverable is an ADR,
which /plan produces on every route anyway. FULL would spend ~25 extra minutes on DOMAIN.md and a full analysis of a brief that already
defines its domain precisely, and cut implementation to ≈ 55 min – while the brief grades running, proven code
(> "a smaller, verified and secure solution beats a larger unverified one" [L3]). SKIP is ruled out by item 6: without a costed backlog
we would start building an unprioritised scope.

Suggested follow-up (not done here): refine checklist item 8 to "no explicit request for analysis deliverables beyond ADRs/README".

### 2 biggest risks of LIGHT

1. **Implementation overrun.** Even the Must row (two apps, queue, exactly-once, tenant isolation, tests, eval tool) is ≈ 80 min of work.
   *Mitigation:* BACKLOG estimates every story; MVP = Must only; Should items start only after the Must slice is green and committed;
   step 1 = multi-module walking skeleton (gateway + matcher + Postgres in compose) so integration risk surfaces first.
2. **Missed domain/concurrency edge cases** because DOMAIN.md and requirements-reviewer are skipped – e.g. erasure racing an in-flight
   job, threshold read at decision time [L44] not request time, idempotency key scoped per tenant, unknown subject → FAILED not 404 [L38].
   *Mitigation:* ANALYSIS.md lists invariants explicitly; run requirements-reviewer anyway (cheap, sonnet) before /spec;
   security-reviewer on every auth/persistence step.

## Conflicts with CLAUDE.md / CONTEXT.md / earlier chat decisions

The brief outranks them (CLAUDE.md §1: SPEC, derived from the brief, is the source of truth). To be resolved via DECISIONS.md / ADR:

| # | Existing rule or choice | Brief says | Proposed resolution |
|---|---|---|---|
| C-1 | CLAUDE.md §4: authorities from a `roles` claim | > "Required claims: `sub`, `tenant_id`, `scope`" [L21] > "(space-separated, values `enroll`, `verify`, `admin`)" [L22] | Map `scope` → `SCOPE_*` (Spring's default converter); update CLAUDE.md |
| C-2 | CLAUDE.md §4: actuator exposes health only | > "plus metrics: verifications by result, queue depth or lag, processing duration" [L61] | Expose `health` + `prometheus` (or `metrics`) only; document in ADR |
| C-3 | CONTEXT: one backend app | > "At least **two separately deployable applications**" [L17] | Gradle multi-module: `gateway`, `matcher` (+ shared module only if justified) |
| C-4 | CONTEXT `{{JWT_ISSUER}}`: `POST /api/auth/token`; chat: BCrypt `app_user` table | > "your own token endpoint or a dev script" [L23] | Dev-only token issuance without a user store (question #3) |
| C-5 | CLAUDE.md §4: 403 for insufficient rights | > "Use `404`, not `403`, for other tenants' resources." [L36] | Compatible: 403 = missing scope, 404 = other tenant's object |
