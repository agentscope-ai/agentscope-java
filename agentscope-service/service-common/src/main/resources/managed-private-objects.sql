-- Private immutable checkpoint and oversized MQ payload storage. No public file API uses this table.
CREATE TABLE IF NOT EXISTS ma.private_object (
    scope_key VARCHAR(64) NOT NULL,
    object_kind VARCHAR(16) NOT NULL CHECK (object_kind IN ('checkpoint', 'batch')),
    object_key VARCHAR(128) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 67108864),
    payload BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(scope_key, object_kind, object_key),
    CHECK (octet_length(payload) = size_bytes)
);
