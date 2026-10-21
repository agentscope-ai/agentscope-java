CREATE INDEX IF NOT EXISTS orchestration_runs_active_cursor
 ON orchestration_runs(created_at,id)
 WHERE state NOT IN ('succeeded','partial_succeeded','failed','cancelled');
