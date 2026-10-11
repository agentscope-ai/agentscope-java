-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_sessions_agent_task ON sessions (agent_task_id) WHERE agent_task_id IS NOT NULL;
