-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY orchestration_run_scope_idx ON orchestration_runs (tenant, namespace, state, created_at DESC);
