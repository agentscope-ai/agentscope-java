-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_revision_id_idx ON orchestration_revisions (id);
