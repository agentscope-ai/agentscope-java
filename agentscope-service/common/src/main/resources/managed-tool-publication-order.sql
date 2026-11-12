-- Fair, eligible-time ordering for independently published committed commands.
ALTER TABLE ma.tool_command_outbox ADD COLUMN IF NOT EXISTS created_at BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ma_tool_publication_order ON ma.tool_command_outbox
 (broker_id,authorized,next_attempt_at,created_at,command_id,publisher_lease_until);
