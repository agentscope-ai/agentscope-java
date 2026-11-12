DROP INDEX IF EXISTS endpoint_invocations_pending_idx;
ALTER TABLE endpoint_conversations DROP COLUMN contract, DROP COLUMN release_id;
ALTER TABLE endpoint_invocations DROP COLUMN contract, DROP COLUMN release_id;
ALTER TABLE endpoint_releases DROP COLUMN contract;
