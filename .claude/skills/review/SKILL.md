---
name: review
description: Run code-reviewer, security-reviewer and verifier in parallel on a target (default the whole branch), merge into one deduplicated findings table with FIX/ACCEPT/REJECT proposals, wait for human approval, apply fixes, re-verify (max 2 loops).
argument-hint: "[target: step N | commit range | path; default whole branch]"
disable-model-invocation: true
---

Target: `$ARGUMENTS` (empty = whole branch: everything since the first commit / main).

1. Run in parallel, all on the same target: **code-reviewer**, **security-reviewer**, **verifier**.
2. Merge their outputs into ONE deduplicated table (same root cause = one row, list all sources):
   id | severity (normalized: BLOCKER≈CRITICAL, MAJOR≈HIGH, MINOR≈MEDIUM, NIT≈LOW) | source agent(s) | file:line or AC | issue |
   proposal FIX / ACCEPT (known limitation, with reason) / REJECT (false positive, with evidence).
   Add the verifier's AC table and failing checks as rows too.
3. Present the table and wait for the human's approval per row. Do not change anything before approval.
4. Apply approved FIXes (tests first where a test is missing). Run ./scripts/verify.sh (show output).
   ACCEPTed rows → README.md "Known limitations". Append docs/AI_LOG.md.
5. Rerun the **verifier** (and the reviewer that raised each fixed BLOCKER/CRITICAL). Max 2 loops; whatever remains goes back to the human.
6. Commit the round as `fix(<scope>): address review findings` (CLAUDE.md §5b; one commit per review-fix round), show hash + message. Stop.
