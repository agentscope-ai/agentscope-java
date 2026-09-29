-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_team_snapshot_idx;
