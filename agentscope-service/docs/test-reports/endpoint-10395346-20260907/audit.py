from probe import *
from collections import Counter
results=[]
for label,statefile,expected in [('success','retest2-state.json','succeeded'),('missing-tool','retest2-negative-state.json','failed')]:
 state=json.loads((ROOT/statefile).read_text());inv=state.get('invocation',state)
 root=api('/api/v1/issues/'+inv['issueId']+'/export',label='final-'+label+'-issue')
 graph=api('/api/v1/orchestration-runs/'+inv['runId']+'/graph',label='final-'+label+'-graph')
 ev=api('/api/v1/orchestration-runs/'+inv['runId']+'/events?limit=1000',label='final-'+label+'-events')
 assert graph['run']['state']==expected
 assert root['issue']['status']==('done' if expected=='succeeded' else 'blocked')
 assert '\\u5e2e' not in root['issue']['description']
 tasks={t['id']:t for t in graph['tasks']}; attempts={a['id']:a for a in graph['attempts']}
 for task in tasks.values():
  assert task['status'] in ['completed','failed']
  detail=api('/api/v1/agent-tasks/'+task['id'],label='final-task-'+task['id'])
  assert detail['task']['status']==task['status']
 for a in attempts.values():
  assert a['agentTaskId'] in tasks and a['state'] in ['succeeded','failed'] and a.get('sessionRef')
  api('/api/v1/execution-attempts/'+a['id'],label='final-attempt-'+a['id'])
  messages=api('/api/v1/sessions/'+a['sessionRef']+'/messages?limit=1000',label='final-messages-'+a['id'])
  assert api.last_status==200
  contexts=[]
  for message in messages['messages']:
   if message.get('role')!='tool': continue
   try: context=json.loads(message['content'])
   except (ValueError,TypeError): continue
   if isinstance(context,dict) and 'currentRequest' in context: contexts.append(context)
  assert contexts, 'missing actual task.get evidence'
  task=tasks[a['agentTaskId']]
  if task['issueId']==inv['issueId']:
   assert graph['run']['input']['prompt'] in contexts[0]['currentRequest']
  (ROOT/'evidence'/('final-delivered-context-'+task['id']+'.json')).write_text(json.dumps(scrub(contexts),ensure_ascii=False,indent=2))
  api('/api/v1/sessions/'+a['sessionRef']+'/events?limit=1000',label='final-session-events-'+a['id'])
  api('/api/v1/sessions/'+a['sessionRef']+'/turns?limit=1000',label='final-turns-'+a['id'])
 for child in root['children']:
  c=api('/api/v1/issues/'+child['id']+'/export',label='final-child-'+child['id']); assert c['issue']['status'] in ['done','blocked','cancelled']
 events=ev['events']; seq=[e['sequence'] for e in events]; assert seq==list(range(1,len(seq)+1))
 assert not any('cancel' in e['type'] for e in events)
 for event in events:
  if event.get('agentTaskId'): assert event['agentTaskId'] in tasks
  if event.get('attemptId'):
   assert event['attemptId'] in attempts
   if event.get('agentTaskId'): assert attempts[event['attemptId']]['agentTaskId']==event['agentTaskId']
 summaries=[c for c in root['comments'] if c.get('content','').startswith('本次团队处理总结')]
 assert len(summaries)==1
 summary=summaries[0]
 transitions=[a for a in root['activity'] if a['action']=='issue.status_changed' and a.get('details',{}).get('to')==root['issue']['status']]
 assert transitions and datetime.datetime.fromisoformat(summary['createdAt'])<=min(datetime.datetime.fromisoformat(a['createdAt']) for a in transitions)
 if expected=='succeeded':
  apiresult=json.loads((ROOT/'evidence/retest2-api-result.json').read_text())['response']['invocation']['result']
  assert 'In a world of code and bytes' in apiresult and 'In a world of code and bytes' in summary['content']
  assert any(e['type']=='coordinator.waiting' for e in events)
 else:
  assert 'TOOL_MISSING' in summary['content']
 results.append({'case':label,'issueId':inv['issueId'],'runId':inv['runId'],'issueStatus':root['issue']['status'],'runState':graph['run']['state'],'tasks':dict(Counter(t['status'] for t in tasks.values())),'attempts':dict(Counter(a['state'] for a in attempts.values())),'events':len(events),'eventTypes':dict(Counter(e['type'] for e in events)),'children':len(root['children']),'summaryPrecedesFinalStatus':True})
(ROOT/'audit-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print(json.dumps(results,ensure_ascii=False,indent=2))
