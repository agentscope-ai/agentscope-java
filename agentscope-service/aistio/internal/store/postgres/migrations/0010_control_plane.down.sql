-- +migrate Down

DROP TABLE IF EXISTS endpoint_invocations;
DROP TABLE IF EXISTS endpoint_rate_windows;
DROP TABLE IF EXISTS endpoint_conversations;
DROP TABLE IF EXISTS endpoint_credentials;
DROP TABLE IF EXISTS endpoint_releases;
DROP TABLE IF EXISTS endpoints;
DROP TABLE IF EXISTS control_outbox;
DROP TABLE IF EXISTS runtime_hosts;
DROP TABLE IF EXISTS runtime_pools;
DROP TABLE IF EXISTS runtime_profiles;
DROP TABLE IF EXISTS agent_instances;
DROP TABLE IF EXISTS agent_registration_credentials;
DROP TABLE IF EXISTS agent_bindings;
DROP TABLE IF EXISTS agents;
DROP SEQUENCE IF EXISTS execution_attempt_fencing_seq;
