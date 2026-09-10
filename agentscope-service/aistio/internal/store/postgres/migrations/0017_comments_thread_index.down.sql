-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_comments_thread_time;
