-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_revision_idx ON orchestration_revisions (definition_id, revision);
