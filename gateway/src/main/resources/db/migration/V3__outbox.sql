-- Transactional outbox (ADR-009): a row per verification whose job message is not yet confirmed by the broker. Written in
-- the same transaction as the verification, deleted on publisher confirm; the relay re-publishes what is left.
CREATE TABLE outbox (
    verification_id uuid        PRIMARY KEY REFERENCES verification (id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX outbox_by_age ON outbox (created_at);
