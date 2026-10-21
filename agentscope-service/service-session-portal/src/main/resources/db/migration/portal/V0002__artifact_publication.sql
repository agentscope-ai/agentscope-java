-- Durable acknowledgement of an explicit public artifact publication.
CREATE TABLE IF NOT EXISTS builder_portal_artifact_publication (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    data TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
