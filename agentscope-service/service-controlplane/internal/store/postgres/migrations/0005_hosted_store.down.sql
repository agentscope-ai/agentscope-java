-- +migrate Down
DROP TABLE IF EXISTS dp_async_tools;
DROP TABLE IF EXISTS dp_bus_entries;
DROP TABLE IF EXISTS dp_snapshots;
DROP TABLE IF EXISTS dp_locks;
DROP SEQUENCE IF EXISTS dp_lock_fencing_seq;
DROP TABLE IF EXISTS dp_kv;
