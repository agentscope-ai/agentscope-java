CREATE TABLE applications (
 id UUID PRIMARY KEY, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
 name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', owner_user_id TEXT NOT NULL,
 members JSONB NOT NULL DEFAULT '[]', status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','disabled')),
 version BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant,namespace,name)
);
ALTER TABLE endpoints ADD COLUMN result_mapping JSONB;
ALTER TABLE endpoint_credentials ADD COLUMN application_id UUID REFERENCES applications(id);
ALTER TABLE endpoint_invocations ADD COLUMN application_id UUID REFERENCES applications(id),
 ADD COLUMN credential_id UUID REFERENCES endpoint_credentials(id),
 ADD COLUMN actor JSONB NOT NULL DEFAULT '{}', ADD COLUMN next_poll_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX endpoint_credentials_application_idx ON endpoint_credentials(application_id);
CREATE INDEX endpoint_invocations_due_idx ON endpoint_invocations(next_poll_at,created_at,id);
DROP INDEX IF EXISTS endpoint_invocations_pending_idx;
CREATE INDEX endpoint_invocations_pending_idx ON endpoint_invocations(created_at,id) WHERE status NOT IN ('completed','partial_succeeded','failed','cancelled','timed_out');

ALTER TABLE applications ADD COLUMN max_concurrent INTEGER NOT NULL DEFAULT 0 CHECK(max_concurrent >= 0),
 ADD COLUMN token_budget BIGINT NOT NULL DEFAULT 0 CHECK(token_budget >= 0),
 ADD COLUMN tokens_used BIGINT NOT NULL DEFAULT 0 CHECK(tokens_used >= 0);
ALTER TABLE endpoint_invocations ADD COLUMN consumed_tokens BIGINT NOT NULL DEFAULT 0 CHECK(consumed_tokens >= 0);
CREATE INDEX idx_endpoint_invocations_application ON endpoint_invocations(application_id,created_at);

CREATE INDEX idx_endpoint_invocations_application_active ON endpoint_invocations(application_id) WHERE status NOT IN ('completed','partial_succeeded','failed','cancelled','timed_out');
CREATE INDEX idx_endpoint_invocations_conversation_active ON endpoint_invocations(conversation_id) WHERE status NOT IN ('completed','partial_succeeded','failed','cancelled','timed_out');
