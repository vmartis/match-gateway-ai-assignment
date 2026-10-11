# ADR-003: PostgreSQL SKIP LOCKED queue and exactly-once outcome

- **Status:** Proposed
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context
- The brief: "The transport between gateway and matcher is **your choice** … Justify it in an ADR, including how you guarantee the exactly-once outcome."
- "no duplicate or conflicting results even if a replica crashes mid-processing"; "retried and decided within 30 s"; "A job that fails 3 times ends as `FAILED` with a reason."
- D-3 chose a PostgreSQL `SKIP LOCKED` queue with lease and attempts.

## Decision
- The `verification` row *is* the job. The gateway INSERTs it as PENDING in the same transaction as the idempotency record, so there is no dual write.
- Matchers claim jobs with `SELECT … FOR UPDATE SKIP LOCKED` inside an `UPDATE` that sets `attempts+1`, a fresh `lease_token` (random UUID) and `lease_until = now()+lease`.
- The terminal write is `UPDATE … WHERE id=? AND status='PENDING' AND lease_token=?`. Effects (counter, latency histogram) are recorded only if 1 row changed. A stale holder's token no longer matches, or the row is already terminal, so its write changes 0 rows.
- Every claim is an attempt (A-2). Exceptions release the lease with a 1 s backoff. A reaper ends rows with `attempts ≥ 3` whose lease has expired as `FAILED`/`MAX_ATTEMPTS`.
- Defaults: lease 10 s, idle poll 200 ms (≤ 30 s is asserted at startup), batch 20. The DB clock is the only clock.
- A trigger forbids any UPDATE of a terminal row.
- Erasure terminal-fails pending rows in its own transaction (ADR-007).
- Proof is by tests: 500 jobs with 3 concurrent claimers (AC-34), a deterministic stale-lease writer (AC-35), no reclaim of terminal rows (AC-36), the lease-expiry reclaim (AC-28), and the attempts cap (AC-27).
- SQL is written with `JdbcClient` (Spring Data JDBC aggregates are not used).

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| Redis Streams + PG | Low latency; consumer-group reclaim | Dual write needs an outbox; exactly-once still rests on a PG conditional write; extra component |
| RabbitMQ / Kafka | Industry scale-out | Same dual-write problem; heavy ops; rebalances complicate the 30 s bound |
| PG queue + LISTEN/NOTIFY wake-up | Lower NFR-2 latency | More moving parts; deferred as a tuning lever |

## Consequences
- (+) One transactional store; correctness is local and testable.
- (−) Polling adds on average about 100 ms of latency.
- (−) The DB is the throughput ceiling at 300/s × 3 replicas (measured in the load test, reported honestly).
- Exactly-once is "from the client's point of view": a crash mid-processing can cause recomputation, never two outcomes.
- Production next step: partitioning or a broker with the same conditional write.
