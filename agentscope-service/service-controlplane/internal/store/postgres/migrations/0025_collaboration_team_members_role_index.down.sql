-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_team_members_role;
