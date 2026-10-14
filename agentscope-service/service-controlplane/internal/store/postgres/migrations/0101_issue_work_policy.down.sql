-- +migrate Down

DROP INDEX IF EXISTS idx_issues_visibility_updated;
ALTER TABLE issues DROP COLUMN IF EXISTS completion_policy;
ALTER TABLE issues DROP COLUMN IF EXISTS visibility;
ALTER TABLE issues DROP COLUMN IF EXISTS kind;
