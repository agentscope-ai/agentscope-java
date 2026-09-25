-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_events_session_time ON session_events (session_fk, occurred_at);
