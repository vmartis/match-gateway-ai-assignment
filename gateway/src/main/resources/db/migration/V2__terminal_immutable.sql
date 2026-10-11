-- INV-1 defence in depth (ADR-003): once a verification is decided (MATCH, NO_MATCH, FAILED) an UPDATE of the row fails.
-- The application guards (status = 'PENDING' AND lease_token = :token) stay the primary mechanism; this trigger makes a
-- bug or a stray UPDATE fail loudly. DELETE remains possible (erasure/retention). Not a control against the table owner
-- or a superuser, who can disable triggers (README "Known limitations").

CREATE FUNCTION verification_terminal_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    -- No row data in the message: errors may be logged (CLAUDE.md §4).
    RAISE EXCEPTION 'decided verifications are immutable' USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER verification_terminal_immutable
    BEFORE UPDATE ON verification
    FOR EACH ROW
    WHEN (OLD.status <> 'PENDING')
EXECUTE FUNCTION verification_terminal_immutable();
