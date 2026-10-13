-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_issues_scope_updated ON issues (tenant, namespace, updated_at DESC, id);
