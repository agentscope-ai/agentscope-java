-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_session_commands_time ON session_commands (requested_at DESC);
