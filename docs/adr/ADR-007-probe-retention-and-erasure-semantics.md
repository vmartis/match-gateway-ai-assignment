# ADR-007: Probe retention and erasure semantics

- **Status:** Proposed
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context
- NFR-5: "Store only what is needed".
- The brief: "Right to erasure: removes all template versions of the subject and its verification probes. Verification results stay, without template data."
- D-11: probe kept until decided. D-8: in-flight verifications end FAILED/SUBJECT_ERASED, never MATCH/NO_MATCH after the 204. A-3: version numbering restarts at 1. A-S3: erasure also deletes fingerprints.

## Decision
- The probe is nulled in the terminal UPDATE. The schema enforces `(status='PENDING') = (probe IS NOT NULL)`.
- `DELETE /v1/subjects/{id}` runs one transaction:
  1. delete the `subject` row; 0 rows → 404; versions cascade;
  2. `UPDATE verification SET status='FAILED', reason='SUBJECT_ERASED', probe=NULL, decided_at=now() WHERE tenant_id=? AND subject_id=? AND status='PENDING'`;
  3. delete the idempotency rows of that subject's verifications.
- A late worker write is a guarded no-op (ADR-003, since D-28 ADR-009: `WHERE id AND status='PENDING'`).

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| Null the probe and let the matcher discover the erasure | Matcher owns all terminal transitions | Needs a PENDING-with-NULL-probe state; harder to prove; decision window after the 204 |
| Keep probes for audit | Forensics | Violates NFR-5 minimisation |

## Consequences
- (+) No biometric data of an erased subject survives the commit.
- (+) The ordering test is deterministic.
- (−) The gateway also performs terminal transitions, so the counter is incremented in both apps.
- (−) A POST racing the erasure can end SUBJECT_NOT_ENROLLED (AM-9).
