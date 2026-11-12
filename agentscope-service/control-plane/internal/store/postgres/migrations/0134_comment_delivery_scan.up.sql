-- Select the latest retained delivery for this Run's child Tasks.
CREATE INDEX IF NOT EXISTS idx_comments_task_delivery ON comments(issue_id,source_task_id,created_at,id) WHERE type='result' AND deleted_at IS NULL;
