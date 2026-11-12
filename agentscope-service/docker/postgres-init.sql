-- Shared Postgres bootstrap. One instance, five schemas:
--   cp  Managed Agents control plane (control-plane, product module)
--   rt  control-plane runtime store (sessions, events, context snapshots, metrics)
--   dp  Java data plane
CREATE SCHEMA IF NOT EXISTS cp;
CREATE SCHEMA IF NOT EXISTS rt;
CREATE SCHEMA IF NOT EXISTS portal;
CREATE SCHEMA IF NOT EXISTS dp;
CREATE SCHEMA IF NOT EXISTS ma;
GRANT ALL ON SCHEMA cp TO builder;
GRANT ALL ON SCHEMA rt TO builder;
GRANT ALL ON SCHEMA dp TO builder;
-- Default search_path for the app role: prefer dp for unqualified Java JDBC.
-- control-plane pins its own schema per connection pool (search_path=cp / rt).
ALTER ROLE builder SET search_path TO dp, cp, public;
