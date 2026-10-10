---
name: ship-check
description: Final pre-submission check – fresh clone following the README literally, README completeness, secrets/TODO/debug/unused-deps scan, AC→test matrix in docs/EVALS.md, OWASP ZAP scan and OWASP matrix in docs/SECURITY.md; outputs a PASS/FAIL checklist and never fixes silently.
argument-hint: "[optional notes]"
disable-model-invocation: true
---

Notes: $ARGUMENTS. Report problems; do NOT fix anything silently. Only docs/EVALS.md and docs/SECURITY.md are written.

1. **Fresh clone**: warn if `git status` shows uncommitted changes (they won't be in the clone). `git clone` the repo into a temp dir
   (`mktemp -d`). Follow README.md literally, command by command, with nothing from your memory or the original working tree.
   Use a distinct compose project name (`docker compose -p shipcheck ...`) so it doesn't clash with a running dev stack.
   Every step that fails or needs knowledge not in the README = FAIL. Tear down (`docker compose -p shipcheck down -v`) and delete the temp dir.
2. **README sections** present and accurate: overview, prerequisites, how to run, how to test, configuration/env vars, API usage
   (incl. getting a token), architecture + decisions (links to docs/), security notes, Known limitations, AI usage (link to docs/AI_LOG.md).
3. **Scans** (report file:line):
   - secrets: `gitleaks detect` if installed (incl. history), otherwise grep for keys/private keys/password literals; no `.env`/keys tracked by git.
   - TODO / FIXME / XXX, debug output (`println`, `printStackTrace`, `console.log`), commented-out code, @Disabled tests.
   - unused dependencies: each declared dependency is used in code/config (grep its packages); flag the ones that aren't.
4. **docs/EVALS.md**: AC→test matrix – AC id | AC text | test(s) file::name | level | last result. Every AC from docs/SPEC.md must
   have ≥ 1 passing test; uncovered AC = FAIL. Run `./scripts/verify.sh` for the results (show output).
5. **Security**: start the app, run `./scripts/owasp-scan.sh` (show real output), tear down. Refresh the OWASP matrix in docs/SECURITY.md:
   category (Top 10:2025 A01–A10, API Top 10:2023 API1–API10) | status covered/N/A/gap | evidence (test or ZAP rule) | notes.
   If the script is missing or cannot run, write "not run" – never invent results.
6. **Output**: PASS/FAIL checklist (clone & run, README, secrets, TODO/debug, deps, AC coverage, verify.sh, ZAP, OWASP matrix,
   PLAN all ticked, AI_LOG complete) with evidence per line, then the list of FAILs with proposed fixes. Stop – the human decides what to fix.
