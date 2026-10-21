CREATE TABLE orchestration_portal_interactions (
 id VARCHAR(256) PRIMARY KEY,
 session_id VARCHAR(256) NOT NULL REFERENCES orchestration_portal_sessions(id),
 payload BYTEA NOT NULL
);
