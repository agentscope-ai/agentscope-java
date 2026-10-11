-- Execution records now belong to application Sessions. Keep the physical table
-- names so accepted work from earlier releases remains recoverable.
ALTER TABLE endpoint_invocations DROP CONSTRAINT IF EXISTS endpoint_invocations_endpoint_id_fkey;
ALTER TABLE endpoint_conversations DROP CONSTRAINT IF EXISTS endpoint_conversations_endpoint_id_fkey;
ALTER TABLE endpoint_invocations DROP CONSTRAINT IF EXISTS endpoint_invocations_credential_id_fkey;
