ALTER TABLE automations ADD COLUMN execution JSONB, ADD COLUMN triggers JSONB;
ALTER TABLE automation_runs ADD COLUMN runtime JSONB;
CREATE TABLE automation_deliveries (
 id UUID PRIMARY KEY, automation_id UUID NOT NULL, trigger_id UUID NOT NULL,
 idempotency_key TEXT NOT NULL, input JSONB, event TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL, reason TEXT NOT NULL DEFAULT '', run_id UUID,
 replayed_from UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (automation_id,trigger_id,idempotency_key)
);
