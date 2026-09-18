-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY orchestration_run_issue_idx ON orchestration_runs (root_issue_id, state, created_at DESC);
