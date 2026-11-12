CREATE INDEX approvals_pending_runtime_cursor ON approvals(target_type,id)
 WHERE status='pending';
