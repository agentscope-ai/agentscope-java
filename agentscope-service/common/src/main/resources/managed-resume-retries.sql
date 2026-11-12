-- Retry metadata on the existing outbox; no payload inbox or SQL execution scheduler.
ALTER TABLE ma.turn_resume ADD COLUMN IF NOT EXISTS retry_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ma.turn_resume ADD COLUMN IF NOT EXISTS publication_version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ma_resume_retry ON ma.turn_resume(delivery_status,retry_at,scope_key);
