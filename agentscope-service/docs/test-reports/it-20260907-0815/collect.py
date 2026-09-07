from probe import *
s=json.loads((ROOT/'state.json').read_text());inv={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'agents':s['agents'],'teams':s['teams'],'scenarios':{},'chats':{},'counts':{}}
alltasks={};allattempts={};allissues={};allruns={}
for name,start in s['issues'].items():
 iid=start['issue']['id'];ex=api('/api/v1/issues/'+iid+'/export',label='final-'+name+'-export')
 allissues[iid]=ex.get('issue',{}); scen={'issueId':iid,'issueStatus':ex.get('issue',{}).get('status'),'children':ex.get('children',[]),'runs':[]}
 for c in ex.get('children',[]):
  allissues[c['id']]=c
  api('/api/v1/issues/'+c['id']+'/export',label='final-child-'+c['id']+'-export')
 for r in ex.get('runs',[]):
  rid=r['id'];g=api('/api/v1/orchestration-runs/'+rid+'/graph',label='final-'+name+'-'+rid+'-graph')
  ev=api('/api/v1/orchestration-runs/'+rid+'/events?limit=500',label='final-'+name+'-'+rid+'-events')
  allruns[rid]=g.get('run',r);scen['runs'].append(g)
  for t in g.get('tasks',[]):alltasks[t['id']]=t
  for a in g.get('attempts',[]):
   allattempts[a['id']]=a
   if a.get('sessionRef'):
    for suf in ['messages','events']:
     api('/api/v1/sessions/'+a['sessionRef']+'/'+suf+'?limit=500',label='final-session-'+a['sessionRef']+'-'+suf)
 inv['scenarios'][name]=scen
 print(name,scen['issueStatus'],[(g['run']['state'],len(g['tasks']),[(t['id'][:8],t['status']) for t in g['tasks']]) for g in scen['runs']])
for role,c in s['chats'].items():
 chat=api('/api/v1/chats/'+c['chat']['id'],label='final-chat-'+role);sid=chat['chat']['sessionId'];inv['chats'][role]=chat['chat']
 for suf in ['messages','events','turns','tasks']:
  api('/api/v1/sessions/'+sid+'/'+suf,label='final-chat-'+role+'-'+suf)
for role,aid in s['agents'].items():
 api('/api/v1/agents/'+aid+'/overview',label='final-overview-'+role)
for name,tid in s['teams'].items():
 api('/api/v1/teams/'+tid+'/overview',label='final-team-'+name+'-overview')
inv['host']=api('/api/v1/runtime-hosts',label='final-host')
inv['allIssues']=list(allissues.values());inv['allTasks']=list(alltasks.values());inv['allAttempts']=list(allattempts.values());inv['allRuns']=list(allruns.values())
inv['counts']={k:len(v) for k,v in [('issues',allissues),('tasks',alltasks),('attempts',allattempts),('runs',allruns)]}
(ROOT/'inventory.json').write_text(json.dumps(scrub(inv),ensure_ascii=False,indent=2));print(inv['counts'])
