-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_issues_due ON issues (due_at, id) WHERE due_at IS NOT NULL AND archived_at IS NULL;
