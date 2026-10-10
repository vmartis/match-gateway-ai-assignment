---
name: plan
description: Delegate to the architect subagent to produce docs/ARCHITECTURE.md, ADRs and docs/PLAN.md (6–12 vertical-slice steps), then self-critique as a staff engineer and stop for approval. Use after docs/SPEC.md is approved. Argument = implementation time budget in minutes.
argument-hint: "<implementation minutes>"
disable-model-invocation: true
---

Implementation time budget: $ARGUMENTS minutes. If empty, use the number in docs/analysis/TRIAGE.md for the chosen route
(D-1 in docs/analysis/DECISIONS.md), else ask.

Precondition: docs/SPEC.md exists (not only SPEC.draft.md). If not, stop and ask.

1. Run the **architect** subagent with the budget. It is read-only and returns text; you write the files.
2. Write from its output:
   - docs/ARCHITECTURE.md – ambiguities/assumptions, scope cut, domain model, components, data model, API table, threat model, evals approach.
   - docs/adr/ADR-00x-<kebab-title>.md – one per ADR candidate (Context / Decision / Consequences / Status: Proposed).
     Always include CSRF-disabled-for-stateless-bearer-API and app-issued RS256 JWT.
   - docs/PLAN.md – table: `[ ]` | step | goal | files | AC ids | tests first | verification command | minutes. Step 1 = walking skeleton.
     Sum of minutes ≤ budget.
3. Self-critique as a staff engineer, in writing, then apply the changes to the three docs:
   - over-scope (anything not traced to SPEC → cut),
   - riskiest step (move earlier / split / add spike),
   - security gaps (every threat in the threat model mapped to a step and a test; JWT negative set present),
   - can step 1 be smaller and still prove build + test + security + DB + docker end to end?
4. Present: summary table (step | goal | AC | minutes), total vs budget, critique changes applied, must-confirm items.
5. Stop for approval. After the human approves, commit as `docs(plan): add architecture, adrs and implementation plan` and show hash + message.
