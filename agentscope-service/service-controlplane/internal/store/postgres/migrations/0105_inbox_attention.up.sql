-- Reading a message and completing its business action are independent.
ALTER TABLE inbox_items ADD COLUMN needs_action BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE inbox_items ADD COLUMN read_at TIMESTAMPTZ;
ALTER TABLE inbox_items ADD COLUMN resolved_at TIMESTAMPTZ;

UPDATE inbox_items i SET needs_action=(a.status='pending'),
    archived=CASE WHEN a.status='pending' THEN false ELSE true END,
    resolved_at=CASE WHEN a.status='pending' THEN NULL ELSE a.updated_at END
FROM approvals a WHERE i.approval_id=a.id;

INSERT INTO inbox_items(id,tenant,namespace,recipient_type,recipient_ref,type,severity,
    issue_id,approval_id,actor_type,actor_ref,title,body,needs_action,dedupe_key,created_at)
SELECT gen_random_uuid(),a.tenant,a.namespace,'human',a.approver_ref,'approval','attention',
    a.issue_id,a.id,a.requested_by_type,a.requested_by_ref,'Approval requested',a.reason,true,
    'approval:'||a.id::text,a.created_at
FROM approvals a WHERE a.status='pending' AND NOT EXISTS (
    SELECT 1 FROM inbox_items i WHERE i.approval_id=a.id AND i.recipient_ref=a.approver_ref)
ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING;

WITH ranked AS (
    SELECT id,row_number() OVER(PARTITION BY tenant,namespace,recipient_ref,approval_id
        ORDER BY read DESC,created_at,id) AS position
    FROM inbox_items WHERE approval_id IS NOT NULL AND NOT archived
)
UPDATE inbox_items i SET archived=true,needs_action=false FROM ranked r
WHERE i.id=r.id AND r.position>1;

-- Retain the explicit notification when a subscription produced a second copy.
UPDATE inbox_items i SET archived=true
WHERE i.type='issue_update' AND i.comment_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM inbox_items direct WHERE direct.tenant=i.tenant AND direct.namespace=i.namespace
    AND direct.recipient_ref=i.recipient_ref AND direct.comment_id=i.comment_id
    AND direct.type IN ('mention','reply','result','review_request'));
UPDATE inbox_items i SET archived=true FROM comments c
WHERE i.comment_id=c.id AND i.type='issue_update' AND c.type IN ('progress','status','system');

-- Backfill only currently actionable work. A migration and the first new state
-- transition use the same episode key, never replaying all historical events.
INSERT INTO inbox_items (id,tenant,namespace,recipient_type,recipient_ref,type,severity,
    issue_id,actor_type,title,body,details,needs_action,dedupe_key,created_at)
SELECT gen_random_uuid(),i.tenant,i.namespace,'human',owner.ref,
    CASE WHEN i.status='blocked' THEN 'issue_blocked' ELSE 'review_request' END,
    CASE WHEN i.status='blocked' THEN 'warning' ELSE 'attention' END,
    i.id,'system',CASE WHEN i.status='blocked' THEN 'Blocked: ' ELSE 'Review requested: ' END || i.title,
    CASE WHEN i.status='blocked' THEN 'Work needs your attention before it can continue.' ELSE 'Execution completed; awaiting acceptance.' END,
    jsonb_build_object('status',i.status,'statusVersion',i.version,'backfilled',true),true,
    'issue-status:'||i.id::text||':'||i.version::text||':'||i.namespace||':'||owner.ref,i.updated_at
FROM issues i
CROSS JOIN LATERAL (SELECT COALESCE(
    CASE WHEN i.assignee_type='human' THEN NULLIF(i.assignee_ref,'') END,
    (SELECT NULLIF(t.accountable_human_ref,'') FROM agent_tasks t
     WHERE t.issue_id=i.id AND t.parent_task_id IS NULL AND t.accountable_human_ref<>''
     ORDER BY t.created_at DESC,t.id LIMIT 1),
    CASE WHEN i.creator_type='human' THEN NULLIF(i.creator_ref,'') END) AS ref) owner
WHERE i.kind='user_work' AND i.visibility='work_hub' AND i.archived_at IS NULL
AND (i.parent_issue_id IS NULL OR i.assignee_type='human') AND owner.ref IS NOT NULL
AND (i.status='blocked' OR (i.status='in_review' AND i.completion_policy='review'))
AND NOT EXISTS (SELECT 1 FROM inbox_items existing WHERE existing.issue_id=i.id
    AND existing.recipient_ref=owner.ref AND existing.archived=false
    AND existing.type=CASE WHEN i.status='blocked' THEN 'issue_blocked' ELSE 'review_request' END)
ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING;

UPDATE inbox_items n SET needs_action=(n.recipient_ref=COALESCE(
    CASE WHEN i.assignee_type='human' THEN NULLIF(i.assignee_ref,'') END,
    (SELECT NULLIF(t.accountable_human_ref,'') FROM agent_tasks t
     WHERE t.issue_id=i.id AND t.parent_task_id IS NULL AND t.accountable_human_ref<>''
     ORDER BY t.created_at DESC,t.id LIMIT 1),
    CASE WHEN i.creator_type='human' THEN NULLIF(i.creator_ref,'') END,'')) FROM issues i
WHERE n.issue_id=i.id AND NOT n.archived
AND ((n.type='review_request' AND i.status='in_review' AND i.completion_policy='review')
    OR (n.type='issue_blocked' AND i.status='blocked'))
AND i.kind='user_work' AND i.visibility='work_hub' AND i.archived_at IS NULL
AND (i.parent_issue_id IS NULL OR i.assignee_type='human');

UPDATE inbox_items n SET archived=true,resolved_at=i.updated_at FROM issues i
WHERE n.issue_id=i.id AND n.type='review_request' AND NOT n.archived
AND (i.status<>'in_review' OR i.completion_policy<>'review' OR i.kind<>'user_work'
    OR i.visibility<>'work_hub' OR i.archived_at IS NOT NULL
    OR (i.parent_issue_id IS NOT NULL AND i.assignee_type IS DISTINCT FROM 'human'));

CREATE INDEX idx_inbox_attention ON inbox_items
    (tenant,namespace,recipient_ref,archived,needs_action DESC,created_at DESC,id);
