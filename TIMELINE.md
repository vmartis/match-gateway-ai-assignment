# Timeline – process phases and active time

Active working time per phase, measured from the Claude Code session transcript (10 Oct 23:10 – 12 Oct 12:04 local time).
Time spent waiting for the human (answers, decisions, plan approvals) and idle periods are excluded – see "Method" below.

| # | Phase group | Phase | Started (local) | Active |
|---|---|---|---|---|
| 1 | **Setup** | Claude Code governance: CLAUDE.md, hooks, subagents, skills, docs templates | 10 Oct 23:10 | 39 min |
| 2 | | Chore: internet-access policy and net-guard hook | 11 Oct 09:22 | 9 min |
| 3 | **Analysis → Plan** | Triage | 11 Oct 00:10 | 2 min |
| 4 | | Analysis (LIGHT) and human decisions | 11 Oct 00:17 | 12 min |
| 5 | | Spec | 11 Oct 07:31 | 2 min |
| 6 | | Plan (architecture, ADRs, PLAN) | 11 Oct 07:35 | 61 min |
| 7 | **Implementation v1** (DB queue) | Step 1a walking skeleton, build | 11 Oct 08:55 | 25 min |
| 8 | | Steps 1b containers and 2 auth/dev tokens | 11 Oct 09:31 | 40 min |
| 9 | | Fix: standard actuator health (D-23) | 11 Oct 10:11 | 7 min |
| 10 | | Step 3 enrolment, validation, errors | 11 Oct 10:20 | 30 min |
| 11 | | Step 4 submit and read verification | 11 Oct 10:49 | 22 min |
| 12 | | Step 5 matcher, plus P1 eval and P2 k6 in parallel | 11 Oct 11:24 | 16 min |
| 13 | | Step 6 exactly-once proof | 11 Oct 11:41 | 28 min |
| 14 | | Step 7 tenant isolation, threshold, leakage | 11 Oct 12:09 | 18 min |
| 15 | | Step 8 erasure | 11 Oct 12:31 | 13 min |
| 16 | | Step 9 idempotency | 11 Oct 12:45 | 15 min |
| 17 | | Step 10 metrics and P1/P2 integration | 11 Oct 13:10 | 17 min |
| 18 | **Ship-check #1** | Fresh clone, 60 s load test, EVALS and SECURITY | 11 Oct 13:29 | 24 min |
| 19 | **RabbitMQ re-design** | Re-plan (D-28, ADR-009, PLAN steps 11–14, SPEC draft) | 11 Oct 15:22 | 7 min |
| 20 | | Step 11 RabbitMQ infrastructure | 11 Oct 16:28 | 50 min |
| 21 | | Step 12 transactional outbox | 11 Oct 22:17 | 38 min |
| 22 | | Step 13 matcher consumer replaces polling | 11 Oct 23:09 | 85 min |
| 23 | | Step 14 drop lease columns, docs, live run | 12 Oct 10:11 | 41 min |
| 24 | **Ship-check #2 and wrap-up** | Ship-check on the RabbitMQ design | 12 Oct 10:53 | 32 min |
| 25 | | Status and README sections | 12 Oct 11:40 | 7 min |
| 26 | | Timeline report | 12 Oct 12:03 | 1 min |
| | | **Total** | | **10 h 40 min** |

## By phase group

| Phase group | Active |
|---|---|
| Setup | 48 min |
| Analysis → Plan | 77 min |
| Implementation v1 (steps 1a–10) | 3 h 51 min |
| Ship-check #1 | 24 min |
| RabbitMQ re-design (steps 11–14) | 3 h 41 min |
| Ship-check #2 and wrap-up | 40 min |
| **Total** | **10 h 40 min** |

Against the 180-minute budget: the first full version (through ship-check #1) took about 7 h of active time; the RabbitMQ
re-design (D-28) and its ship-check added about 4 h.

## Method

- Source: timestamps of every event (prompts, model turns, tool calls and results, subagent notifications) in the session
  transcript; each event belongs to the phase opened by the most recent human prompt.
- Excluded: every gap that ended in a human message or in the human's answer to a question or plan approval (≈ 9 h 46 min,
  including the overnight breaks).
- Capped: a gap with no activity counts at most 15 min, a single tool run at most 30 min (≈ 16 h 26 min removed, including a
  security review in step 11 that waited about 5 h on permission prompts).
- Caveat: subagent work is only visible at its start and end, so a subagent running longer than 15 min uninterrupted is
  undercounted – the totals are a slight underestimate.
