-- Direct Session Store metadata. Content belongs to a configured immutable object backend.
CREATE TABLE IF NOT EXISTS ma.runtime_commit (
 scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key), commit_id VARCHAR(256) NOT NULL,
 content_hash VARCHAR(64) NOT NULL, receipt TEXT NOT NULL,
 turn_id VARCHAR(256), epoch BIGINT,
 first_sequence BIGINT, last_sequence BIGINT, recovery_ref TEXT, checkpoint_ref TEXT, projection_version BIGINT NOT NULL DEFAULT 0,
 public_commit_version BIGINT, projection_manifest TEXT,
 publication_state VARCHAR(16) NOT NULL DEFAULT 'sent', next_attempt_at BIGINT NOT NULL DEFAULT 0,
 publisher_token VARCHAR(64), published_at BIGINT, attempt_count INTEGER NOT NULL DEFAULT 0,
 UNIQUE(scope_key,public_commit_version),
 PRIMARY KEY(scope_key,commit_id)
);
CREATE INDEX IF NOT EXISTS ma_runtime_commit_after ON ma.runtime_commit(scope_key,last_sequence);
CREATE INDEX IF NOT EXISTS ma_runtime_commit_turn ON ma.runtime_commit(scope_key,turn_id,first_sequence);
CREATE TABLE IF NOT EXISTS ma.projection_tool_identity (
 scope_key VARCHAR(64) NOT NULL, source_id VARCHAR(256) NOT NULL, semantic_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(scope_key,source_id)
);
CREATE TABLE IF NOT EXISTS ma.session_store_usage (
 scope_key VARCHAR(64) NOT NULL, model_call_id VARCHAR(256) NOT NULL, data TEXT NOT NULL,
 PRIMARY KEY(scope_key,model_call_id)
);
CREATE TABLE IF NOT EXISTS ma.session_store_output (
 scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key), turn_id VARCHAR(256) NOT NULL,
 sealed BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(scope_key,turn_id)
);
CREATE TABLE IF NOT EXISTS ma.session_store_output_event (
 scope_key VARCHAR(64) NOT NULL, turn_id VARCHAR(256) NOT NULL, event_id VARCHAR(256) NOT NULL,
 ordinal BIGINT NOT NULL, payload TEXT NOT NULL, finalized BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(scope_key,turn_id,event_id), UNIQUE(scope_key,turn_id,ordinal)
);
CREATE TABLE IF NOT EXISTS ma.session_projection_usage (
 scope_key VARCHAR(64) NOT NULL, turn_id VARCHAR(256) NOT NULL,
 usage_summary TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(scope_key,turn_id)
);

-- Model outcomes are private objects; these rows are their committed execution identities.
CREATE TABLE IF NOT EXISTS ma.model_call_result (
 scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key), model_call_id VARCHAR(256) NOT NULL,
 turn_id VARCHAR(256) NOT NULL, request_sequence BIGINT NOT NULL, checkpoint_sequence BIGINT NOT NULL,
 completion_sequence BIGINT, status VARCHAR(32), request_hash VARCHAR(64), result_ref TEXT, replay_hash VARCHAR(64),
 PRIMARY KEY(scope_key,model_call_id)
);

CREATE INDEX IF NOT EXISTS ma_model_result_replay ON ma.model_call_result(scope_key,turn_id,replay_hash,completion_sequence);

CREATE TABLE IF NOT EXISTS ma.finalization_frame (
 scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key), turn_id VARCHAR(256) NOT NULL,
 frame_id VARCHAR(256) NOT NULL, start_sequence BIGINT NOT NULL, capture_ref TEXT NOT NULL,
 completion_sequence BIGINT, PRIMARY KEY(scope_key,frame_id)
);
CREATE INDEX IF NOT EXISTS finalization_frame_turn_idx ON ma.finalization_frame(scope_key,turn_id,start_sequence);
CREATE TABLE IF NOT EXISTS ma.finalization_model (
 scope_key VARCHAR(64) NOT NULL, turn_id VARCHAR(256) NOT NULL, frame_id VARCHAR(256) NOT NULL,
 slot INTEGER NOT NULL, model_call_id VARCHAR(256) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 sequence BIGINT NOT NULL, PRIMARY KEY(scope_key,frame_id,slot,model_call_id), UNIQUE(scope_key,sequence)
);

CREATE TABLE IF NOT EXISTS ma.finalization_io (
 scope_key VARCHAR(64) NOT NULL, turn_id VARCHAR(256) NOT NULL, frame_id VARCHAR(256) NOT NULL,
 slot INTEGER NOT NULL, request_hash VARCHAR(64) NOT NULL, read_ref TEXT, write_result TEXT,
 PRIMARY KEY(scope_key,frame_id,slot),
 FOREIGN KEY(scope_key,frame_id) REFERENCES ma.finalization_frame(scope_key,frame_id)
);
