from probe import *
from collections import Counter
s=json.loads((ROOT/'fresh-state.json').read_text());rootid=s['issueId']
root=api('/api/v1/issues/'+rootid+'/export',label='fresh-final-root')
assert root['issue']['status']=='in_review',root['issue']['status']
assert len(root['children'])==2 and all(c['status']=='done' for c in root['children'])
g=api('/api/v1/orchestration-runs/'+s['initialRunId']+'/graph',label='fresh-final-graph')
assert g['run']['state']=='partial_succeeded' and g['run']['rootIssueId']==rootid
comments=[json.loads((ROOT/'evidence'/('fresh-human-'+name+'.json')).read_text())['response'] for name in ['A','B']]
ids=[x.get('Comment',x.get('comment',{}))['id'] for x in comments]
resumed=[t for t in g['tasks'] if t.get('triggerCommentId') in ids]
assert len(resumed)==2 and all(t['status']=='completed' and t.get('teamId')=='22ce6ec1-fdf5-4393-a9f0-43abff828377' and t.get('parentTaskId') for t in resumed)
resumedids={t['id'] for t in resumed}
leaders=[]
for child in root['children']:
 ex=api('/api/v1/issues/'+child['id']+'/export',label='fresh-final-child-'+child['id'])
 for c in ex['comments']:
  if c.get('sourceTaskId') in resumedids and c['type']=='result':
   for route in c.get('routes',[]):
    if route.get('taskId'):leaders.append(route['taskId'])
assert len(leaders)==2
allids=resumedids|set(leaders)
selected=[t for t in g['tasks'] if t['id'] in allids];assert len(selected)==4
assert all(t['status']=='completed' for t in selected)
assert len({t['runNodeId'] for t in selected if t['id'] in leaders})==1
attempts=[a for a in g['attempts'] if a['agentTaskId'] in allids]
assert len(attempts)==4 and all(a['state']=='succeeded' for a in attempts)
events=api('/api/v1/orchestration-runs/'+s['initialRunId']+'/events?limit=1000',label='fresh-final-events')['events']
assert [e['sequence'] for e in events]==list(range(1,len(events)+1))
errors=[e for e in events if e['type']=='agent_tool.failed' and e.get('agentTaskId') in allids];assert not errors,errors
waits=[e for e in events if e['type']=='coordinator.waiting' and e.get('agentTaskId') in leaders];assert len(waits)==1
for t in selected:
 item=api('/api/v1/agent-tasks/'+t['id'],label='fresh-final-task-'+t['id'])['task']
 assert all(i['state']=='processed' and i.get('responseCommentId') for i in item.get('inputs',[]))
for a in attempts:
 api('/api/v1/execution-attempts/'+a['id'],label='fresh-final-attempt-'+a['id'])
 for kind in ['messages','events','turns']:
  api('/api/v1/sessions/'+a['sessionRef']+'/'+kind+'?limit=1000',label='fresh-final-'+kind+'-'+a['id']);assert api.last_status==200
summary=[c for c in root['comments'] if c['type']=='result'][-1]
assert all(v in summary['content'] for v in ['42','84'])
transitions=[a for a in root['activity'] if a['action']=='issue.status_changed' and a.get('details',{}).get('to')=='in_review']
assert datetime.datetime.fromisoformat(summary['createdAt'])<=min(datetime.datetime.fromisoformat(a['createdAt']) for a in transitions)
inbox=api('/api/v1/inbox?tenant=default&namespace=default&limit=100',label='fresh-final-inbox')['items']
reviews=[i for i in inbox if i.get('issueId')==rootid and i['type']=='review_request' and i.get('needsAction') and not i.get('archived')];assert len(reviews)==1
result={'rootIssueId':rootid,'runId':s['initialRunId'],'initialRunStateBeforeHumanInput':s['initialRunState'],'rootStatus':'in_review','runState':g['run']['state'],'rootStatusReconciledAfterFix':True,'children':'2 done','resumedTasks':'2 workers + 2 leaders completed','resumedAttempts':'4 succeeded','resumedToolErrors':0,'coordinatorWaitingAfterChildAcceptance':len(waits),'result':'A=42 B=42 sum=84','summaryPrecedesReview':True,'inputsProcessed':True,'reviewInboxId':reviews[0]['id'],'initialPhaseToolErrorsRetained':sum(e['type']=='agent_tool.failed' for e in events)}
(ROOT/'fresh-audit-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False,indent=2))
