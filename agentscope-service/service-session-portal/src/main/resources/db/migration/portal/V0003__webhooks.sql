-- Portal subscription cursor, private claim and public delivery metadata.
CREATE TABLE builder_portal_webhook (
    id VARCHAR(64) PRIMARY KEY, session_id VARCHAR(256) NOT NULL, url VARCHAR(4096) NOT NULL,
    actor TEXT NOT NULL, secret TEXT NOT NULL, event_types TEXT NOT NULL, input_digest VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL, cursor_seq BIGINT NOT NULL, pending_event VARCHAR(64), pending_seq BIGINT NOT NULL,
    delivery_id VARCHAR(64), attempt_count INTEGER NOT NULL, claim_token VARCHAR(64), claim_fence BIGINT NOT NULL,
    claim_until BIGINT NOT NULL, next_attempt_at BIGINT NOT NULL, failure_code VARCHAR(64),
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, version BIGINT NOT NULL
);
CREATE INDEX ix_portal_webhook_session ON builder_portal_webhook(session_id,id);
CREATE INDEX ix_portal_webhook_limit ON builder_portal_webhook(session_id,state,id);
CREATE INDEX ix_portal_webhook_due ON builder_portal_webhook(state,next_attempt_at,claim_until,id);
CREATE TABLE builder_portal_webhook_delivery (
    id VARCHAR(64) PRIMARY KEY, webhook_id VARCHAR(64) NOT NULL, event_id VARCHAR(64) NOT NULL, event_seq BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL, claim_fence BIGINT NOT NULL, claim_token VARCHAR(64) NOT NULL,
    http_status INTEGER NOT NULL, error_code VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX ix_portal_webhook_receipts ON builder_portal_webhook_delivery(webhook_id,id);
