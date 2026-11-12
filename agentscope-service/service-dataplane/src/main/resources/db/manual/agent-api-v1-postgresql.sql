-- Manual PostgreSQL migration for the Agent API tables. Review before deployment.
-- Applies to the Data Plane database; do not run against the control-plane sessions DB.
BEGIN;
CREATE TABLE IF NOT EXISTS builder_session_turn_command (
    id varchar(64) PRIMARY KEY,
    session_id varchar(64) NOT NULL,
    user_id varchar(128) NOT NULL,
    request_json text NOT NULL,
    continuation_json text,
    continuation_id varchar(64),
    status varchar(32) NOT NULL,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    lease_until bigint NOT NULL,
    worker_id varchar(64),
    error_code varchar(128),
    native_start_seq bigint NOT NULL DEFAULT -1,
    admission_seq bigint NOT NULL,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_turn_command_status ON builder_session_turn_command(status,created_at);
CREATE INDEX IF NOT EXISTS ix_turn_command_session_order ON builder_session_turn_command(session_id,admission_seq);
CREATE TABLE IF NOT EXISTS builder_session_action_command (
    id varchar(64) PRIMARY KEY,
    turn_id varchar(64) NOT NULL,
    request_json text NOT NULL,
    delivery_status varchar(24) NOT NULL DEFAULT 'applied',
    created_at bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_action_command_delivery ON builder_session_action_command(delivery_status,created_at);
CREATE INDEX IF NOT EXISTS ix_action_command_turn ON builder_session_action_command(turn_id,created_at);
CREATE TABLE IF NOT EXISTS builder_session_export_source (
    session_id varchar(64) PRIMARY KEY,
    user_id varchar(128) NOT NULL,
    agent_id varchar(512) NOT NULL,
    parent_session_id varchar(64)
);
ALTER TABLE builder_session_export_source ADD COLUMN IF NOT EXISTS parent_session_id varchar(64);
COMMIT;
