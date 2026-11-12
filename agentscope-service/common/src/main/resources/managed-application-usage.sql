-- Additive migration: billing facts remain in ma.model_call; application totals are eventual.
CREATE TABLE IF NOT EXISTS ma.application_usage_delta (
    usage_id VARCHAR(64) PRIMARY KEY,
    quota_key VARCHAR(64) NOT NULL REFERENCES ma.admission_quota(quota_key),
    tokens_used BIGINT NOT NULL CHECK(tokens_used > 0),
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS ma_application_usage_pending ON ma.application_usage_delta(created_at,usage_id);
