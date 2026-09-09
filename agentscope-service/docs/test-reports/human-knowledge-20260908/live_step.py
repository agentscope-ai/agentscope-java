from probe import *
NS='personal-8c6976e5b5410415bde9';Q='?tenant=default&namespace='+NS
state=json.loads((ROOT/'live-state.json').read_text())
d=api('/api/v1/issues/'+state['root']+'/export'+Q,label='live-root')
print('root',d.get('issue',{}).get('status'))
state['children']=[c['id'] for c in d.get('children',[])]
for cid in state['children']:
 c=api('/api/v1/issues/'+cid+'/export'+Q,label='live-child-'+cid)
 print(c['issue']['title'],c['issue']['status'],[(t['triggerType'],t['status']) for t in c.get('tasks',[])])
 if c['issue']['status']=='blocked' and not any(x['author']['type']=='human' for x in c.get('comments',[])):
  x=api('/api/v1/issues/'+cid+'/comments'+Q,{'content':'你直接根据自己的认知回答就好了'},label='live-human-'+cid)
  assert api.last_status in (200,201),x
  print('submitted same human revision',cid)
(ROOT/'live-state.json').write_text(json.dumps(state,indent=2))
