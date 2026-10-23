-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_bus_key ON dp_bus_entries (tenant, bus_key, kind, id);
