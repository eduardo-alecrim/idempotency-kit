-- idempotency-kit record table for Oracle (SPEC section 6.7); NUMBER(1) booleans keep it compatible with 19c
CREATE TABLE idempotency_record (
    scope_key        CHAR(64 CHAR)            NOT NULL,
    idempotency_key  VARCHAR2(255 CHAR)       NOT NULL,
    client_id        VARCHAR2(255 CHAR)       NOT NULL,
    http_method      VARCHAR2(10 CHAR)        NOT NULL,
    route            VARCHAR2(512 CHAR)       NOT NULL,
    fingerprint      CHAR(64 CHAR)            NOT NULL,
    state            VARCHAR2(16 CHAR)        NOT NULL,
    owner_token      CHAR(36 CHAR),
    lease_until      TIMESTAMP WITH TIME ZONE,
    response_status  NUMBER(3),
    response_headers CLOB,
    response_body    BLOB,
    body_omitted     NUMBER(1) DEFAULT 0      NOT NULL,
    codec_key_id     VARCHAR2(64 CHAR),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (scope_key),
    CONSTRAINT ck_idempotency_record_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_record_omitted CHECK (body_omitted IN (0, 1))
);

CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);
