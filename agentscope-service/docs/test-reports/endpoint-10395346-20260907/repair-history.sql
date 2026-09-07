BEGIN;
DO $repair$
BEGIN
  PERFORM 1 FROM rt.issues WHERE id='10395346-02dc-5fff-bb96-bef5404cb642' AND version=6 AND status='cancelled' AND kind='endpoint_job' AND completion_policy='automatic' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'historical Issue changed; abort repair'; END IF;
  PERFORM 1 FROM rt.orchestration_runs WHERE id='37a0e973-c617-5a3b-a446-087dbdf4d963' AND root_issue_id='10395346-02dc-5fff-bb96-bef5404cb642' AND state='failed' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Run no longer failed'; END IF;
  IF EXISTS (SELECT 1 FROM rt.agent_tasks WHERE issue_id='10395346-02dc-5fff-bb96-bef5404cb642' AND status IN ('queued','dispatched','running')) THEN RAISE EXCEPTION 'active work exists'; END IF;
  IF NOT EXISTS (SELECT 1 FROM rt.activity_log WHERE issue_id='10395346-02dc-5fff-bb96-bef5404cb642' AND action='issue.status_changed' AND actor_type='system' AND actor_ref='endpoint-invocation:1109b0e0-511a-41e3-8442-58914ed3060a' AND details->>'from'='blocked' AND details->>'to'='cancelled' AND details->>'reason'='Endpoint Job execution did not complete successfully') THEN RAISE EXCEPTION 'missing erroneous Endpoint transition'; END IF;
  UPDATE rt.issues SET description='请求输入：

```json
{
  "prompt": "帮我查看当前目录下的文件，写一首诗"
}
```

历史修复说明：修复 Endpoint 输入丢失及失败被误标为取消的问题。原始执行因未收到用户问题而错误索要 API 参数；保留原 Run、Task、Attempt 失败历史，本次未重跑旧任务。',status='blocked',version=version+1,updated_at=now(),resolved_at=NULL WHERE id='10395346-02dc-5fff-bb96-bef5404cb642';
  INSERT INTO rt.activity_log(id,tenant,namespace,issue_id,actor_type,actor_ref,action,object_type,object_ref,details)
  VALUES ('cf75a54b-a480-4f08-a1b3-96385d018344','default','default','10395346-02dc-5fff-bb96-bef5404cb642','system','repair:endpoint-input-20260907','issue.status_changed','issue','10395346-02dc-5fff-bb96-bef5404cb642','{"from": "cancelled", "to": "blocked", "reason": "修复 Endpoint 输入丢失及失败被误标为取消的问题。原始执行因未收到用户问题而错误索要 API 参数；保留原 Run、Task、Attempt 失败历史，本次未重跑旧任务。", "recoveredInputFromRunId": "37a0e973-c617-5a3b-a446-087dbdf4d963"}'::jsonb);
  INSERT INTO rt.control_outbox(id,tenant,namespace,aggregate_type,aggregate_id,event_type,actor_type,actor_ref,payload,dedupe_key)
  VALUES ('be21487f-43d8-4327-9a5e-9d67f3acfa8e','default','default','issue','10395346-02dc-5fff-bb96-bef5404cb642','issue.status-changed.v1','system','repair:endpoint-input-20260907','{"issue": {"id": "10395346-02dc-5fff-bb96-bef5404cb642", "tenant": "default", "namespace": "default", "title": "team1 API invocation", "status": "blocked", "priority": "normal", "kind": "endpoint_job", "visibility": "operational", "completionPolicy": "automatic", "assigneeType": "team", "assigneeRef": "cf7b2403-a314-44f9-a959-e81708e7ae60", "executionTargetType": "team", "executionTargetRef": "cf7b2403-a314-44f9-a959-e81708e7ae60", "creator": {"type": "system", "ref": "endpoint:357bb97c-ec8b-419f-b3bf-e5e44820e79f"}, "acceptanceCriteria": [], "contextRefs": [], "sourceType": "endpoint", "sourceRef": "1109b0e0-511a-41e3-8442-58914ed3060a", "version": 7, "createdAt": "2026-09-07T20:04:52.530596+08:00", "updatedAt": "2026-09-07T12:24:50.803014+00:00", "description": "请求输入：\n\n```json\n{\n  \"prompt\": \"帮我查看当前目录下的文件，写一首诗\"\n}\n```\n\n历史修复说明：修复 Endpoint 输入丢失及失败被误标为取消的问题。原始执行因未收到用户问题而错误索要 API 参数；保留原 Run、Task、Attempt 失败历史，本次未重跑旧任务。"}, "previousStatus": "cancelled", "repairReason": "修复 Endpoint 输入丢失及失败被误标为取消的问题。原始执行因未收到用户问题而错误索要 API 参数；保留原 Run、Task、Attempt 失败历史，本次未重跑旧任务。"}'::jsonb,'endpoint-input-repair:10395346-02dc-5fff-bb96-bef5404cb642');
END $repair$;
COMMIT;
