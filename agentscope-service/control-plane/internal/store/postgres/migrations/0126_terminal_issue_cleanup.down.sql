DROP INDEX IF EXISTS issues_child_cleanup_cursor;
DROP INDEX IF EXISTS orchestration_runs_reconcile_cursor;
ALTER TABLE orchestration_runs DROP COLUMN terminal_cleanup_pending;
