-- Session Service owns cross-language fencing, receipts and opaque recovery heads.
CREATE TABLE IF NOT EXISTS ma.harness_head (
 scope_key VARCHAR(128) PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0,
 native_state TEXT, harness_type VARCHAR(128), state_format VARCHAR(128)
);
CREATE TABLE IF NOT EXISTS ma.harness_lease (
 scope_key VARCHAR(128) PRIMARY KEY, turn_id VARCHAR(256) NOT NULL,
 epoch BIGINT NOT NULL, execution_token VARCHAR(256) NOT NULL,
 owner TEXT NOT NULL, expires_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ma.harness_receipt (
 scope_key VARCHAR(128) NOT NULL, operation VARCHAR(64) NOT NULL,
 request_id VARCHAR(256) NOT NULL, content_hash VARCHAR(64) NOT NULL,
 response TEXT NOT NULL, PRIMARY KEY(scope_key,operation,request_id)
);
CREATE TABLE IF NOT EXISTS ma.harness_boundary (
 scope_key VARCHAR(128) NOT NULL, turn_id VARCHAR(256) NOT NULL,
 revision BIGINT NOT NULL, payload TEXT NOT NULL,
 PRIMARY KEY(scope_key,revision)
);
CREATE TABLE IF NOT EXISTS ma.harness_model (
 scope_key VARCHAR(128) NOT NULL, turn_id VARCHAR(256) NOT NULL,
 model_call_id VARCHAR(256) NOT NULL, request_sequence BIGINT NOT NULL,
 request TEXT NOT NULL, PRIMARY KEY(scope_key,model_call_id)
);
CREATE TABLE IF NOT EXISTS ma.harness_store (
 namespace_path VARCHAR(2048) NOT NULL, item_key VARCHAR(255) NOT NULL,
 value_json TEXT NOT NULL, version BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(namespace_path,item_key)
);

CREATE TABLE IF NOT EXISTS ma.harness_binding (scope_key VARCHAR(128) PRIMARY KEY, harness_type VARCHAR(128) NOT NULL);

CREATE TABLE IF NOT EXISTS ma.harness_configuration (
 scope_key VARCHAR(256) NOT NULL, turn_id VARCHAR(256) NOT NULL, payload TEXT NOT NULL,
 PRIMARY KEY(scope_key,turn_id)
);

CREATE TABLE IF NOT EXISTS ma.harness_model_configuration (
 scope_key VARCHAR(256) NOT NULL, turn_id VARCHAR(256) NOT NULL, payload TEXT NOT NULL,
 PRIMARY KEY(scope_key,turn_id)
);
