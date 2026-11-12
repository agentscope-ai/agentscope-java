-- Additive Runtime command publication ownership; unrelated to Worker execution leases.
ALTER TABLE ma.tool_command_outbox ADD COLUMN IF NOT EXISTS publisher_token VARCHAR(256);
ALTER TABLE ma.tool_command_outbox ADD COLUMN IF NOT EXISTS publisher_lease_until BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ma.tool_command_outbox ADD COLUMN IF NOT EXISTS publish_attempt BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ma_tool_command_publication_ready ON ma.tool_command_outbox(broker_id,publisher_lease_until,next_attempt_at);
