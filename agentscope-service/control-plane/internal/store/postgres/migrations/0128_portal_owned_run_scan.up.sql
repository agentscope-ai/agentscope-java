ALTER TABLE orchestration_runs ADD COLUMN portal_owned BOOLEAN NOT NULL DEFAULT false;

-- This is a scheduling hint, not an authorization function. The execution
-- engine independently resolves and checks the live Portal contract.
CREATE FUNCTION portal_issue_owned(root UUID) RETURNS BOOLEAN
LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE ancestry AS (
  SELECT id,parent_issue_id,source_type,source_ref,ARRAY[id] AS visited,1 AS depth
  FROM issues WHERE id=root
  UNION ALL
  SELECT parent.id,parent.parent_issue_id,parent.source_type,parent.source_ref,
   child.visited || parent.id,child.depth+1
  FROM ancestry child JOIN issues parent ON parent.id=child.parent_issue_id
  WHERE child.depth<256 AND NOT parent.id=ANY(child.visited)
   AND COALESCE(child.source_type,'') NOT IN ('endpoint','session_turn','endpoint_conversation')
 )
 SELECT EXISTS(
  SELECT 1 FROM ancestry a JOIN endpoint_invocations invocation ON invocation.id=
   CASE WHEN a.source_ref ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    THEN a.source_ref::uuid ELSE NULL END
  WHERE a.source_type IN ('endpoint','session_turn','endpoint_conversation')
   AND jsonb_typeof(invocation.contract->'portal_command_id')='string'
   AND COALESCE(invocation.contract->>'portal_command_id','')<>''
 )
$$;

UPDATE orchestration_runs SET portal_owned=portal_issue_owned(root_issue_id)
 WHERE state NOT IN ('succeeded','partial_succeeded','failed','cancelled') OR terminal_cleanup_pending;

CREATE INDEX orchestration_runs_portal_reconcile_cursor ON orchestration_runs(created_at,id)
 WHERE portal_owned AND (state NOT IN ('succeeded','partial_succeeded','failed','cancelled') OR terminal_cleanup_pending);
