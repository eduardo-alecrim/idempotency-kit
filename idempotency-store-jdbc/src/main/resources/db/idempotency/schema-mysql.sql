-- idempotency-kit record table for MySQL (SPEC section 6.7); timestamps hold UTC
CREATE TABLE idempotency_record (
    scope_key        CHAR(64)     NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    client_id        VARCHAR(255) NOT NULL,
    http_method      VARCHAR(10)  NOT NULL,
    route            VARCHAR(512) NOT NULL,
    fingerprint      CHAR(64)     NOT NULL,
    state            VARCHAR(16)  NOT NULL,
    owner_token      CHAR(36),
    lease_until      DATETIME(6),
    response_status  SMALLINT,
    response_headers TEXT,
    response_body    LONGBLOB,
    body_omitted     BOOLEAN      NOT NULL DEFAULT FALSE,
    codec_key_id     VARCHAR(64),
    created_at       DATETIME(6)  NOT NULL,
    completed_at     DATETIME(6),
    expires_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (scope_key),
    CONSTRAINT ck_idempotency_record_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);
