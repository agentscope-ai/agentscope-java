-- Independent Hands execution facts and durable tool command/notification delivery.
CREATE TABLE IF NOT EXISTS ma.tool_command_outbox (
 command_id VARCHAR(256) PRIMARY KEY, scope_key VARCHAR(64) NOT NULL,
 task_id VARCHAR(256) NOT NULL, kind VARCHAR(32) NOT NULL, payload TEXT NOT NULL,
 next_attempt_at BIGINT NOT NULL DEFAULT 0, broker_id VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS ma_tool_commands_pending ON ma.tool_command_outbox(broker_id,next_attempt_at);
CREATE TABLE IF NOT EXISTS ma.hands_execution (
 owner_incarnation VARCHAR(64), execution_deadline BIGINT,
 scope_key VARCHAR(64) NOT NULL, tenant VARCHAR(1024) NOT NULL,
 namespace VARCHAR(1024) NOT NULL, session_id VARCHAR(1024) NOT NULL,
 task_id VARCHAR(256) NOT NULL, turn_id VARCHAR(256) NOT NULL,
 tool_call_id VARCHAR(256) NOT NULL, execution_id VARCHAR(256) NOT NULL,
 tool_name VARCHAR(1024) NOT NULL, arguments TEXT NOT NULL,
 checkpoint_sequence BIGINT NOT NULL, configuration TEXT, command_payload TEXT,
 status VARCHAR(32) NOT NULL DEFAULT 'ready', worker_token VARCHAR(256),
 owner_generation BIGINT NOT NULL DEFAULT 0, process_handle TEXT, result TEXT,
 last_dispatched_at BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(scope_key,task_id)
);
CREATE TABLE IF NOT EXISTS ma.hands_control (
 scope_key VARCHAR(64) NOT NULL, task_id VARCHAR(256) NOT NULL,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(scope_key,task_id)
);
CREATE TABLE IF NOT EXISTS ma.hands_tool_result_outbox (
 scope_key VARCHAR(64) NOT NULL, task_id VARCHAR(256) NOT NULL,
 execution_id VARCHAR(256) NOT NULL, result_hash VARCHAR(64) NOT NULL,
 payload TEXT NOT NULL, next_attempt_at BIGINT NOT NULL DEFAULT 0, committed_at BIGINT,
 PRIMARY KEY(scope_key,task_id),
 FOREIGN KEY(scope_key,task_id) REFERENCES ma.hands_execution(scope_key,task_id)
);
CREATE TABLE IF NOT EXISTS ma.hands_tool_stream (
 scope_key VARCHAR(64) NOT NULL, task_id VARCHAR(256) NOT NULL,
 execution_id VARCHAR(256) NOT NULL, mode VARCHAR(32) NOT NULL,
 source_sequence BIGINT NOT NULL DEFAULT 0, chunk_sequence BIGINT NOT NULL DEFAULT 0,
 output_offset BIGINT NOT NULL DEFAULT 0, capture_complete BOOLEAN NOT NULL DEFAULT FALSE,
 has_gap BOOLEAN NOT NULL DEFAULT FALSE, final_sequence BIGINT,
 consumed_sequence BIGINT NOT NULL DEFAULT 0, consumed_chunk BIGINT NOT NULL DEFAULT 0,
 consumed_offset BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(scope_key,task_id),
 FOREIGN KEY(scope_key,task_id) REFERENCES ma.hands_execution(scope_key,task_id)
);
CREATE TABLE IF NOT EXISTS ma.hands_tool_event (
 scope_key VARCHAR(64) NOT NULL, task_id VARCHAR(256) NOT NULL,
 execution_id VARCHAR(256) NOT NULL, source_sequence BIGINT NOT NULL,
 event_id VARCHAR(256) NOT NULL, content_hash VARCHAR(64) NOT NULL, payload TEXT NOT NULL,
 next_attempt_at BIGINT NOT NULL DEFAULT 0, broker_at BIGINT, received_at BIGINT, applied_at BIGINT,
 PRIMARY KEY(scope_key,task_id,source_sequence), UNIQUE(scope_key,event_id),
 FOREIGN KEY(scope_key,task_id) REFERENCES ma.hands_tool_stream(scope_key,task_id)
);
CREATE INDEX IF NOT EXISTS ma_hands_event_publish ON ma.hands_tool_event(broker_at,next_attempt_at);
CREATE TABLE IF NOT EXISTS ma.hands_resume_outbox (
 notification_id VARCHAR(256) PRIMARY KEY, scope_key VARCHAR(64) NOT NULL,
 task_id VARCHAR(256) NOT NULL, payload TEXT NOT NULL, broker_id VARCHAR(1024)
);
CREATE TABLE IF NOT EXISTS ma.tool_resume_inbox (
 notification_id VARCHAR(256) PRIMARY KEY, scope_key VARCHAR(64) NOT NULL,
 task_id VARCHAR(256) NOT NULL, payload TEXT NOT NULL, suspension_id VARCHAR(256)
);
CREATE TABLE IF NOT EXISTS ma.mcp_catalog (
 scope_key VARCHAR(64) NOT NULL, catalog_id VARCHAR(256) NOT NULL,
 payload TEXT NOT NULL, PRIMARY KEY(scope_key,catalog_id)
);

CREATE TABLE IF NOT EXISTS ma.admin_tool_operation (
 scope_key VARCHAR(64) NOT NULL, task_id VARCHAR(256) NOT NULL,
 payload TEXT NOT NULL, result TEXT, PRIMARY KEY(scope_key,task_id)
);
CREATE TABLE IF NOT EXISTS ma.hands_sandbox (
 scope_key VARCHAR(64) PRIMARY KEY, status VARCHAR(32) NOT NULL,
 creation_token VARCHAR(256) NOT NULL, encrypted_state TEXT, borrowed_identity VARCHAR(64) UNIQUE
);


CREATE TABLE IF NOT EXISTS ma.hands_execution_event (
 scope_key VARCHAR(64) NOT NULL, task_id VARCHAR(256) NOT NULL,
 owner_generation BIGINT NOT NULL, phase VARCHAR(64) NOT NULL,
 recorded_at BIGINT NOT NULL, detail TEXT NOT NULL,
 PRIMARY KEY(scope_key,task_id,owner_generation,phase)
);

CREATE INDEX IF NOT EXISTS ma_hands_pending ON ma.hands_execution(status,last_dispatched_at,scope_key,task_id);
CREATE INDEX IF NOT EXISTS ma_hands_result_pending ON ma.hands_tool_result_outbox(committed_at,next_attempt_at);
CREATE INDEX IF NOT EXISTS ma_hands_resume_pending ON ma.hands_resume_outbox(broker_id,notification_id);

CREATE INDEX IF NOT EXISTS ma_model_source_turn ON ma.model_call(source_scope,turn_id);
