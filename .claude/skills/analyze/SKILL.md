---
name: analyze
description: Run the analysis pipeline (analyst → domain-expert → human decisions → product-owner → requirements-reviewer) and write docs/analysis/ANALYSIS.md, DOMAIN.md, DECISIONS.md and BACKLOG.md. Use after /triage chose LIGHT or FULL. Argument "light" = analyst + product-owner only.
argument-hint: "[light]"
disable-model-invocation: true
---

Mode: `$ARGUMENTS` ("light" = LIGHT route: analyst + product-owner only; empty = full pipeline).

Preconditions: docs/analysis/TRIAGE.md and D-1 in docs/analysis/DECISIONS.md exist. If not, stop and tell the human to run /triage.
Subagents are read-only advisers: they return text; YOU write the files; the human decides. Keep their traceability labels
(`[A: "quote"]`, `[D-x]`, ASSUMPTION, INFERRED) intact when writing.

## Full mode
1. Run the **analyst** subagent on docs/ASSIGNMENT.md. Write its output to docs/analysis/ANALYSIS.md.
2. Run the **domain-expert** subagent on that draft (sequentially – it needs the draft; pass it the file path).
   Write docs/analysis/DOMAIN.md: glossary, mermaid `classDiagram` domain model, mermaid `stateDiagram-v2` state machine,
   the expert's corrections, added BR/NFR, domain risks, eval metrics. Apply its corrections to ANALYSIS.md and note them.
3. Present to the human the open questions and solution-option choices (ranked by impact, each with the proposed default).
   Ask; do not decide. Record each answer in docs/analysis/DECISIONS.md as D-x:
   `## D-x <title>` – question, options, decision, reason, decided by (human / accepted default), date.
4. Run the **product-owner** subagent. Write docs/analysis/BACKLOG.md.
5. Run the **requirements-reviewer** subagent. Fix its findings in the analysis docs (BLOCKER/MAJOR always; MINOR if cheap).
   Anything that needs a human decision goes back to step 3. Rerun the reviewer once if there were BLOCKERs.
6. Commit as `docs(analysis): add analysis, domain model and backlog` (CLAUDE.md §5b), show hash + message.
7. Present:
   - MVP stories table: id | story | value | estimate min | MoSCoW (plus total vs implementation budget from TRIAGE.md)
   - decisions still needed (with proposed defaults)
   - reviewer verdict.
   Stop.

## Light mode
Steps 1 (ANALYSIS.md kept short: problem, actors, domain model, BR/NFR, assumptions, open questions), 3, 4, 6, 7.
Skip domain-expert and requirements-reviewer.
