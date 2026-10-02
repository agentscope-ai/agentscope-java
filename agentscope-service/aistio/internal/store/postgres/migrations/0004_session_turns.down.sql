-- +migrate Down
DROP INDEX IF EXISTS idx_session_turns_running;
DROP INDEX IF EXISTS idx_session_turns_session;
DROP TABLE IF EXISTS session_turns;
