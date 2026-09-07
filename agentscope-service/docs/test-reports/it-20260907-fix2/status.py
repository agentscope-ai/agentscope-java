from probe import *
s=json.loads((ROOT/'state.json').read_text());keys=json.loads(pathlib.Path('/tmp/agentscope-it-20260907-fix2-endpoint-keys.json').read_text());tag=sys.argv[1] if len(sys.argv)>1 else datetime.datetime.now().strftime('%H%M%S');summary={}
items={name:v['issue']['id'] for name,v in s['issues'].items()}
for name,v in s['invocations'].items():
 r=v['response'];ep=v['endpoint'];h={'X-API-Key':keys[ep]}
 if r.get('issueId'):items['api-'+name]=r['issueId']
 path='/invoke/v1/jobs/'+r['invocationId'] if r.get('issueId') else '/invoke/v1/conversations/'+r['conversationId']
 x=api(path,headers=h,label=tag+'-public-'+name)
 print('PUBLIC',name,json.dumps(x,ensure_ascii=False)[:420])
 if r.get('sessionRef'):
  turns=api('/api/v1/sessions/'+r['sessionRef']+'/turns',label=tag+'-'+name+'-turns');msgs=api('/api/v1/sessions/'+r['sessionRef']+'/messages',label=tag+'-'+name+'-messages')
  print(' CHAT',json.dumps(turns)[:140],json.dumps(msgs,ensure_ascii=False)[-260:])
for name,iid in items.items():
 ex=api('/api/v1/issues/'+iid+'/export',label=tag+'-'+name+'-export');graphs=[]
 for r in ex.get('runs',[]):
  g=api('/api/v1/orchestration-runs/'+r['id']+'/graph',label=tag+'-'+name+'-'+r['id']+'-graph');graphs.append(g)
 info={'issueId':iid,'issueStatus':ex.get('issue',{}).get('status'),'runs':[{'id':g['run']['id'],'state':g['run']['state'],'failure':g['run'].get('failureMessage'),'tasks':[(t['agentId'][:6],t['status'],str(t.get('result') or t.get('errorMessage') or '')[:140]) for t in g.get('tasks',[])]} for g in graphs]}
 summary[name]=info;print(name,json.dumps(info,ensure_ascii=False))
(ROOT/'latest-status.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
