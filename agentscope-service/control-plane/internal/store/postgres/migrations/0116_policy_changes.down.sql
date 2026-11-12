DROP TRIGGER IF EXISTS catalog_agent_policy_change ON agents;
DROP TRIGGER IF EXISTS catalog_binding_policy_change ON agent_bindings;
DROP TRIGGER IF EXISTS namespace_policy_change ON access_namespaces;
DROP TRIGGER IF EXISTS application_policy_change ON applications;
DROP TRIGGER IF EXISTS credential_policy_change ON dp_kv;
DROP FUNCTION IF EXISTS emit_policy_change();
DROP TABLE IF EXISTS policy_change_outbox;
