-- +migrate Up

ALTER TABLE teams ADD COLUMN IF NOT EXISTS instructions TEXT;
ALTER TABLE teams ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'active';

CREATE INDEX IF NOT EXISTS idx_teams_status
    ON teams(tenant, namespace, status)
    WHERE archived_at IS NULL;
