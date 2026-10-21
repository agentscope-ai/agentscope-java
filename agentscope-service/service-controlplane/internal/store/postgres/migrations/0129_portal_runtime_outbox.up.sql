ALTER TABLE control_outbox ADD COLUMN portal_owned BOOLEAN NOT NULL DEFAULT false;

-- Resolve scheduling ownership from durable aggregates, never from payload or
-- caller flags. Execution still requires the live Portal admission contract.
CREATE FUNCTION portal_runtime_event_owned(kind TEXT, ref TEXT) RETURNS BOOLEAN
LANGUAGE SQL STABLE AS $$
 WITH identity AS (
  SELECT CASE WHEN ref ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
   THEN ref::uuid ELSE NULL END AS id
 )
 SELECT CASE kind
  WHEN 'agent-task' THEN EXISTS(
   SELECT 1 FROM identity i JOIN agent_tasks t ON t.id=i.id
   JOIN orchestration_runs r ON r.id=t.orchestration_run_id WHERE r.portal_owned)
  WHEN 'execution-attempt' THEN EXISTS(
   SELECT 1 FROM identity i JOIN execution_attempts a ON a.id=i.id
   JOIN orchestration_runs r ON r.id=a.run_id WHERE r.portal_owned)
  WHEN 'approval' THEN EXISTS(
   SELECT 1 FROM identity i JOIN approvals a ON a.id=i.id
   WHERE EXISTS(SELECT 1 FROM orchestration_runs r WHERE r.id=a.run_id AND r.portal_owned)
    OR portal_issue_owned(a.issue_id))
  ELSE false END
$$;

UPDATE control_outbox SET portal_owned=portal_runtime_event_owned(aggregate_type,aggregate_id)
 WHERE delivered_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX control_outbox_portal_due ON control_outbox(created_at,id)
 WHERE portal_owned AND delivered_at IS NULL AND dead_lettered_at IS NULL;
