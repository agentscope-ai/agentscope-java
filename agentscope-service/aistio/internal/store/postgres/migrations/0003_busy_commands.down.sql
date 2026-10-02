-- +migrate Down
DROP TABLE IF EXISTS session_commands;
ALTER TABLE sessions DROP COLUMN IF EXISTS busy;
