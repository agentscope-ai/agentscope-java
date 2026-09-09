from probe import *
Q='?tenant=default&namespace=demo'
run=json.loads((ROOT/'live-state.json').read_text())['run']
d=api('/api/v1/issues/'+run['rootIssueId']+'/export'+Q,label='after-rerun')
g=next(x['graph'] for x in d['runDiagnostics'] if x['graph']['run']['id']==run['id'])
summary={'runId':run['id'],'issueStatus':d['issue']['status'],'runState':g['run']['state'],'nodes':[{k:n.get(k) for k in ['nodeKey','state','failureCode','output']} for n in g['nodes']],'tasks':[]}
for t in d.get('tasks',[]):
 if t['orchestrationRunId']!=run['id']:continue
 row={k:t.get(k) for k in ['id','status','errorCode','runNodeId']}
 if t.get('currentAttemptId'):
  a=api('/api/v1/execution-attempts/'+t['currentAttemptId']+Q,label='attempt-'+t['id'])['attempt'];row['attemptState']=a['state']
  if a.get('sessionRef'):
   es=api('/api/v1/sessions/'+a['sessionRef']+'/events'+Q+'&limit=1000',label='events-'+t['id']).get('events',[])
   row['tools']=[e.get('toolName') for e in es if e.get('eventType')=='agent.tool_use']
   for e in es:
    if e.get('eventType')=='agent.tool_result' and e.get('toolName')=='task.get':
     ctx=json.loads(e.get('toolOutput') or e.get('content'));row['workflow']=ctx.get('executionBrief',{}).get('workflow');row['nodeReturned']=bool(ctx.get('node'))
 summary['tasks'].append(row)
(ROOT/'audit.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print('issue',summary['issueStatus'],'run',summary['runState'])
for n in summary['nodes']:print('node',n['nodeKey'],n['state'],n.get('failureCode'),str(n.get('output',''))[:130])
for t in summary['tasks']:print('task',t['id'],t['status'],t.get('attemptState'),t.get('tools'),'predecessors',[(p['nodeKey'],p['state']) for p in (t.get('workflow') or {}).get('predecessors',[])])
