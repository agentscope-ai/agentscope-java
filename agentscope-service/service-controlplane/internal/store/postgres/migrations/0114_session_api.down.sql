-- Session-owned executions cannot be represented by the old Endpoint schema.
-- Fail a rollback with such records instead of deleting user data.
ALTER TABLE endpoint_invocations ADD CONSTRAINT endpoint_invocations_endpoint_id_fkey FOREIGN KEY(endpoint_id) REFERENCES endpoints(id);
ALTER TABLE endpoint_conversations ADD CONSTRAINT endpoint_conversations_endpoint_id_fkey FOREIGN KEY(endpoint_id) REFERENCES endpoints(id);
ALTER TABLE endpoint_invocations ADD CONSTRAINT endpoint_invocations_credential_id_fkey FOREIGN KEY(credential_id) REFERENCES endpoint_credentials(id);
