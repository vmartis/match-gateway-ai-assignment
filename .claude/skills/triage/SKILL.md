---
name: triage
description: Classify the brief in docs/ASSIGNMENT.md, score its readiness and recommend a SKIP / LIGHT / FULL analysis route; writes docs/analysis/TRIAGE.md and records the human's route choice as D-1. Run first, before any analysis or spec.
argument-hint: "[optional notes for the triage]"
disable-model-invocation: true
---

Triage the assignment. Extra notes from the human: $ARGUMENTS

1. Read docs/ASSIGNMENT.md verbatim. Never edit it. If it is missing, empty, or still only the TEMPLATE comment, stop and ask the human to paste the brief.
   Also read CLAUDE.md and docs/CONTEXT.md. Time budget: 180 min total.
2. Write docs/analysis/TRIAGE.md with these sections. Quote the assignment verbatim as evidence (`> "…"`) wherever you claim something about it.
   - **Type of brief**: A = implementation-ready spec, B = high-level business requirement, C = analysis/design deliverable, D = mixed. One-paragraph justification with quotes.
   - **Expected deliverables**: explicit (quote) and implied (label INFERRED, say from what).
   - **Evaluation criteria**: explicit (quote) and inferred from the wording (quote the wording, label INFERRED).
   - **Recommended time split** of the 180 min (triage, analysis, spec, plan, implementation, review, ship-check, buffer).
   - **Minimum analysis artifacts** worth producing for this brief type (from TRIAGE, DECISIONS, ANALYSIS, DOMAIN, BACKLOG) and why.
   - **Top 5 scope-changing questions**, ranked by impact, each with a proposed default answer.
   - **Readiness checklist** – table: # | item | yes/no | evidence (quote, or "no quote found"):
     1 concrete actors and use cases; 2 data/entities named; 3 API or UI behaviour described; 4 testable acceptance criteria or examples;
     5 NFR/security expectations stated; 6 scope fits 180 min; 7 at most 3 open questions that change scope;
     8 no explicit request for analysis/design deliverables.
   - **Route recommendation**: 8/8 yes → SKIP; 5–7 → LIGHT; ≤ 4, or item 8 = no → FULL. Show the score and the rule applied.
     Implementation budget per route (docs/CONTEXT.md): ≈ 90 min SKIP / ≈ 80 min LIGHT / ≈ 55 min FULL.
   - **2 biggest risks of the recommended route** and how to mitigate them.
3. Do NOT decide the route. Present the score, recommendation and risks, then ask the human: SKIP, LIGHT or FULL?
4. Record the answer in docs/analysis/DECISIONS.md as D-1 (create the file if missing):
   `## D-1 Analysis route` – question, options (SKIP/LIGHT/FULL), decision, reason, decided by: human, date.
5. Commit as `docs(analysis): add triage and route decision` (CLAUDE.md §5b), show hash + message. Stop.
