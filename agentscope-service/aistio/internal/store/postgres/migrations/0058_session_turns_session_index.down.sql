-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_session_turns_session;
