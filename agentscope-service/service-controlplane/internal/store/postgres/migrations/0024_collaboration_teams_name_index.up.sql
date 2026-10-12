-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_teams_name ON teams (tenant, namespace, name) WHERE archived_at IS NULL;
