DROP INDEX IF EXISTS idx_endpoint_invocations_conversation_active;
ALTER TABLE endpoint_invocations DROP COLUMN consumed_tokens;
DROP INDEX IF EXISTS endpoint_invocations_pending_idx;
CREATE INDEX endpoint_invocations_pending_idx ON endpoint_invocations(created_at,id) WHERE status NOT IN ('completed','failed','cancelled','timed_out');
ALTER TABLE endpoint_invocations DROP COLUMN next_poll_at, DROP COLUMN actor, DROP COLUMN credential_id, DROP COLUMN application_id;
ALTER TABLE endpoint_credentials DROP COLUMN application_id;
ALTER TABLE endpoints DROP COLUMN result_mapping;
DROP TABLE applications;
