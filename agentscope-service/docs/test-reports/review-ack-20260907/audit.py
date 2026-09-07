from probe import *
from collections import Counter
s=json.loads((ROOT/'live-state.json').read_text());results={}
for name,iid in s.items():
 before=json.loads((ROOT/'evidence'/('before-'+name+'.json')).read_text())['response']
 x=api('/api/v1/issues/'+iid+'/export',label='final-'+name)
 assert x['issue']['status']==('done' if name=='accepted' else 'in_review'),(name,x['issue']['status'])
 assert len(x['runs'])==1,(name,x['runs'])
 r=x['runs'][0];g=api('/api/v1/orchestration-runs/'+r['id']+'/graph',label='final-graph-'+name)
 ev=api('/api/v1/orchestration-runs/'+r['id']+'/events?limit=1000',label='final-events-'+name)['events']
 assert r['state']=='succeeded',(name,r['state'])
 assert [e['sequence'] for e in ev]==list(range(1,len(ev)+1))
 errors=[e for e in ev if e['type']=='agent_tool.failed']
 if name!='change': assert not errors,(name,errors)
 else:
  assert len(errors)<=1 and all(e['payload'].get('toolName')=='run.node.complete' and 'active child issue' in e['payload'].get('message','') for e in errors),errors
  if errors: assert any(e['type']=='coordinator.waiting' for e in ev), 'premature completion did not yield'
 assert all(t['status']=='completed' for t in g['tasks']) and all(a['state']=='succeeded' for a in g['attempts'])
 assert len(g['tasks'])==len(g['attempts'])
 assert {c['id'] for c in before['children']} <= {c['id'] for c in x['children']}
 assert all(c['status']=='done' for c in x['children'])
 delivery=[c for c in x['comments'] if c['type']=='result']
 if name!='change':
  assert len(x['children'])==2 and len(g['tasks'])==1
  assert r['triggerType']=='review_comment' and g['tasks'][0]['triggerType']=='review_comment'
  assert len(delivery)==1 and delivery[0]['content']=='测试预置交付结果：A=42，B=42，合计=84。'
  assert not [a for a in x.get('activity',[]) if a['action']=='issue.status_changed']
  replies=[c for c in x['comments'] if c.get('sourceTaskId')==g['tasks'][0]['id']]
  assert len(replies)==1 and replies[0]['type']=='comment'
 else:
  assert len(x['children'])==3 and len(g['tasks'])==3
  assert len([e for e in ev if e['type']=='review.work_requested'])==1
  assert '56' in delivery[-1]['content'] and '84' in delivery[-1]['content']
  transitions=[a for a in x['activity'] if a['action']=='issue.status_changed']
  assert [(a['details']['from'],a['details']['to']) for a in transitions]==[('in_review','in_progress'),('in_progress','in_review')]
  assert datetime.datetime.fromisoformat(delivery[-1]['createdAt'])<=datetime.datetime.fromisoformat(transitions[-1]['createdAt'])
 for task in g['tasks']:
  t=api('/api/v1/agent-tasks/'+task['id'],label='final-task-'+task['id'])['task']
  assert all(i['state']=='processed' and i.get('responseCommentId') for i in t.get('inputs',[]))
 for a in g['attempts']:
  api('/api/v1/execution-attempts/'+a['id'],label='final-attempt-'+a['id'])
  for kind in ['messages','events','turns']:
   api('/api/v1/sessions/'+a['sessionRef']+'/'+kind+'?limit=1000',label='final-'+kind+'-'+a['id']);assert api.last_status==200
 results[name]={'issueId':iid,'status':x['issue']['status'],'children':len(x['children']),'runId':r['id'],'runState':r['state'],'tasksCompleted':len(g['tasks']),'attemptsSucceeded':len(g['attempts']),'toolErrors':len(errors),'eventTypes':dict(Counter(e['type'] for e in ev))}
 print(name,'PASS',results[name])
inbox=api('/api/v1/inbox?tenant=default&namespace=default&limit=500',label='final-inbox')['items']
for name,result in results.items():
 items=[i for i in inbox if i.get('issueId')==result['issueId'] and i['type']=='review_request' and i.get('needsAction') and not i.get('archived')]
 if name=='change': assert len(items)==1
 if name=='accepted': assert not items
 result['pendingReviews']=len(items)
(ROOT/'audit-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
