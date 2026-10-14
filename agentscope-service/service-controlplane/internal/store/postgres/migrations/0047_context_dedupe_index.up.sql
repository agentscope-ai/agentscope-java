-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_ctx_dedup ON context_snapshots (session_fk, context_hash);
