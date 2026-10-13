-- +migrate Down
DROP TABLE IF EXISTS agent_metrics;
DROP TABLE IF EXISTS token_usage_metrics;
DROP TABLE IF EXISTS context_snapshots;
DROP TABLE IF EXISTS session_events;
DROP TABLE IF EXISTS session_snapshots;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS schema_migrations;
