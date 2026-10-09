-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_issue_subscribers_unique ON issue_subscribers (issue_id, subscriber_type, subscriber_ref);
