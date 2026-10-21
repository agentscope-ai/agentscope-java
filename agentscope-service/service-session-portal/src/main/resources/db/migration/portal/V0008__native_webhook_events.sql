-- Native public event identities may include a descriptive prefix and a 64-character digest.
ALTER TABLE builder_portal_webhook ALTER COLUMN pending_event TYPE VARCHAR(256);
ALTER TABLE builder_portal_webhook_delivery ALTER COLUMN event_id TYPE VARCHAR(256);
