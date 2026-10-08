-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_events_sequence ON session_events (session_fk, seq);
