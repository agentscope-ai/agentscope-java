-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_execution_attempts_lease ON execution_attempts (lease_expires_at) WHERE state IN ('assigned', 'preparing', 'running');
