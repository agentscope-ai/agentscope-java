-- +migrate Up

CREATE UNIQUE INDEX IF NOT EXISTS idx_team_members_agent
    ON team_members(team_id, agent_id)
    WHERE archived_at IS NULL;
