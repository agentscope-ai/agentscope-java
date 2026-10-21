-- Copyright 2024-2026 the original author or authors.
-- Licensed under the Apache License, Version 2.0.
CREATE TABLE builder_portal_checkpoint (
    id VARCHAR(64) PRIMARY KEY,
    source_session_id VARCHAR(256) NOT NULL,
    target_session_id VARCHAR(256) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64),
    claim_token VARCHAR(64),
    claim_until BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL
);
CREATE INDEX ix_portal_checkpoint_due ON builder_portal_checkpoint (status,next_attempt_at,claim_until);
