-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_data_planes_agent;
