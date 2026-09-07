"""Narrow, audited repair for the reported historical Endpoint job only."""
from probe import *
import subprocess, uuid
issue_id='10395346-02dc-5fff-bb96-bef5404cb642'
run_id='37a0e973-c617-5a3b-a446-087dbdf4d963'
before=api('/api/v1/issues/'+issue_id+'/export',label='repair-backup')
issue=before['issue']
run=api('/api/v1/orchestration-runs/'+run_id+'/graph',label='repair-run-backup')['run']
assert issue['status']=='cancelled' and issue['version']==6 and not issue.get('description')
assert run['state']=='failed' and run['triggerType']=='endpoint' and run['rootIssueId']==issue_id
assert run['input']=={'prompt':'帮我查看当前目录下的文件，写一首诗'}
reason='修复 Endpoint 输入丢失及失败被误标为取消的问题。原始执行因未收到用户问题而错误索要 API 参数；保留原 Run、Task、Attempt 失败历史，本次未重跑旧任务。'
description='请求输入：\n\n```json\n'+json.dumps(run['input'],ensure_ascii=False,indent=2)+'\n```\n\n历史修复说明：'+reason
# A transaction validates the exact historical version and erroneous system transition.
def q(s): return "'"+str(s).replace("'","''")+"'"
updated={**issue,'description':description,'status':'blocked','version':7,'updatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
payload={'issue':updated,'previousStatus':'cancelled','repairReason':reason}
sql=f'''BEGIN;
DO $repair$
BEGIN
  PERFORM 1 FROM rt.issues WHERE id={q(issue_id)} AND version=6 AND status='cancelled' AND kind='endpoint_job' AND completion_policy='automatic' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'historical Issue changed; abort repair'; END IF;
  PERFORM 1 FROM rt.orchestration_runs WHERE id={q(run_id)} AND root_issue_id={q(issue_id)} AND state='failed' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Run no longer failed'; END IF;
  IF EXISTS (SELECT 1 FROM rt.agent_tasks WHERE issue_id={q(issue_id)} AND status IN ('queued','dispatched','running')) THEN RAISE EXCEPTION 'active work exists'; END IF;
  IF NOT EXISTS (SELECT 1 FROM rt.activity_log WHERE issue_id={q(issue_id)} AND action='issue.status_changed' AND actor_type='system' AND actor_ref='endpoint-invocation:1109b0e0-511a-41e3-8442-58914ed3060a' AND details->>'from'='blocked' AND details->>'to'='cancelled' AND details->>'reason'='Endpoint Job execution did not complete successfully') THEN RAISE EXCEPTION 'missing erroneous Endpoint transition'; END IF;
  UPDATE rt.issues SET description={q(description)},status='blocked',version=version+1,updated_at=now(),resolved_at=NULL WHERE id={q(issue_id)};
  INSERT INTO rt.activity_log(id,tenant,namespace,issue_id,actor_type,actor_ref,action,object_type,object_ref,details)
  VALUES ({q(uuid.uuid4())},'default','default',{q(issue_id)},'system','repair:endpoint-input-20260907','issue.status_changed','issue',{q(issue_id)},{q(json.dumps({'from':'cancelled','to':'blocked','reason':reason,'recoveredInputFromRunId':run_id},ensure_ascii=False))}::jsonb);
  INSERT INTO rt.control_outbox(id,tenant,namespace,aggregate_type,aggregate_id,event_type,actor_type,actor_ref,payload,dedupe_key)
  VALUES ({q(uuid.uuid4())},'default','default','issue',{q(issue_id)},'issue.status-changed.v1','system','repair:endpoint-input-20260907',{q(json.dumps(payload,ensure_ascii=False))}::jsonb,'endpoint-input-repair:{issue_id}');
END $repair$;
COMMIT;
'''
(ROOT/'repair-history.sql').write_text(sql)
r=subprocess.run(['docker','exec','-i','agentscope-dev-pg','psql','-U','builder','-d','builder','-v','ON_ERROR_STOP=1'],input=sql,text=True,capture_output=True)
print(r.stdout,r.stderr); assert r.returncode==0
post=api('/api/v1/issues/'+issue_id+'/export',label='repair-after')
assert post['issue']['status']=='blocked' and run['input']['prompt'] in post['issue']['description']
assert [(t['id'],t['status']) for t in before['tasks']]==[(t['id'],t['status']) for t in post['tasks']]
print('Historical request and status repaired; Task history unchanged.')
