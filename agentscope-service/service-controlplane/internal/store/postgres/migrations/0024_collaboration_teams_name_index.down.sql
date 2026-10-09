-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_teams_name;
