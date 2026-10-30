-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_comment_routes_unique ON comment_routes (comment_id, comment_version, target_type, target_ref);
