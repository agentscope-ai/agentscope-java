-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_tasks_delivery ON dp_tasks (tenant, parent_agent_id, parent_session_id) WHERE delivered_at IS NULL;
