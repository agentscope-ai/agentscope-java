-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_session_turns_session ON session_turns (session_fk, turn_index DESC);
