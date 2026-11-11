-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_dp_bus_key;
