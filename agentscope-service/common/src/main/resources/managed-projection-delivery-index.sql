-- Only outstanding publications participate in repair scheduling.
CREATE INDEX IF NOT EXISTS ma_projection_outbox_pending ON ma.runtime_commit(next_attempt_at,scope_key,public_commit_version) WHERE publication_state IN ('pending','inflight');
CREATE INDEX IF NOT EXISTS ma_projection_outbox_scope ON ma.runtime_commit(scope_key,public_commit_version) WHERE publication_state IN ('pending','inflight');
