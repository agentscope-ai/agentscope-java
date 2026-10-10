-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_agent_tasks_queue ON agent_tasks (tenant, namespace, agent_id, priority DESC, created_at) WHERE status = 'queued';
