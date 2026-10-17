-- idempotency-kit record table for SQL Server (SPEC section 6.7); NCHAR keys match the driver's Unicode parameters
CREATE TABLE idempotency_record (
    scope_key        NCHAR(64)         NOT NULL,
    idempotency_key  NVARCHAR(255)     NOT NULL,
    client_id        NVARCHAR(255)     NOT NULL,
    http_method      NVARCHAR(10)      NOT NULL,
    route            NVARCHAR(512)     NOT NULL,
    fingerprint      NCHAR(64)         NOT NULL,
    state            NVARCHAR(16)      NOT NULL,
    owner_token      NCHAR(36),
    lease_until      DATETIMEOFFSET(6),
    response_status  SMALLINT,
    response_headers NVARCHAR(MAX),
    response_body    VARBINARY(MAX),
    body_omitted     BIT               NOT NULL DEFAULT 0,
    codec_key_id     NVARCHAR(64),
    created_at       DATETIMEOFFSET(6) NOT NULL,
    completed_at     DATETIMEOFFSET(6),
    expires_at       DATETIMEOFFSET(6) NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (scope_key),
    CONSTRAINT ck_idempotency_record_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);
