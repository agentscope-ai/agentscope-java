-- Data-plane self-registration registry (standalone-friendly discovery).
CREATE TABLE IF NOT EXISTS data_planes (
    instance_id     TEXT PRIMARY KEY,
    agent_name      TEXT NOT NULL,
    namespace       TEXT NOT NULL DEFAULT 'default',
    base_url        TEXT NOT NULL,
    runtime         TEXT,
    framework       TEXT,
    contract_level  INT NOT NULL DEFAULT 1,
    capabilities    JSONB,
    healthy         BOOLEAN NOT NULL DEFAULT true,
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    source          TEXT NOT NULL DEFAULT 'self-register',
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
