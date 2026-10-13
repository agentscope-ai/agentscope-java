-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_automation_runs_dedupe ON automation_runs (automation_id, idempotency_key);
