-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_tasks_orphan ON dp_tasks (last_updated_at) WHERE NOT terminal;
