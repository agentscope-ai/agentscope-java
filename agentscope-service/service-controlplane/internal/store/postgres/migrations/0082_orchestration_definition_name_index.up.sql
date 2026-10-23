-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_definition_name_idx ON orchestration_definitions (tenant, namespace, name) WHERE archived_at IS NULL;
