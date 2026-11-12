-- Process-level liveness; no per-task heartbeat rows or counters.
CREATE SCHEMA IF NOT EXISTS ma_worker;
CREATE TABLE IF NOT EXISTS ma_worker.lease (
 incarnation VARCHAR(64) PRIMARY KEY, role VARCHAR(128) NOT NULL,
 instance_id VARCHAR(256) NOT NULL, expires_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS worker_lease_expiry ON ma_worker.lease(expires_at,incarnation);
