from probe import *
s=json.loads((ROOT/'state.json').read_text())
x=api('/api/v1/issues/'+s['issue']+'/export',label='final-root')
g=api('/api/v1/orchestration-runs/'+s['run']+'/graph',label='final-graph')
e=api('/api/v1/orchestration-runs/'+s['run']+'/events?limit=1000',label='final-run-events')
assert x['issue']['status']=='in_review'
assert len(x['children'])==1 and x['children'][0]['status']=='done'
assert g['run']['state']=='succeeded'
assert len(g['tasks'])==5 and all(t['status']=='completed' for t in g['tasks'])
assert len(g['attempts'])==5 and all(a['state']=='succeeded' for a in g['attempts'])
assert all(n['state']=='succeeded' for n in g['nodes'])
for t in g['tasks']:
 y=api('/api/v1/agent-tasks/'+t['id'],label='final-task-'+t['id'])
 assert all(i['state']=='processed' for i in y['task'].get('inputs',[]))
for a in g['attempts']:
 api('/api/v1/execution-attempts/'+a['id'],label='final-attempt-'+a['id'])
 if a.get('sessionRef'):
  for kind in ['events','messages','turns']:
   api('/api/v1/sessions/'+a['sessionRef']+'/'+kind+'?limit=1000',label='session-'+a['sessionRef']+'-'+kind)
items=e.get('items',e.get('events',[]))
assert len([v for v in items if v['type']=='coordinator.waiting'])==2,(e.keys(),len(items))
assert not [v for v in items if v['type'].endswith('.failed') or v['type'].endswith('.cancelled')]
assert any('FOLLOWUP_OK_144' in c['content'] and '子任务结果' in c['content'] for c in x['comments'])
r={'status':'PASS','issueId':s['issue'],'runId':s['run'],'rootState':x['issue']['status'],'childState':'done','runState':'succeeded','tasks':5,'attempts':5,'nodes':len(g['nodes']),'waitingEvents':2,'failedOrCancelledEvents':0,'allInputsProcessed':True,'rootSummary':True}
(ROOT/'verification.json').write_text(json.dumps(r,ensure_ascii=False,indent=2));print(json.dumps(r,ensure_ascii=False))
