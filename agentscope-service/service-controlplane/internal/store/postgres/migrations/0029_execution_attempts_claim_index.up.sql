-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_execution_attempts_claim ON execution_attempts (tenant, namespace, runtime_pool_name, created_at) WHERE state = 'queued';
