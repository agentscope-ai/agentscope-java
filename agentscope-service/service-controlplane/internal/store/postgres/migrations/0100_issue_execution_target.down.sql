-- +migrate Down

ALTER TABLE issues DROP COLUMN IF EXISTS execution_target_ref;
ALTER TABLE issues DROP COLUMN IF EXISTS execution_target_type;
