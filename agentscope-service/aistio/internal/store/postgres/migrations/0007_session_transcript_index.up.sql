-- +migrate Up
-- Narrow per-session transcript index for Operate read-path aggregates.
-- Write-time maintenance: DP Level-1 snapshot fields (messageCount / tokenUsage)
-- via upsertObservedSession / dataplane poller — not recomputed from events.

CREATE TABLE IF NOT EXISTS session_transcript_index (
    session_fk         UUID PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    entry_count        INT NOT NULL DEFAULT 0,
    prompt_tokens      BIGINT NOT NULL DEFAULT 0,
    completion_tokens  BIGINT NOT NULL DEFAULT 0,
    object_prefix      TEXT,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_transcript_index_updated
    ON session_transcript_index(updated_at DESC);
