-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_definition_id_idx ON orchestration_definitions (id);
