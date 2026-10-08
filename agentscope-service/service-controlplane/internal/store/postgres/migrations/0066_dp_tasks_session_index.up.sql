-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_tasks_session ON dp_tasks (tenant, parent_agent_id, parent_session_id, status);
