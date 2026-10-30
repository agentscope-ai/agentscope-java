-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_agent_tasks_pending_unique ON agent_tasks (issue_id, agent_id, COALESCE(team_role, '')) WHERE status = 'queued';
