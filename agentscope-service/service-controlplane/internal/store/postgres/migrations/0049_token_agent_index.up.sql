-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_token_agent_time ON token_usage_metrics (tenant, agent_id, namespace, recorded_at DESC);
