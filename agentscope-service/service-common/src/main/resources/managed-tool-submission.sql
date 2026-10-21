-- MQ-first tool proposals are inert until the task/checkpoint transaction authorizes them.
ALTER TABLE ma.tool_command_outbox ADD COLUMN IF NOT EXISTS authorized BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX IF NOT EXISTS ma_tool_submission_pending ON ma.tool_command_outbox(scope_key,authorized);
