ALTER TABLE artifacts ADD COLUMN portal_command_id VARCHAR(256) NOT NULL DEFAULT '';
CREATE INDEX artifacts_portal_turn_page ON artifacts(tenant,namespace,portal_command_id,created_at,id)
 WHERE portal_command_id <> '';
