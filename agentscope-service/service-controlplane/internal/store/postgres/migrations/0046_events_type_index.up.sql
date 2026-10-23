-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_events_type ON session_events (session_fk, event_type);
