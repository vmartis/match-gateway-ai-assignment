# ADR-009: RabbitMQ transport with a transactional outbox; exactly-once outcome stays in PostgreSQL

- **Status:** Proposed (supersedes ADR-003)
- **Date:** 2026-10-11
- **Deciders:** human (D-28) + AI proposal

## Context
- The brief: "The transport between gateway and matcher is **your choice** … Justify it in an ADR, including how you guarantee the
  exactly-once outcome." "no duplicate or conflicting results even if a replica crashes mid-processing"; "retried and decided
  within 30 s"; "A job that fails 3 times ends as `FAILED` with a reason."
- D-28 (human): replace the PostgreSQL `SKIP LOCKED` polling queue (ADR-003) with RabbitMQ running in docker compose; publish via a
  transactional outbox; retries and the attempt cap via quorum-queue delivery limits; replace the DB-queue tests with broker-based
  tests that prove the same ACs.
- Verified RabbitMQ 4.x semantics (rabbitmq.com/docs/quorum-queues, /docs/consumers, read 2026-10-11):
  - Quorum queues count deliveries; `basic.reject`, client crash/connection loss and consumer timeout increment the count,
    **`basic.nack` does not**. Default delivery limit is 20; over the limit the message is dropped or dead-lettered.
  - `at-least-once` dead-lettering requires `overflow=reject-publish`.
  - The delivery-acknowledgement (consumer) timeout is evaluated at one-minute intervals; values below one minute are not supported.

## Decision
- **Source of truth stays PostgreSQL.** The `verification` row holds status and outcome; the message carries only the
  verification id (no biometric data in the broker, NFR-5).
- **Publishing (no dual write):** the gateway inserts the verification, its idempotency record and an `outbox` row in one
  transaction. After commit it publishes the id eagerly (publisher confirms, persistent message) and deletes the outbox row on
  confirm. A relay in the gateway re-publishes outbox rows older than a few seconds (lost publish, broker down, gateway crash).
  Duplicate publishes are harmless (see exactly-once).
- **Topology** (declared by the apps, idempotent): durable direct exchange `matchgw.verifications` → quorum queue
  `matchgw.verifications` with `x-delivery-limit=3`, `x-dead-letter-exchange=matchgw.verifications.dlx`,
  `x-dead-letter-strategy=at-least-once`, `x-overflow=reject-publish`; dead-letter quorum queue `matchgw.verifications.dead`.
- **Consuming:** manual acknowledgement. Per message the matcher decides and writes the outcome in one transaction with the
  guarded terminal write `UPDATE … WHERE id=? AND status='PENDING'`, then acks **after commit**. Effects (counter, latency) are
  recorded only if 1 row changed. A message for a row that is already terminal (duplicate, redelivery, erased) is acked without
  effect. `SUBJECT_NOT_ENROLLED` is terminal on the first delivery. On a processing error the matcher calls `basic.reject` with
  requeue (never `nack`, which would not count toward the limit).
- **Attempt cap:** after 3 deliveries the queue dead-letters the message; a dead-letter consumer writes `FAILED`/`MAX_ATTEMPTS`
  with the same guarded write and acks.
- **Crash and stall (NFR-6, 30 s):** a crashed replica's connection closes and RabbitMQ requeues its unacked message at once
  (counted as a delivery). A stalled-but-connected worker is not covered by RabbitMQ's consumer timeout (≥ 1 min), so the matcher
  bounds processing itself: a per-message deadline (DB statement timeout + bounded compute, default 10 s) after which it rejects
  the message; a hung JVM fails its healthcheck and is restarted, closing its connection.
- **Exactly-once outcome** rests, as before, on the guarded terminal write plus the V2 trigger (decided rows are immutable);
  delivery is at-least-once. Erasure (ADR-007) is unchanged: it fails pending rows in the DB; a later message is a no-op.
- **Lease columns** (`lease_token`, `lease_until`, `attempts`) and the poll/claim/reaper code are removed (migration V4).
- **Proof by tests** with a RabbitMQ Testcontainer: 500 jobs / 3 consumers (AC-34), duplicate and redelivered messages after the
  outcome (AC-35, AC-36), always-failing job → dead-lettered after 3 deliveries → MAX_ATTEMPTS (AC-27), consumer connection killed
  mid-processing → redelivered and decided ≤ 30 s (AC-28), erasure ordering (AC-49), outbox atomicity and re-publish.
- **Operations:** RabbitMQ 4.x image pinned by digest, no management plugin, port not published, credentials generated on first
  start (like the DB password), data on a named volume; readiness of both apps includes the broker.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| Keep ADR-003 (PG `SKIP LOCKED` polling) | One store; proofs exist; fewer moving parts | Polling latency and load; the human prefers a broker (D-28) |
| Publish after commit + sweeper (no outbox) | Simpler | A lost publish waits for the sweeper; reconciliation scans the verification table |
| RabbitMQ as wake-up signal only, DB lease/attempts stay | Keeps lease proofs | Two coordination mechanisms; still needs DB recovery logic |
| Kafka / Redis Streams | Scale-out, replay | Heavier ops (Kafka) or weaker durability (Redis); same dual-write problem |

## Consequences
- (+) No polling of the verification table; push delivery; native redelivery on crash; dead-letter queue for poison jobs.
- (+) The broker never sees biometric data; the exactly-once argument is unchanged and local to PostgreSQL.
- (−) Extra component (RabbitMQ) with its own credentials, volume, health and failure modes; outbox table and relay.
- (−) NFR-6 for a stalled-but-connected worker depends on the matcher's own deadline, not on the broker.
- (−) A-2 changes meaning: a redelivery after a crash counts as one of the 3 attempts (delivery count), as the lease expiry did.
- Requires SPEC changes (transport line, AC-27, AC-28, AC-34..AC-36, A-2, NFR-6/7) – proposed in docs/SPEC.draft.md.
