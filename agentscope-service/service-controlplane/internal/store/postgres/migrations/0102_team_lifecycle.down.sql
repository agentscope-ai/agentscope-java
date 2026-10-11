-- +migrate Down

DROP INDEX IF EXISTS idx_teams_status;
ALTER TABLE teams DROP COLUMN IF EXISTS status;
ALTER TABLE teams DROP COLUMN IF EXISTS instructions;
