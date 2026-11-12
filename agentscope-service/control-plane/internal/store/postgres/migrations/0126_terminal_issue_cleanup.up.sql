ALTER TABLE orchestration_runs ADD COLUMN terminal_cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE orchestration_runs SET terminal_cleanup_pending=TRUE
 WHERE state='failed' AND parent_run_id IS NULL AND trigger_type<>'review_comment';
CREATE INDEX orchestration_runs_reconcile_cursor ON orchestration_runs(created_at,id)
 WHERE state NOT IN ('succeeded','partial_succeeded','failed','cancelled') OR terminal_cleanup_pending;
CREATE INDEX issues_child_cleanup_cursor ON issues(parent_issue_id,id) WHERE archived_at IS NULL;
