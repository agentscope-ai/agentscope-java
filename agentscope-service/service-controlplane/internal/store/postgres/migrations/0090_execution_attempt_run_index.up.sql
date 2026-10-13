-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY execution_attempt_run_idx ON execution_attempts (run_id, node_id, created_at);
