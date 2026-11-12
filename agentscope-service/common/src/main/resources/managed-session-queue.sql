-- Copyright 2024-2026 the original author or authors.
-- Licensed under the Apache License, Version 2.0.

-- Session mode is frozen at creation; legacy sessions are never implicitly migrated.
ALTER TABLE ma.session ADD COLUMN IF NOT EXISTS scheduler_mode VARCHAR(32) NOT NULL DEFAULT 'broker_fifo_v1';
ALTER TABLE ma.session ADD COLUMN IF NOT EXISTS scheduler_generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ma.session ADD COLUMN IF NOT EXISTS queue_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS ma.session_queue (
 scope_key VARCHAR(256) NOT NULL REFERENCES ma.session(scope_key),
 entry_id VARCHAR(128) NOT NULL,
 turn_id VARCHAR(128) NOT NULL,
 submission_id VARCHAR(128),
 enqueue_sequence BIGINT NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'queued',
 version BIGINT NOT NULL DEFAULT 1,
 converted_input_id VARCHAR(128),
 conversion_command_id VARCHAR(128),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(scope_key,entry_id),
 UNIQUE(scope_key,turn_id),
 UNIQUE(scope_key,enqueue_sequence),
 FOREIGN KEY(scope_key,turn_id) REFERENCES ma.turn(scope_key,turn_id)
);
CREATE INDEX IF NOT EXISTS ma_session_queue_head ON ma.session_queue(scope_key,state,enqueue_sequence);
CREATE TABLE IF NOT EXISTS ma.session_command (
 scope_key VARCHAR(256) NOT NULL REFERENCES ma.session(scope_key),
 command_id VARCHAR(128) NOT NULL,
 command_sequence BIGINT NOT NULL,
 kind VARCHAR(32) NOT NULL,
 actor TEXT NOT NULL,
 source_entry_id VARCHAR(128),
 target_turn_id VARCHAR(128),
 effect_input_id VARCHAR(128),
 status VARCHAR(32) NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY(scope_key,command_id),
 UNIQUE(scope_key,command_sequence)
);
CREATE TABLE IF NOT EXISTS ma.session_dispatch (
 scope_key VARCHAR(256) PRIMARY KEY REFERENCES ma.session(scope_key),
 requested_generation BIGINT NOT NULL DEFAULT 1,
 handled_generation BIGINT NOT NULL DEFAULT 0,
 next_check_at BIGINT NOT NULL,
 claim_token VARCHAR(128),
 claim_until BIGINT NOT NULL DEFAULT 0,
 notified_generation BIGINT NOT NULL DEFAULT 0,
 notification_pending BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS ma_session_dispatch_due ON ma.session_dispatch(next_check_at,claim_until);

CREATE INDEX IF NOT EXISTS ma_session_dispatch_notification ON ma.session_dispatch(notification_pending,next_check_at);
