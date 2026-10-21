-- Copyright 2024-2026 the original author or authors.
-- Licensed under the Apache License, Version 2.0.
ALTER TABLE builder_portal_session ADD COLUMN title VARCHAR(500);
ALTER TABLE builder_portal_session ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE builder_portal_session ADD COLUMN creation_request_digest VARCHAR(64);
CREATE TABLE builder_session_api_state (
    id VARCHAR(256) PRIMARY KEY,
    source_sequence BIGINT NOT NULL,
    event_sequence BIGINT NOT NULL,
    published_sequence BIGINT NOT NULL,
    input_sequence BIGINT NOT NULL,
    data TEXT NOT NULL,
    version BIGINT NOT NULL
);
CREATE TABLE builder_session_api_event (
    id VARCHAR(80) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(64),
    reference VARCHAR(512),
    type VARCHAR(128) NOT NULL,
    seq BIGINT NOT NULL,
    published_sequence BIGINT NOT NULL,
    data TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL,
    processed_at BIGINT,
    CONSTRAINT uq_session_api_event_seq UNIQUE(session_id, seq)
);
CREATE INDEX ix_session_api_stream ON builder_session_api_event(session_id, published_sequence);
CREATE INDEX ix_session_api_history ON builder_session_api_event(session_id, processed_at, seq);
CREATE INDEX ix_session_api_reference ON builder_session_api_event(session_id, reference, type);
CREATE TABLE builder_session_api_input (
    id VARCHAR(80) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    seq BIGINT NOT NULL,
    type VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    actor TEXT NOT NULL,
    data TEXT NOT NULL,
    batch_digest VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64),
    command_id VARCHAR(64),
    reference VARCHAR(512),
    CONSTRAINT uq_session_api_input_seq UNIQUE(session_id, seq)
);
CREATE INDEX ix_session_api_input_pending ON builder_session_api_input(status, session_id, seq);
CREATE INDEX ix_session_api_input_turn ON builder_session_api_input(session_id, turn_id, type);
CREATE INDEX ix_session_api_input_reference ON builder_session_api_input(session_id, reference, type);
