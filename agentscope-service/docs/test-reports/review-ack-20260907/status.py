from probe import *
s=json.loads((ROOT/'live-state.json').read_text())
for name,iid in s.items():
 x=api('/api/v1/issues/'+iid+'/export',label='progress-'+name)
 print(name,x['issue']['status'],'children',len(x['children']))
 for r in x.get('runs',[]):
  g=api('/api/v1/orchestration-runs/'+r['id']+'/graph',label='graph-'+name);print(r['id'],r['triggerType'],r['state'],[(t['status'],t.get('triggerType')) for t in g['tasks']])
