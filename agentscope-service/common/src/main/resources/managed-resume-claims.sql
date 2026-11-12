-- Resume receipts may be ACKed after a durable execution claim on the existing Turn.
ALTER TABLE ma.turn ADD COLUMN IF NOT EXISTS resume_claimed_at BIGINT;
CREATE INDEX IF NOT EXISTS ma_turn_resume_claim ON ma.turn(resume_claimed_at,scope_key,turn_id);
