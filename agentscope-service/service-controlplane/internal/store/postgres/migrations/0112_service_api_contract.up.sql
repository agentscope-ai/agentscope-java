ALTER TABLE endpoint_releases ADD COLUMN contract JSONB;
ALTER TABLE endpoint_invocations ADD COLUMN release_id UUID REFERENCES endpoint_releases(id), ADD COLUMN contract JSONB;
ALTER TABLE endpoint_conversations ADD COLUMN release_id UUID REFERENCES endpoint_releases(id), ADD COLUMN contract JSONB;
CREATE INDEX endpoint_invocations_pending_idx ON endpoint_invocations(created_at,id) WHERE status NOT IN ('completed','failed','cancelled','timed_out');
