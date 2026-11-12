-- Local P5 protocol foundation. Not enabled by the production schema job yet.
CREATE SCHEMA IF NOT EXISTS ma_route;
CREATE TABLE IF NOT EXISTS ma_route.session_route (
 scope_key VARCHAR(64) PRIMARY KEY, cell VARCHAR(24) NOT NULL,
 layout_version INTEGER NOT NULL, shard_count INTEGER NOT NULL, logical_shard INTEGER NOT NULL
);
