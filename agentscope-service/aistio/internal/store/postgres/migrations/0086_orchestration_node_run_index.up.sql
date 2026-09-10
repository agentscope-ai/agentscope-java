-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_node_run_idx ON orchestration_run_nodes (run_id, node_key, iteration);
