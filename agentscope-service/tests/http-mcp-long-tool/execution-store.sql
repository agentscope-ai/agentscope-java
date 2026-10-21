-- Fixture-owned schema, independent of the AgentScope Service platform tables.
CREATE SCHEMA IF NOT EXISTS mcp_fixture;
CREATE TABLE IF NOT EXISTS mcp_fixture.execution (
    execution_id TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    marker TEXT NOT NULL,
    seconds INTEGER NOT NULL CHECK (seconds BETWEEN 1 AND 180),
    call_id TEXT NOT NULL UNIQUE,
    pod TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    state TEXT NOT NULL DEFAULT 'running' CHECK (state IN ('running','succeeded')),
    result JSONB,
    CHECK ((state='succeeded') = (result IS NOT NULL))
);
