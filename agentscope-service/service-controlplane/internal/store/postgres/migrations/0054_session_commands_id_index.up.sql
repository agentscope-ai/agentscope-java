-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_session_commands_command_id ON session_commands (command_id) WHERE command_id IS NOT NULL AND command_id != '';
