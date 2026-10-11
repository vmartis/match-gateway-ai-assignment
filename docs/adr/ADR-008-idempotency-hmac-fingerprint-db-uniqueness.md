# ADR-008: Idempotency via HMAC fingerprint and DB uniqueness

- **Status:** Proposed
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context
- The brief: "the same `Idempotency-Key` with the same body within 24 h returns the original `verificationId` (`200` or `202`). The same key with a different body returns `422`."
- D-10: 202 while PENDING, 200 once decided; same body = HMAC of the parsed subjectId and probe bytes, per tenant; raw probe not kept.
- D-17: concurrency relies on a DB uniqueness guarantee.

## Decision
- PK `(tenant_id, idem_key)`. In one transaction:
  1. INSERT the verification;
  2. `INSERT idempotency_key … ON CONFLICT (tenant_id, idem_key) DO UPDATE SET … WHERE idempotency_key.created_at < now() - interval '24 hours' RETURNING verification_id`.
- One row returned → commit, 202.
- Zero rows → roll back, then read the existing record. If the fingerprint matches (`MessageDigest.isEqual`), return the original id with 202 or 200. Otherwise 422.
- The fingerprint is `HmacSHA256` (JDK `javax.crypto.Mac`) over `tenant ‖ 0x00 ‖ subjectId ‖ 0x00 ‖ 512 probe bytes`. The key comes from the keygen volume.

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| Plain SHA-256 of the body | No key management | A deterministic hash of biometric data can be linked or brute-forced |
| Store the raw body for comparison | Exact | Keeps biometric data 24 h longer than needed |
| App-level lock or in-memory map | Simple | Breaks with more than one gateway instance |

## Consequences
- (+) 10 concurrent identical requests produce exactly one verification, because the losers block on the unique index and then replay.
- (−) Replays write and roll back one row.
- (−) Expired keys are only replaced lazily. There is no cleanup job, so a unique-key flood (MC-4b) is a documented limitation.
