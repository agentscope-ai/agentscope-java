-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_comment_mentions_unique ON comment_mentions (comment_id, target_type, target_ref);
