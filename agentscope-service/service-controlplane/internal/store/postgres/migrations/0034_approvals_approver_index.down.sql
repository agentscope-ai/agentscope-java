-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_approvals_approver;
