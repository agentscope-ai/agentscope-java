-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_issues_search ON issues USING GIN (to_tsvector('simple', COALESCE(identifier, '') || ' ' || title || ' ' || COALESCE(description, ''))) WHERE archived_at IS NULL;
