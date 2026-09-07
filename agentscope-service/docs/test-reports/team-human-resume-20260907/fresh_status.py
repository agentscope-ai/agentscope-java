import json
from probe import api,ROOT
s=json.loads((ROOT/'fresh-state.json').read_text())
x=api('/api/v1/issues/'+s['issueId']+'/export',label='fresh-progress')
print('ROOT',x['issue']['status'])
print('CHILDREN',[(c['id'],c['title'],c['status']) for c in x.get('children',[])])
for r in x.get('runs',[]):
 g=api('/api/v1/orchestration-runs/'+r['id']+'/graph',label='fresh-graph-'+r['id'])
 ev=api('/api/v1/orchestration-runs/'+r['id']+'/events?limit=1000',label='fresh-events-'+r['id'])
 print('RUN',r['id'],r['state'])
 print('TASKS',[(t['id'],t.get('leaderTask',False),t['status'],t.get('errorCode')) for t in g.get('tasks',[])])
 errors=[e for e in ev.get('events',[]) if e['type']=='agent_tool.failed']
 print('ERRORS',len(errors),'LAST',errors[-1].get('payload') if errors else None)
for c in x.get('comments',[]):
 if c['type']=='result': print('SUMMARY',c['content'][:500])
