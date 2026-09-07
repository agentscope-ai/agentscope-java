from probe import *
s=json.loads((ROOT/'state.json').read_text())
x=api('/api/v1/issues/'+s['issue']+'/export',label='live-latest')
print('ROOT',x['issue']['status'], 'children',len(x.get('children',[])))
for r in x.get('runs',[]):
 s['run']=r['id'];print('RUN',r['id'],r['state'],r.get('failureCode'),r.get('failureMessage'))
 g=api('/api/v1/orchestration-runs/'+r['id']+'/graph',label='live-graph')
 print('TASKS',[(t['id'][:8],t.get('agentId'),t['status'],t.get('result'),t.get('errorMessage')) for t in g.get('tasks',[])])
for c in x.get('children',[]):
 y=api('/api/v1/issues/'+c['id']+'/export',label='child-'+c['id']);print('CHILD',c['title'],c['status'],[(z['type'],z['content'][:220]) for z in y.get('comments',[])])
(ROOT/'state.json').write_text(json.dumps(s,indent=2))
