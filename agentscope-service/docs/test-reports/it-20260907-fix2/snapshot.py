from probe import *
s=json.loads((ROOT/'state.json').read_text());tag=sys.argv[1] if len(sys.argv)>1 else datetime.datetime.now().strftime('%H%M%S')
for name,initial in s['issues'].items():
 iid=initial['issue']['id'];rid=(initial.get('agentTask') or {}).get('orchestrationRunId')
 issue=api('/api/v1/issues/'+iid,label=tag+'-'+name+'-issue')
 comments=api('/api/v1/issues/'+iid+'/comments',label=tag+'-'+name+'-comments')
 print(name,'issue',issue.get('issue',{}).get('status'),'comments',len(comments.get('items',[])))
 if rid:
  graph=api('/api/v1/orchestration-runs/'+rid+'/graph',label=tag+'-'+name+'-graph')
  api('/api/v1/orchestration-runs/'+rid+'/events?limit=500',label=tag+'-'+name+'-events')
  print(' run',graph.get('run',{}).get('state'),graph.get('run',{}).get('failureMessage'),'tasks',[(t['id'][:8],t['status'],str(t.get('result',t.get('errorMessage','')))[:250]) for t in graph.get('tasks',[])])
  for a in graph.get('attempts',[]):
   print(' attempt',a['id'][:8],a['state'],a.get('failureMessage'),a.get('sessionRef'))
   if a.get('sessionRef'):
    for suffix in ['events','messages']:
     api('/api/v1/sessions/'+a['sessionRef']+'/'+suffix,label=tag+'-'+name+'-'+a['id']+'-'+suffix)
