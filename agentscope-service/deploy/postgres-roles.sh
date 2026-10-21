#!/usr/bin/env sh
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
set -eu
# Run after schema jobs, with PostgreSQL owner credentials in PG* variables.
# psql quotes identifiers and values; passwords are never printed or concatenated into SQL.
: "${CONTROL_DB_PASSWORD:?Required}"
: "${PORTAL_DB_PASSWORD:?Required}" "${DATA_DB_PASSWORD:?Required}" "${HANDS_DB_PASSWORD:?Required}"
psql -X -v ON_ERROR_STOP=1 \
  -v cp_user="${CONTROL_DB_USER:-agentscope_control}" -v cp_password="$CONTROL_DB_PASSWORD" \
  -v portal_user="${PORTAL_DB_USER:-agentscope_portal}" -v portal_password="$PORTAL_DB_PASSWORD" \
  -v dp_user="${DATA_DB_USER:-agentscope_data}" -v dp_password="$DATA_DB_PASSWORD" \
  -v hands_user="${HANDS_DB_USER:-agentscope_hands}" -v hands_password="$HANDS_DB_PASSWORD" <<'SQL'
SET statement_timeout='60s';
SET lock_timeout='30s';
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('agentscope-service-business-roles', 0));
SELECT format('CREATE ROLE %I LOGIN', name) FROM (VALUES
  (:'cp_user'), (:'portal_user'), (:'dp_user'), (:'hands_user')) roles(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=name) \gexec
SELECT count(DISTINCT name)=4 AND count(*)=4 AND bool_and(
  name ~ '^[a-z_][a-z0-9_]{0,62}$' AND name<>current_user
  AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(oid, current_user, 'MEMBER')
  AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=roles.oid AND nspname IN ('cp','rt','portal','dp','ma'))
  AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.relowner=roles.oid AND n.nspname IN ('cp','rt','portal','dp','ma'))
) AS roles_safe FROM (VALUES (:'cp_user'), (:'portal_user'),
  (:'dp_user'), (:'hands_user')) requested(name) JOIN pg_roles roles ON roles.rolname=requested.name \gset
\if :roles_safe
\else
  DO $$ BEGIN
    RAISE EXCEPTION 'Business roles must be distinct, unprivileged, and must not own Service schemas or tables.';
  END $$;
\endif
SELECT format('ALTER ROLE %I PASSWORD %L', name, password) FROM (VALUES
  (:'cp_user', :'cp_password'),
  (:'portal_user', :'portal_password'), (:'dp_user', :'dp_password'),
 (:'hands_user', :'hands_password')) roles(name, password) \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', schema, name) FROM (VALUES
  ('cp', :'cp_user'), ('rt', :'cp_user'),
  ('portal', :'portal_user'), ('dp', :'dp_user'),
  ('ma', :'portal_user'), ('ma', :'dp_user'), ('ma', :'hands_user')) grants(schema, name) \gexec
SELECT format('GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA %I TO %I', schema, name)
FROM (VALUES ('cp', :'cp_user'), ('rt', :'cp_user'),
  ('portal', :'portal_user'), ('dp', :'dp_user'),
  ('ma', :'portal_user'), ('ma', :'dp_user'), ('ma', :'hands_user')) grants(schema, name) \gexec
SELECT format('GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', schema, name)
FROM (VALUES ('cp', :'cp_user'), ('rt', :'cp_user'),
  ('portal', :'portal_user'), ('dp', :'dp_user'),
  ('ma', :'portal_user'), ('ma', :'dp_user'), ('ma', :'hands_user')) grants(schema, name) \gexec
SELECT format('REVOKE INSERT,UPDATE,DELETE ON TABLE %I.%I FROM %I', schema, ledger, name)
FROM (VALUES ('cp', 'product_schema_migrations', :'cp_user'),
  ('rt', 'schema_migrations', :'cp_user'),
  ('portal', 'builder_schema_migration', :'portal_user'),
  ('dp', 'builder_schema_migration', :'dp_user'),
  ('ma', 'builder_schema_migration', :'portal_user'),
  ('ma', 'builder_schema_migration', :'dp_user'),
  ('ma', 'builder_schema_migration', :'hands_user')) grants(schema, ledger, name) \gexec
-- Reset Hands to its physical ledger and the narrow admission facts required by the commit gate.
-- Checkpoints, native logs, configuration and authorization writes remain Runtime-owned.
SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA ma FROM %I', :'hands_user') \gexec
SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA ma FROM %I', :'hands_user') \gexec
SELECT format('GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE ma.%I TO %I', tablename, :'hands_user')
FROM pg_tables WHERE schemaname='ma' AND tablename LIKE 'hands\_%' ESCAPE '\' \gexec
SELECT format('GRANT SELECT ON TABLE ma.builder_schema_migration TO %I', :'hands_user') \gexec
SELECT format('GRANT SELECT,INSERT ON TABLE ma.private_object TO %I', :'hands_user') \gexec
-- SELECT FOR UPDATE requires UPDATE on a column; the gate itself never updates the fence.
SELECT format('GRANT SELECT (%s) ON TABLE ma.%I TO %I', columns, relation, :'hands_user')
FROM (VALUES
  ('scope_key,execution_epoch,lifecycle', 'session'),
  ('scope_key,turn_id,status,execution_epoch,execution_token', 'turn'),
  ('command_id,scope_key,payload,authorized', 'tool_command_outbox'),
  ('scope_key,task_id', 'tool_task'),
  ('scope_key,task_id', 'admin_tool_operation'),
  ('scope_key,turn_id,type,status', 'input')) grants(columns, relation) \gexec
SELECT format('GRANT UPDATE (execution_epoch) ON TABLE ma.session TO %I', :'hands_user') \gexec
-- Runtime replicas consume events rather than consulting the physical execution ledger.
SELECT format('REVOKE ALL ON TABLE ma.%I FROM %I', tablename, name)
FROM pg_tables CROSS JOIN (VALUES (:'portal_user'), (:'dp_user')) roles(name)
WHERE schemaname='ma' AND tablename LIKE 'hands\_%' ESCAPE '\' \gexec
SELECT format('REVOKE UPDATE,DELETE ON TABLE ma.private_object FROM %I', name)
FROM (VALUES (:'portal_user'), (:'dp_user'), (:'hands_user')) roles(name) \gexec
COMMIT;
SQL
