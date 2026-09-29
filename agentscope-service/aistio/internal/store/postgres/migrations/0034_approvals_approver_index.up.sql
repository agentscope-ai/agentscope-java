-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_approvals_approver ON approvals (tenant, namespace, approver_ref, status, created_at DESC);
