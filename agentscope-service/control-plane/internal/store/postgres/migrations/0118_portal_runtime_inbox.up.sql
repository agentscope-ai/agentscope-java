CREATE TABLE orchestration_portal_sessions (
 id VARCHAR(256) PRIMARY KEY,
 command_id VARCHAR(256) NOT NULL UNIQUE,
 digest VARCHAR(64) NOT NULL,
 payload BYTEA NOT NULL,
 last_sequence BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE orchestration_portal_turns (
 id VARCHAR(256) PRIMARY KEY,
 session_id VARCHAR(256) NOT NULL REFERENCES orchestration_portal_sessions(id),
 turn_id VARCHAR(256) NOT NULL,
 admission_sequence BIGINT NOT NULL CHECK(admission_sequence>0),
 digest VARCHAR(64) NOT NULL,
 payload BYTEA NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'queued',
 next_attempt TIMESTAMPTZ NOT NULL DEFAULT now(),
 claim_token VARCHAR(64),
 claim_until TIMESTAMPTZ,
 UNIQUE(session_id,turn_id),
 UNIQUE(session_id,admission_sequence)
);
CREATE INDEX orchestration_portal_turns_due ON orchestration_portal_turns(next_attempt,id)
 WHERE status NOT IN ('completed','failed','interrupted','cancelled');
CREATE INDEX orchestration_portal_turns_predecessor ON orchestration_portal_turns(session_id,admission_sequence)
 WHERE status NOT IN ('completed','failed','interrupted','cancelled');
CREATE TABLE orchestration_portal_facts (
 session_id VARCHAR(256) NOT NULL REFERENCES orchestration_portal_sessions(id),
 sequence BIGINT NOT NULL CHECK(sequence>0),
 event_id VARCHAR(256) NOT NULL,
 digest VARCHAR(64) NOT NULL,
 payload BYTEA NOT NULL,
 delivered BOOLEAN NOT NULL DEFAULT FALSE,
 claim_token VARCHAR(64),
 claim_until TIMESTAMPTZ,
 PRIMARY KEY(session_id,sequence),
 UNIQUE(session_id,event_id)
);
CREATE INDEX orchestration_portal_facts_due ON orchestration_portal_facts(session_id,sequence)
 WHERE NOT delivered;
