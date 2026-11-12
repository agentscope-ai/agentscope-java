DROP INDEX IF EXISTS orchestration_runs_portal_reconcile_cursor;
ALTER TABLE orchestration_runs DROP COLUMN portal_owned;
DROP FUNCTION IF EXISTS portal_issue_owned(UUID);
