-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_sessions_phase ON sessions (tenant, phase) WHERE phase != 'terminated';
