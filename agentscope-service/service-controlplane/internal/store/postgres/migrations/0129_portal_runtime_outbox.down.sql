DROP INDEX control_outbox_portal_due;
ALTER TABLE control_outbox DROP COLUMN portal_owned;
DROP FUNCTION portal_runtime_event_owned(TEXT,TEXT);
