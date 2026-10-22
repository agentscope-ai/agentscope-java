-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_event_dedupe_idx ON orchestration_run_events (run_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
