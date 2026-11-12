-- Private, expiring MQ capabilities; Turn terminal facts form the durable ACK intent.
-- Distinct receipt generations never overwrite each other. This is not a runnable Turn queue.
CREATE TABLE IF NOT EXISTS ma.turn_ack_receipt (
    receipt_id VARCHAR(64) PRIMARY KEY,
    scope_key VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    publication_sequence BIGINT NOT NULL,
    topic VARCHAR(1024) NOT NULL,
    consumer_group VARCHAR(1024) NOT NULL,
    payload TEXT NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    FOREIGN KEY(scope_key,turn_id) REFERENCES ma.turn(scope_key,turn_id)
);
CREATE INDEX IF NOT EXISTS ma_turn_ack_pending ON ma.turn_ack_receipt(topic,consumer_group,ready,next_attempt_at);
CREATE INDEX IF NOT EXISTS ma_turn_ack_expiry ON ma.turn_ack_receipt(expires_at);
CREATE INDEX IF NOT EXISTS ma_turn_ack_turn ON ma.turn_ack_receipt(scope_key,turn_id);
