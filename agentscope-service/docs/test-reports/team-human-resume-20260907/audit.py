from probe import *
from collections import Counter
rootid='5e660a82-72da-4a31-8fed-bb7c037fc006'
runid='3258d720-64a6-4502-9bcb-278cff822f73'
root=api('/api/v1/issues/'+rootid+'/export',label='final-root')
graph=api('/api/v1/orchestration-runs/'+runid+'/graph',label='final-graph')
events=api('/api/v1/orchestration-runs/'+runid+'/events?limit=1000',label='final-events')['events']
inbox=api('/api/v1/inbox?tenant=default&namespace=default&limit=100',label='final-inbox')['items']
assert root['issue']['status'] in ('in_review','done')
accepted=[a for a in root['activity'] if a['action']=='issue.status_changed' and a.get('details',{}).get('to')=='done' and a.get('details',{}).get('from')=='in_review' and a['actor']['type']=='human']
if root['issue']['status']=='done': assert accepted
assert len(root['children'])==2 and all(c['status']=='done' for c in root['children'])
assert graph['run']['state']=='succeeded' and graph['run']['rootIssueId']==rootid
assert graph['run']['rerunOfRunId']=='900e100e-1206-4acf-8fa8-9777af13a182'
assert all(t['status']=='completed' for t in graph['tasks']) and all(a['state']=='succeeded' for a in graph['attempts'])
assert len(graph['tasks'])==len(graph['attempts'])==2
reviews=[i for i in inbox if i.get('issueId')==rootid and i['type']=='review_request' and i.get('needsAction') and not i.get('archived')]
assert len(reviews)==(1 if root['issue']['status']=='in_review' else 0)
new_summary=[c for c in root['comments'] if c.get('type')=='result' and c['content'].startswith('本次团队处理总结')][-1]
assert all(word in new_summary['content'] for word in ['微服务','新能源汽车'])
transitions=[a for a in root['activity'] if a['action']=='issue.status_changed' and a.get('details',{}).get('to')=='in_review']
assert datetime.datetime.fromisoformat(new_summary['createdAt'])<=min(datetime.datetime.fromisoformat(a['createdAt']) for a in transitions)
assert [e['sequence'] for e in events]==list(range(1,len(events)+1))
tasks={t['id']:t for t in graph['tasks']};attempts={a['id']:a for a in graph['attempts']}
for e in events:
 if e.get('agentTaskId'):assert e['agentTaskId'] in tasks
 if e.get('attemptId'):
  assert e['attemptId'] in attempts
  if e.get('agentTaskId'):assert attempts[e['attemptId']]['agentTaskId']==e['agentTaskId']
for t in graph['tasks']:
 task=api('/api/v1/agent-tasks/'+t['id'],label='final-task-'+t['id'])['task']
 assert all(i['state']=='processed' and i.get('responseCommentId') for i in (task.get('inputs') or []))
for a in graph['attempts']:
 assert a.get('sessionRef')
 api('/api/v1/execution-attempts/'+a['id'],label='final-attempt-'+a['id'])
 for kind in ['messages','events','turns']:
  api('/api/v1/sessions/'+a['sessionRef']+'/'+kind+'?limit=1000',label='final-'+kind+'-'+a['id']);assert api.last_status==200
for child in root['children']:api('/api/v1/issues/'+child['id']+'/export',label='final-child-'+child['id'])
old=api('/api/v1/orchestration-runs/900e100e-1206-4acf-8fa8-9777af13a182/graph',label='original-failed-run-retained')
assert old['run']['state']=='failed'
for id in ['068e2154-5021-436a-bcd6-49f4296f0bf1','63379801-b157-4fd8-a595-910a77815d01']:
 t=api('/api/v1/agent-tasks/'+id,label='original-completed-task-'+id)['task'];assert t['status']=='completed' and not t.get('teamId')
result={'rootIssueId':rootid,'status':root['issue']['status'],'humanAccepted':bool(accepted),'children':'2 done','continuationRun':runid,'tasks':'2 completed','attempts':'2 succeeded','eventCount':len(events),'eventTypes':dict(Counter(e['type'] for e in events)),'reviewInboxId':reviews[0]['id'] if reviews else None,'remainingReviewActions':len(reviews),'summaryPrecedesReview':True,'inputsProcessed':True,'oldHistoryPreserved':True}
(ROOT/'audit-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False,indent=2))
