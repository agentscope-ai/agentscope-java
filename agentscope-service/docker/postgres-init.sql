-- Shared Postgres bootstrap. One instance, three schemas:
--   cp  Managed Agents control plane (service-controlplane, product module)
--   rt  service-controlplane runtime store (sessions, events, context snapshots, metrics)
--   dp  Java data plane / scheduler
CREATE SCHEMA IF NOT EXISTS cp;
CREATE SCHEMA IF NOT EXISTS rt;
CREATE SCHEMA IF NOT EXISTS dp;
GRANT ALL ON SCHEMA cp TO builder;
GRANT ALL ON SCHEMA rt TO builder;
GRANT ALL ON SCHEMA dp TO builder;
-- Default search_path for the app role: prefer dp for unqualified Java JDBC.
-- service-controlplane pins its own schema per connection pool (search_path=cp / rt).
ALTER ROLE builder SET search_path TO dp, cp, public;
