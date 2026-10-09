-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_session_turns_running ON session_turns (session_fk) WHERE status = 'running';
