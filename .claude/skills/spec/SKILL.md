---
name: spec
description: Write docs/SPEC.draft.md (goal, actors, Given/When/Then AC, NFRs, non-goals, assumptions, open questions) from docs/ASSIGNMENT.md and docs/analysis/. Use after /triage (SKIP) or /analyze (LIGHT/FULL); the human promotes the draft to docs/SPEC.md.
argument-hint: "[optional focus notes]"
disable-model-invocation: true
---

Notes from the human: $ARGUMENTS

1. Read docs/ASSIGNMENT.md, CLAUDE.md, and docs/analysis/ (whatever exists). Never edit docs/SPEC.md – write docs/SPEC.draft.md only.
2. Write docs/SPEC.draft.md with sections:
   - **Goal** (1–3 sentences), **Actors**.
   - **Acceptance criteria** AC-1..n in Given/When/Then; each concrete and testable (inputs, status codes, outputs).
   - **NFRs** with numbers (latency, limits, retention, security). No adjectives without numbers.
   - **Non-goals**.
   - **Assumptions**: A-x | assumption | why | risk if wrong.
   - **Open questions**: at most 7, each with a proposed default.
3. Source rules depend on the route:
   - **BACKLOG.md exists (LIGHT/FULL)**: AC ONLY for MVP stories. Each AC links its `US-x` and quotes its source (`[A: "…"]`) or `[D-x]`.
     Non-goals = BACKLOG "Won't have now".
   - **No BACKLOG.md (SKIP)**: number the assignment's requirements R-x, each with a verbatim quote. Derive AC directly from them (link R-x).
     Add the missing negative/security AC yourself (401 no/invalid token, 403 wrong role, 404/403 other user's object, 400 validation,
     input limits) marked INFERRED. List everything that looks under-specified; if more than 3 such gaps change scope,
     recommend switching to LIGHT (/analyze light) instead of guessing.
4. Check: every AC traces to a quote, D-x, US-x or is marked INFERRED; no requirement without a source.
5. Show the AC list and open questions; tell the human to review and rename to docs/SPEC.md themselves (or explicitly ask you to). Stop.
