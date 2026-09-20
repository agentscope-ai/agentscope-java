-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_agent_metrics_time ON agent_metrics (tenant, agent_id, namespace, recorded_at DESC);
