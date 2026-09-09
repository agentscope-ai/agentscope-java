from probe import *
Q='?tenant=default&namespace=personal-8c6976e5b5410415bde9'
state=json.loads((ROOT/'live-state.json').read_text());result=[]
for name,iid in [('root',state['root']),*[(cid,cid) for cid in state['children']],('negative',state['negative'])]:
 d=api('/api/v1/issues/'+iid+'/export'+Q,label='audit-'+name)
 row={'name':name,'issueId':iid,'status':d['issue']['status'],'tasks':[]}
 for t in d.get('tasks',[]):
  tr={k:t.get(k) for k in ['id','triggerType','status','leaderTask','errorCode']}
  if t.get('currentAttemptId'):
   a=api('/api/v1/execution-attempts/'+t['currentAttemptId']+Q,label='attempt-'+t['id'])['attempt'];tr['attemptState']=a['state']
   if a.get('sessionRef'):
    e=api('/api/v1/sessions/'+a['sessionRef']+'/events'+Q+'&limit=1000',label='events-'+t['id']).get('events',[])
    tr['sessionRef']=a['sessionRef'];tr['tools']=[x.get('toolName') for x in e if x.get('eventType')=='agent.tool_use']
    tr['wakeHasBrief']=bool(e and 'CURRENT EXECUTION BRIEF' in e[0].get('content',''))
    for x in e:
     if x.get('toolName')=='task.get' and x.get('eventType')=='agent.tool_result':
      try:
       c=json.loads(x.get('toolOutput') or x.get('content',''));tr['humanRevisions']=c.get('executionBrief',{}).get('humanRevisions',[])
      except ValueError:pass
  row['tasks'].append(tr)
 result.append(row)
(ROOT/'audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
for row in result:
 print(row['name'],row['status'])
 for t in row['tasks']: print(' ',t['triggerType'],t['status'],t.get('attemptState'), 'leader' if t.get('leaderTask') else 'worker',t.get('tools'), 'human revisions',len(t.get('humanRevisions',[])))
