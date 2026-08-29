-- +migrate Down
DROP TABLE IF EXISTS agent_metrics;
DROP TABLE IF EXISTS team_task_history;
DROP TABLE IF EXISTS team_tasks;
DROP TABLE IF EXISTS team_messages;
DROP TABLE IF EXISTS token_usage_metrics;
DROP TABLE IF EXISTS context_snapshots;
DROP TABLE IF EXISTS session_events;
DROP TABLE IF EXISTS session_snapshots;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS schema_migrations;
