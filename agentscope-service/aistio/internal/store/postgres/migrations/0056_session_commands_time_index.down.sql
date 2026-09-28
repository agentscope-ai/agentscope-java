-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_session_commands_time;
