DROP INDEX IF EXISTS idx_inbox_attention;
ALTER TABLE inbox_items DROP COLUMN IF EXISTS needs_action;
ALTER TABLE inbox_items DROP COLUMN IF EXISTS read_at;
ALTER TABLE inbox_items DROP COLUMN IF EXISTS resolved_at;
