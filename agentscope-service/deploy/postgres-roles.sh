#!/usr/bin/env sh
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
set -eu
# Run after schema jobs, with PostgreSQL owner credentials in PG* variables.
# psql quotes identifiers and values; passwords are never printed or concatenated into SQL.
: "${CONTROL_DB_PASSWORD:?Required}"
: "${AGENT_SERVICE_DB_PASSWORD:?Required}" "${AGENT_HARNESS_RUNTIME_DB_PASSWORD:?Required}" "${TOOL_WORKER_DB_PASSWORD:?Required}"
psql -X -v ON_ERROR_STOP=1 \
  -v cp_user="${CONTROL_DB_USER:-managed_agents_control}" -v cp_password="$CONTROL_DB_PASSWORD" \
  -v agent_service_user="${AGENT_SERVICE_DB_USER:-managed_agents_agent_service}" -v agent_service_password="$AGENT_SERVICE_DB_PASSWORD" \
  -v harness_runtime_user="${AGENT_HARNESS_RUNTIME_DB_USER:-managed_agents_agent_harness_runtime}" -v harness_runtime_password="$AGENT_HARNESS_RUNTIME_DB_PASSWORD" \
  -v tool_worker_user="${TOOL_WORKER_DB_USER:-managed_agents_tool_worker}" -v tool_worker_password="$TOOL_WORKER_DB_PASSWORD" <<'SQL'
SET statement_timeout='60s';
SET lock_timeout='30s';
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('managed-agents-business-roles', 0));
SELECT format('CREATE ROLE %I LOGIN', name) FROM (VALUES
  (:'cp_user'), (:'agent_service_user'), (:'harness_runtime_user'), (:'tool_worker_user')) roles(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=name) \gexec
SELECT count(DISTINCT name)=4 AND count(*)=4 AND bool_and(
  name ~ '^[a-z_][a-z0-9_]{0,62}$' AND name<>current_user
  AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(oid, current_user, 'MEMBER')
  AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=roles.oid AND nspname IN ('cp','rt','portal','dp','ma','ma_worker'))
  AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.relowner=roles.oid AND n.nspname IN ('cp','rt','portal','dp','ma','ma_worker'))
) AS roles_safe FROM (VALUES (:'cp_user'), (:'agent_service_user'),
  (:'harness_runtime_user'), (:'tool_worker_user')) requested(name) JOIN pg_roles roles ON roles.rolname=requested.name \gset
\if :roles_safe
\else
  DO $$ BEGIN
    RAISE EXCEPTION 'Business roles must be distinct, unprivileged, and must not own Service schemas or tables.';
  END $$;
\endif
SELECT format('ALTER ROLE %I PASSWORD %L', name, password) FROM (VALUES
  (:'cp_user', :'cp_password'),
  (:'agent_service_user', :'agent_service_password'), (:'harness_runtime_user', :'harness_runtime_password'),
 (:'tool_worker_user', :'tool_worker_password')) roles(name, password) \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', schema, name) FROM (VALUES
  ('cp', :'cp_user'), ('rt', :'cp_user'),
  ('portal', :'agent_service_user'), ('dp', :'harness_runtime_user'),
  ('ma', :'agent_service_user'), ('ma', :'harness_runtime_user'), ('ma', :'tool_worker_user'),
  ('ma_worker', :'agent_service_user'), ('ma_worker', :'harness_runtime_user'), ('ma_worker', :'tool_worker_user')) grants(schema, name) \gexec
SELECT format('GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA %I TO %I', schema, name)
FROM (VALUES ('cp', :'cp_user'), ('rt', :'cp_user'),
  ('portal', :'agent_service_user'), ('dp', :'harness_runtime_user'),
  ('ma', :'agent_service_user'), ('ma', :'harness_runtime_user'), ('ma', :'tool_worker_user'),
  ('ma_worker', :'agent_service_user'), ('ma_worker', :'harness_runtime_user'), ('ma_worker', :'tool_worker_user')) grants(schema, name) \gexec
SELECT format('GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', schema, name)
FROM (VALUES ('cp', :'cp_user'), ('rt', :'cp_user'),
  ('portal', :'agent_service_user'), ('dp', :'harness_runtime_user'),
  ('ma', :'agent_service_user'), ('ma', :'harness_runtime_user'), ('ma', :'tool_worker_user'),
  ('ma_worker', :'agent_service_user'), ('ma_worker', :'harness_runtime_user'), ('ma_worker', :'tool_worker_user')) grants(schema, name) \gexec
SELECT format('REVOKE INSERT,UPDATE,DELETE ON TABLE %I.%I FROM %I', schema, ledger, name)
FROM (VALUES ('cp', 'product_schema_migrations', :'cp_user'),
  ('rt', 'schema_migrations', :'cp_user'),
  ('portal', 'builder_schema_migration', :'agent_service_user'),
  ('dp', 'builder_schema_migration', :'harness_runtime_user'),
  ('ma', 'builder_schema_migration', :'agent_service_user'),
  ('ma', 'builder_schema_migration', :'harness_runtime_user'),
  ('ma', 'builder_schema_migration', :'tool_worker_user')) grants(schema, ledger, name) \gexec
-- Tool Worker owns physical facts. MQ-first admission reads only the required Runtime columns.
SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA ma FROM %I', :'tool_worker_user') \gexec
SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA ma FROM %I', :'tool_worker_user') \gexec
SELECT format('GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE ma.%I TO %I', tablename, :'tool_worker_user')
FROM pg_tables WHERE schemaname='ma' AND tablename LIKE 'hands\_%' ESCAPE '\' \gexec
SELECT format('GRANT SELECT ON TABLE ma.builder_schema_migration TO %I', :'tool_worker_user') \gexec
-- Column grants deliberately exclude checkpoint, input bodies, native logs and logical task results.
SELECT format('GRANT SELECT (%s) ON TABLE ma.%I TO %I', columns, tablename, :'tool_worker_user')
FROM (VALUES
  ('session', 'scope_key,execution_epoch,lifecycle'),
  ('turn', 'scope_key,turn_id,status,execution_epoch,execution_token'),
  ('input', 'scope_key,turn_id,type,status'),
  ('tool_task', 'scope_key,task_id'),
  ('tool_command_outbox', 'command_id,scope_key,payload,authorized'),
  ('admin_tool_operation', 'scope_key,task_id')) grants(tablename,columns) \gexec
-- PostgreSQL row locking requires UPDATE on at least one column; the gate never writes this key.
SELECT format('GRANT UPDATE (scope_key) ON TABLE ma.session TO %I', :'tool_worker_user') \gexec
-- Runtime replicas consume events rather than consulting the physical execution ledger.
SELECT format('REVOKE ALL ON TABLE ma.%I FROM %I', tablename, name)
FROM pg_tables CROSS JOIN (VALUES (:'agent_service_user'), (:'harness_runtime_user')) roles(name)
WHERE schemaname='ma' AND tablename LIKE 'hands\_%' ESCAPE '\' \gexec
COMMIT;
SQL
