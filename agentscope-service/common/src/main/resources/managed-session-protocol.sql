-- Managed Agents public Session protocol, draft 3. Existing execution identities remain intact.
CREATE TABLE IF NOT EXISTS ma.protocol_session (
    scope_key VARCHAR(64) PRIMARY KEY,
    sequence BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'idle'
);
CREATE TABLE IF NOT EXISTS ma.protocol_request (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    request_key VARCHAR(64) NOT NULL,
    digest TEXT NOT NULL,
    receipt TEXT NOT NULL,
    PRIMARY KEY(scope_key,request_key)
);
CREATE TABLE IF NOT EXISTS ma.protocol_link (
    scope_key VARCHAR(64) NOT NULL,
    event_id VARCHAR(256) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    batch_index INT NOT NULL,
    turn_id VARCHAR(256),
    input_id VARCHAR(256),
    intent VARCHAR(32) NOT NULL,
    message_id VARCHAR(1024),
    admitted_at BIGINT NOT NULL,
    processed_at BIGINT,
    PRIMARY KEY(scope_key,event_id)
);
CREATE INDEX IF NOT EXISTS ma_protocol_turn_link ON ma.protocol_link(scope_key,turn_id,input_id);
CREATE TABLE IF NOT EXISTS ma.protocol_command_event (
    scope_key VARCHAR(64) NOT NULL,
    event_id VARCHAR(256) NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    processed_at BIGINT,
    payload TEXT NOT NULL,
    native_key VARCHAR(1024),
    PRIMARY KEY(scope_key,sequence)
);
CREATE INDEX IF NOT EXISTS ma_protocol_command_event_identity ON ma.protocol_command_event(scope_key,event_id,sequence);
CREATE INDEX IF NOT EXISTS ma_protocol_command_event_native ON ma.protocol_command_event(scope_key,native_key);
CREATE TABLE IF NOT EXISTS ma.protocol_output (
    scope_key VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    event_ids TEXT NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(scope_key,turn_id)
);
CREATE TABLE IF NOT EXISTS ma.protocol_creation (
    request_key VARCHAR(64) PRIMARY KEY,
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    digest TEXT NOT NULL,
    receipt TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS ma.protocol_mcp_catalog (
    scope_key VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    catalog_id VARCHAR(256) NOT NULL,
    PRIMARY KEY(scope_key,turn_id,catalog_id)
);
