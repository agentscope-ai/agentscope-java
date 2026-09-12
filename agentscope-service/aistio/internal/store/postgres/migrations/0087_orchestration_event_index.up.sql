-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_event_idx ON orchestration_run_events (run_id, sequence);
