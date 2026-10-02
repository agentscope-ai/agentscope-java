-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY agent_runtime_policy_id_idx ON agent_runtime_policies (id);
