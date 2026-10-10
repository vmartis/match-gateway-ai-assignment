---
name: implement
description: Implement exactly one approved docs/PLAN.md step test-first – test-writer, minimal implementation, verify.sh, code/security review, fixes, AI_LOG, PLAN tick, one Conventional Commit. Argument = step number.
argument-hint: "<step number>"
disable-model-invocation: true
---

Step: $ARGUMENTS. Only this step. If empty, not in docs/PLAN.md, or not approved, stop and ask.

1. **Restate** the step: goal, AC ids (with their text from docs/SPEC.md), files to touch, verification command, minute budget.
   If anything conflicts with SPEC/ARCHITECTURE/CLAUDE.md, stop and ask.
2. **Tests first**: run the **test-writer** subagent for this step. Confirm the new tests fail for the right reason (show output).
3. **Minimal implementation** to make them pass. Nothing beyond the step's AC. Follow CLAUDE.md §3, §4, §9.
   New dependency → state exact coordinates and verify it resolves before using it.
4. Run `./scripts/verify.sh` until green. Show the real output.
5. **Review in parallel**: **code-reviewer** always; **security-reviewer** too if the step touches auth/JWT, input handling,
   persistence/SQL, uploads, logging, configuration or dependencies.
6. **Fix** every BLOCKER/MAJOR (code) and CRITICAL/HIGH (security). Rerun verify.sh and the reviewer(s) that raised them.
   Max 2 fix loops. Remaining findings → README.md "Known limitations" (finding, risk, what you would do next).
   Never weaken or skip tests to get green.
7. **Append docs/AI_LOG.md**: step, what the AI did, prompts/agents used, what the human changed or decided, review findings and their
   resolution; mark security-sensitive code "human-reviewed: pending" for the human to confirm.
8. **Tick** the step in docs/PLAN.md (`[x]`).
9. **Commit** the step as one Conventional Commit (CLAUDE.md §5b), tests in the same commit, files staged explicitly,
   `git status` + `git diff --staged` checked first. Example:
   ```
   feat(verification): add async verification endpoint

   Refs: step-N, AC-x
   ```
   Review fixes made after this commit go into a separate `fix(<scope>): ...` commit.
10. Report the commit hash + message and the Definition of Done checklist (CLAUDE.md §7) with each item ticked or not. Stop.
