-- +migrate Up
-- Plan approval workflow on team members: worker submits a plan, lead approves/rejects.

ALTER TABLE team_members ADD COLUMN IF NOT EXISTS plan_text TEXT;
ALTER TABLE team_members ADD COLUMN IF NOT EXISTS plan_status TEXT;

CREATE INDEX IF NOT EXISTS idx_team_members_plan_status
    ON team_members(namespace, team_name, plan_status)
    WHERE plan_status IS NOT NULL;
