-- +migrate Down
DROP INDEX IF EXISTS idx_team_members_plan_status;
ALTER TABLE team_members DROP COLUMN IF EXISTS plan_status;
ALTER TABLE team_members DROP COLUMN IF EXISTS plan_text;
