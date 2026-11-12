CREATE INDEX IF NOT EXISTS orchestration_nodes_human_wait
 ON orchestration_run_nodes(run_id,id)
 WHERE state='waiting' AND type IN ('approval','signal');

CREATE INDEX IF NOT EXISTS orchestration_nodes_autonomous
 ON orchestration_run_nodes(run_id,id)
 WHERE state IN ('ready','running') OR (state='waiting' AND type NOT IN ('approval','signal'));

CREATE INDEX IF NOT EXISTS approvals_node_observation
 ON approvals(tenant,namespace,target_type,target_ref,created_at DESC,id);
