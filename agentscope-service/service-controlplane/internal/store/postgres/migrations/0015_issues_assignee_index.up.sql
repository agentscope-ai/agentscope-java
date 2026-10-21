-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_issues_assignee ON issues (tenant, namespace, assignee_type, assignee_ref, status, updated_at DESC);
