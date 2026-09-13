-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_automations_due ON automations (next_run_at, id) WHERE enabled AND archived_at IS NULL;
