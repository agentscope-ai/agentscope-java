CREATE TABLE access_namespaces (
    tenant TEXT NOT NULL,
    name TEXT NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant,name)
);
CREATE INDEX access_namespace_members ON access_namespaces USING gin ((payload->'members'));
CREATE TABLE access_namespace_audit (
    id BIGSERIAL PRIMARY KEY,
    tenant TEXT NOT NULL,
    name TEXT NOT NULL,
    actor TEXT NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Preserve existing behavior explicitly; new work defaults to private.
ALTER TABLE issues ADD COLUMN access_policy JSONB NOT NULL DEFAULT '{"mode":"namespace"}';
ALTER TABLE issues ALTER COLUMN access_policy SET DEFAULT '{"mode":"private"}';

-- Child work always follows the live root policy. No copied ACL can outlive a revocation.
CREATE FUNCTION issue_access_allowed(issue_id UUID, subjects TEXT[], writable BOOLEAN DEFAULT false)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    WITH RECURSIVE ancestors AS (
        SELECT id,parent_issue_id,creator_type,creator_ref,access_policy,ARRAY[id] path
        FROM issues WHERE id=issue_id
        UNION ALL
        SELECT p.id,p.parent_issue_id,p.creator_type,p.creator_ref,p.access_policy,a.path||p.id
        FROM issues p JOIN ancestors a ON p.id=a.parent_issue_id WHERE NOT p.id=ANY(a.path)
    )
    SELECT COALESCE(bool_or(
        access_policy->>'mode'='namespace'
        OR (creator_type='human' AND creator_ref=ANY(subjects))
        OR (access_policy->>'mode'='shared' AND EXISTS (
            SELECT 1 FROM unnest(subjects) subject
            WHERE access_policy->'members'->>subject='contributor'
               OR (NOT writable AND access_policy->'members'->>subject='reader')
        ))
    ),false) FROM ancestors WHERE parent_issue_id IS NULL
$$;

CREATE FUNCTION target_work_access_allowed(target_kind TEXT, target_id TEXT, subjects TEXT[])
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
 SELECT CASE replace(target_kind,'_','-')
 WHEN 'issue' THEN COALESCE((SELECT issue_access_allowed(id,subjects) FROM issues WHERE id::text=target_id),false)
 WHEN 'agent-task' THEN COALESCE((SELECT issue_access_allowed(issue_id,subjects) FROM agent_tasks WHERE id::text=target_id),false)
 WHEN 'execution-attempt' THEN COALESCE((SELECT issue_access_allowed(t.issue_id,subjects) FROM execution_attempts e JOIN agent_tasks t ON t.id=e.agent_task_id WHERE e.id::text=target_id),false)
 WHEN 'run-node' THEN COALESCE((SELECT issue_access_allowed(r.root_issue_id,subjects) FROM orchestration_run_nodes n JOIN orchestration_runs r ON r.id=n.run_id WHERE n.id::text=target_id),false)
 ELSE false END
$$;
