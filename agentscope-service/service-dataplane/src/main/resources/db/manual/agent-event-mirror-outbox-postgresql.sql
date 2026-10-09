-- Data Plane database only. Apply after agent-api-v1-postgresql.sql.
-- Existing manual migrations are unnumbered; this adds delivery state without rewriting history.
BEGIN;
CREATE TABLE IF NOT EXISTS builder_session_event_outbox (
    event_id varchar(128) PRIMARY KEY,
    session_id varchar(64) NOT NULL,
    attempt_id varchar(64) NOT NULL,
    event_seq bigint NOT NULL,
    report_json text NOT NULL,
    created_at bigint NOT NULL,
    delivered_at bigint NOT NULL DEFAULT 0,
    next_attempt_at bigint NOT NULL DEFAULT 0,
    lease_until bigint NOT NULL DEFAULT 0,
    worker_id varchar(64),
    attempts integer NOT NULL DEFAULT 0,
    last_error varchar(1024),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_event_outbox_delivery ON builder_session_event_outbox(delivered_at,next_attempt_at,lease_until);
CREATE INDEX IF NOT EXISTS ix_event_outbox_session ON builder_session_event_outbox(session_id,event_seq);
CREATE INDEX IF NOT EXISTS ix_event_outbox_attempt ON builder_session_event_outbox(session_id,attempt_id,delivered_at);
CREATE TABLE IF NOT EXISTS builder_session_execution_scope (
    admission_id varchar(128) PRIMARY KEY,
    session_id varchar(64) NOT NULL,
    attempt_id varchar(64),
    run_id varchar(64),
    scope_json text NOT NULL,
    lease_until bigint NOT NULL,
    finished boolean NOT NULL DEFAULT false,
    native_start_seq bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_execution_scope_run ON builder_session_execution_scope(run_id);
CREATE INDEX IF NOT EXISTS ix_execution_scope_attempt ON builder_session_execution_scope(session_id,attempt_id);
CREATE TABLE IF NOT EXISTS builder_session_event_fence (
    attempt_id varchar(64) PRIMARY KEY,
    session_id varchar(64) NOT NULL,
    closing boolean NOT NULL DEFAULT false,
    sealed boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0
);
COMMIT;
