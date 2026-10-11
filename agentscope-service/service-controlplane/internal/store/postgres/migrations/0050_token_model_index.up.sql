-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_token_model ON token_usage_metrics (tenant, model, recorded_at DESC);
