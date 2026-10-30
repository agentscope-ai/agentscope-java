-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY agent_runtime_policy_idx ON agent_runtime_policies (tenant, namespace, agent_id) WHERE archived_at IS NULL;
