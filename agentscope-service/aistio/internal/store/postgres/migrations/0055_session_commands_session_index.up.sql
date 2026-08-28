-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_session_commands_session ON session_commands (session_fk, requested_at DESC);
