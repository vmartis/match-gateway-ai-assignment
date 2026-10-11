-- Match Gateway schema (ARCHITECTURE.md §4 "Data model"). Flyway is owned by the gateway (D-7).

CREATE TABLE tenant_settings (
    tenant_id  varchar(64)      PRIMARY KEY,
    threshold  double precision NOT NULL CHECK (threshold >= 0 AND threshold <= 1),
    updated_at timestamptz      NOT NULL DEFAULT now()
);

CREATE TABLE subject (
    tenant_id    varchar(64) NOT NULL,
    subject_id   varchar(64) NOT NULL CHECK (subject_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    last_version int         NOT NULL CHECK (last_version >= 1),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, subject_id)
);

-- template: raw little-endian float32 x 128 – biometric, special category (NFR-5); kept until erasure (D-14).
CREATE TABLE template_version (
    tenant_id  varchar(64) NOT NULL,
    subject_id varchar(64) NOT NULL,
    version    int         NOT NULL CHECK (version >= 1),
    template   bytea       NOT NULL CHECK (octet_length(template) = 512),
    active     boolean     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, subject_id, version),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES subject (tenant_id, subject_id) ON DELETE CASCADE
);

-- INV-2: at most one active version per subject.
CREATE UNIQUE INDEX template_version_one_active ON template_version (tenant_id, subject_id) WHERE active;

-- The verification row is the job (ADR-003). No FK to subject: the subject may be unknown (BR-6).
CREATE TABLE verification (
    id          uuid             PRIMARY KEY,
    tenant_id   varchar(64)      NOT NULL,
    subject_id  varchar(64)      NOT NULL,
    status      varchar(16)      NOT NULL CHECK (status IN ('PENDING', 'MATCH', 'NO_MATCH', 'FAILED')),
    probe       bytea            CHECK (octet_length(probe) = 512),
    score       double precision,
    threshold   double precision,
    reason      varchar(32)      CHECK (reason IN ('SUBJECT_NOT_ENROLLED', 'SUBJECT_ERASED', 'MAX_ATTEMPTS')),
    attempts    int              NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    lease_token uuid,
    lease_until timestamptz,
    created_at  timestamptz      NOT NULL DEFAULT now(),
    decided_at  timestamptz,
    -- D-11: the probe exists exactly while the verification is pending.
    CONSTRAINT verification_probe_only_while_pending CHECK ((status = 'PENDING') = (probe IS NOT NULL)),
    CONSTRAINT verification_decided_iff_terminal CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT verification_reason_iff_failed CHECK ((status = 'FAILED') = (reason IS NOT NULL)),
    CONSTRAINT verification_scored_when_decided
        CHECK (status NOT IN ('MATCH', 'NO_MATCH') OR (score IS NOT NULL AND threshold IS NOT NULL)),
    CONSTRAINT verification_lease_only_while_pending CHECK (status = 'PENDING' OR lease_token IS NULL)
);

CREATE INDEX verification_pending ON verification (created_at) WHERE status = 'PENDING';
CREATE INDEX verification_by_subject ON verification (tenant_id, subject_id);

-- D-10: keyed HMAC fingerprint of (subjectId, probe bytes); the raw probe is never stored here.
CREATE TABLE idempotency_key (
    tenant_id       varchar(64) NOT NULL,
    idem_key        varchar(64) NOT NULL,
    fingerprint     bytea       NOT NULL CHECK (octet_length(fingerprint) = 32),
    verification_id uuid        NOT NULL REFERENCES verification (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idem_key)
);

CREATE INDEX idempotency_key_by_verification ON idempotency_key (verification_id);
