from probe import *
s=json.loads((ROOT/'state.json').read_text());tag=sys.argv[1] if len(sys.argv)>1 else 'final';keys=json.loads(pathlib.Path('/tmp/agentscope-it-20260907-fix2-endpoint-keys.json').read_text());agentids=set(s['agents'].values())
inv={'at':datetime.datetime.now().astimezone().isoformat(),'scenarios':{},'issues':{},'runs':{},'tasks':{},'attempts':{},'runEvents':{},'sessions':{},'public':{}}
attempts=api('/api/v1/execution-attempts?tenant=default&namespace=default&limit=1000',label=tag+'-attempts')['attempts'];attempts=[a for a in attempts if a['agentId'] in agentids]
roots={name:v['issue']['id'] for name,v in s['issues'].items()}
for name,v in s['invocations'].items():
 r=v['response'];ep=v['endpoint'];h={'X-API-Key':keys[ep]}
 if r.get('issueId'):
  roots['api-'+name]=r['issueId'];path='/invoke/v1/jobs/'+r['invocationId']
 else:
  path='/invoke/v1/conversations/'+r['conversationId']
  for a in attempts:
   if a.get('sessionId')==r.get('sessionId'):
    t=api('/api/v1/agent-tasks/'+a['agentTaskId'],label=tag+'-chat-task-'+a['agentTaskId'])['task'];roots['chat-internal-'+a['agentTaskId']]=t['issueId']
 inv['public'][name]=api(path,headers=h,label=tag+'-public-'+name)
 if r.get('sessionRef'):inv['sessions'][r['sessionRef']]={}
def collect_issue(iid):
 if iid in inv['issues']:return
 ex=api('/api/v1/issues/'+iid+'/export',label=tag+'-issue-'+iid+'-export');inv['issues'][iid]=ex
 for c in ex.get('children',[]):collect_issue(c['id'])
 for r in ex.get('runs',[]):
  rid=r['id']
  if rid in inv['runs']:continue
  g=api('/api/v1/orchestration-runs/'+rid+'/graph',label=tag+'-run-'+rid+'-graph');inv['runs'][rid]=g
  ev=api('/api/v1/orchestration-runs/'+rid+'/events?limit=1000',label=tag+'-run-'+rid+'-events');inv['runEvents'][rid]=ev
  for t in g.get('tasks',[]):
   inv['tasks'][t['id']]=api('/api/v1/agent-tasks/'+t['id'],label=tag+'-task-'+t['id']).get('task',t)
  for a in g.get('attempts',[]):
   inv['attempts'][a['id']]=api('/api/v1/execution-attempts/'+a['id'],label=tag+'-attempt-'+a['id']).get('attempt',a)
   if a.get('sessionRef'):inv['sessions'][a['sessionRef']]={}
for name,iid in roots.items():
 collect_issue(iid);inv['scenarios'][name]=iid
for sid in inv['sessions']:
 for suffix in ['events','messages','turns','tasks']:
  inv['sessions'][sid][suffix]=api('/api/v1/sessions/'+sid+'/'+suffix+'?limit=1000',label=tag+'-session-'+sid+'-'+suffix)
for role,aid in s['agents'].items():api('/api/v1/agents/'+aid+'/overview',label=tag+'-agent-'+role+'-overview')
for name,ep in s['endpoints'].items():api('/api/v1/endpoints/'+ep['id']+'/invocations',label=tag+'-endpoint-'+name+'-invocations')
inv['hosts']=api('/api/v1/runtime-hosts',label=tag+'-hosts')
(ROOT/(tag+'-inventory.json')).write_text(json.dumps(scrub(inv),ensure_ascii=False,indent=2))
print('collected', {k:len(inv[k]) for k in ['issues','runs','tasks','attempts','sessions']})
for name,iid in roots.items():
 ex=inv['issues'][iid];print(name,ex['issue']['status'],[(r['state'],r['id']) for r in ex.get('runs',[])])
