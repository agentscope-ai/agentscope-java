-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_token_agent_time;
