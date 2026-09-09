-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_session_turns_identity ON session_turns (session_fk, turn_index);
