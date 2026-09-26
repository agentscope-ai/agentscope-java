-- +migrate Up
-- Endpoint-created work keeps its published execution target distinct from
-- the actor or team accountable for accepting the Issue.

ALTER TABLE issues ADD COLUMN IF NOT EXISTS execution_target_type TEXT;
ALTER TABLE issues ADD COLUMN IF NOT EXISTS execution_target_ref TEXT;
