-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_automations_name ON automations (tenant, namespace, name) WHERE archived_at IS NULL;
