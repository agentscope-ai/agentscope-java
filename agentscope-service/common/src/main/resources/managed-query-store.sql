-- Run only against the query database. No execution schema or foreign keys are required.
CREATE SCHEMA IF NOT EXISTS ma_query;
CREATE TABLE IF NOT EXISTS ma_query.progress (
 scope_key VARCHAR(64) NOT NULL, build_id VARCHAR(128) NOT NULL,
 event_span INTEGER NOT NULL, directory_span INTEGER NOT NULL,
 visible_sequence BIGINT NOT NULL DEFAULT 0, commit_version BIGINT NOT NULL DEFAULT 0,
 progress_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(scope_key,build_id)
);
CREATE TABLE IF NOT EXISTS ma_query.event_history (
 scope_key VARCHAR(64) NOT NULL, build_id VARCHAR(128) NOT NULL,
 segment BIGINT NOT NULL, sequence BIGINT NOT NULL, data TEXT NOT NULL,
 PRIMARY KEY(scope_key,build_id,segment,sequence)
);
CREATE INDEX IF NOT EXISTS query_event_prefix ON ma_query.event_history(scope_key,build_id,sequence);
CREATE TABLE IF NOT EXISTS ma_query.resource_version (
 scope_key VARCHAR(64) NOT NULL, build_id VARCHAR(128) NOT NULL, kind VARCHAR(64) NOT NULL,
 resource_id VARCHAR(512) NOT NULL, sequence BIGINT NOT NULL, data TEXT NOT NULL,
 PRIMARY KEY(scope_key,build_id,kind,resource_id,sequence)
);
CREATE TABLE IF NOT EXISTS ma_query.resource_directory (
 scope_key VARCHAR(64) NOT NULL, build_id VARCHAR(128) NOT NULL, kind VARCHAR(64) NOT NULL,
 resource_id VARCHAR(512) NOT NULL, segment BIGINT NOT NULL, created_sequence BIGINT NOT NULL,
 PRIMARY KEY(scope_key,build_id,kind,resource_id)
);
CREATE INDEX IF NOT EXISTS query_directory_page ON ma_query.resource_directory(scope_key,build_id,kind,created_sequence,resource_id);
CREATE TABLE IF NOT EXISTS ma_query.resource_index (
 scope_key VARCHAR(64) NOT NULL, build_id VARCHAR(128) NOT NULL, kind VARCHAR(64) NOT NULL,
 position VARCHAR(1024) NOT NULL, sequence BIGINT NOT NULL, data TEXT NOT NULL,
 PRIMARY KEY(scope_key,build_id,kind,position)
);
