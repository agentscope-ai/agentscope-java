-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_run_idempotency_idx ON orchestration_runs (tenant, namespace, idempotency_key) WHERE idempotency_key IS NOT NULL;
