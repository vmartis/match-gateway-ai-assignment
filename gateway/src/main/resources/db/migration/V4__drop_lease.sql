-- D-28 / ADR-009: jobs travel through RabbitMQ; the DB-queue lease columns of ADR-003 are gone. (The V2 comment that
-- mentions lease_token is historical: applied migrations are never edited, their checksum would change.)

-- Upgrade path (code review step 13): PENDING verifications created before the outbox existed (V3) were never published;
-- an outbox row lets the relay publish them. Decided rows need no job.
INSERT INTO outbox (verification_id)
SELECT id FROM verification WHERE status = 'PENDING'
ON CONFLICT DO NOTHING;

ALTER TABLE verification DROP CONSTRAINT verification_lease_only_while_pending;
ALTER TABLE verification DROP COLUMN lease_token, DROP COLUMN lease_until, DROP COLUMN attempts;
