-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_async_stale ON dp_async_tools (tenant, session_id, status, created_at);
