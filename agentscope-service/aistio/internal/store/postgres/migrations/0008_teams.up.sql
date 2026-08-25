-- +migrate Up
-- Store-backed AgentTeam authority (CRD becomes optional projection).

CREATE TABLE IF NOT EXISTS teams (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    objective       TEXT NOT NULL,
    phase           TEXT NOT NULL DEFAULT 'Pending',
    lead_ref        TEXT NOT NULL,
    lead_prompt     TEXT,
    config          JSONB,
    spec_extra      JSONB,
    started_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(namespace, name)
);

CREATE INDEX IF NOT EXISTS idx_teams_phase ON teams(namespace, phase);

CREATE TABLE IF NOT EXISTS team_members (
    id                  BIGSERIAL PRIMARY KEY,
    team_name           TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    member_name         TEXT NOT NULL,
    agent_ref           TEXT NOT NULL,
    prompt              TEXT,
    plan_approval       BOOLEAN NOT NULL DEFAULT FALSE,
    origin              TEXT NOT NULL DEFAULT 'static',
    deploy_mode         TEXT NOT NULL DEFAULT 'byo',
    managed_agent_id    TEXT,
    owner_id            TEXT,
    phase               TEXT NOT NULL DEFAULT 'Joining',
    session_id          TEXT,
    managed_session_id  TEXT,
    instance_ref        TEXT,
    current_task        TEXT,
    restart_count       INT NOT NULL DEFAULT 0,
    last_restart_at     TIMESTAMPTZ,
    last_restart_reason TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(namespace, team_name, member_name)
);

CREATE INDEX IF NOT EXISTS idx_team_members_team ON team_members(namespace, team_name);
CREATE INDEX IF NOT EXISTS idx_team_members_session ON team_members(session_id) WHERE session_id IS NOT NULL;
