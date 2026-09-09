-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_team_members_role ON team_members (team_id, role) WHERE archived_at IS NULL;
