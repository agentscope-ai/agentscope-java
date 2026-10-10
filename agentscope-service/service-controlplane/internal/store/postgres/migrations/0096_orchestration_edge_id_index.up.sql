-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_edge_id_idx ON orchestration_run_edges (id);
