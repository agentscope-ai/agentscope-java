from probe import ROOT,scrub
import json,collections,sys
name=sys.argv[1] if len(sys.argv)>1 else 'audit1'
d=json.loads((ROOT/(name+'-inventory.json')).read_text());findings=[]
terminal={'completed','failed','cancelled'};runterminal={'succeeded','failed','cancelled'}
for rid,g in d['runs'].items():
 tasks=g.get('tasks',[]);attempts=g.get('attempts',[]);run=g['run'];es=d['runEvents'][rid].get('events',[])
 if run['state'] not in runterminal and tasks and all(t['status'] in terminal for t in tasks):findings.append({'kind':'quiescent_run','runId':rid,'state':run['state'],'tasks':len(tasks)})
 for t in tasks:
  if t['orchestrationRunId']!=rid:findings.append({'kind':'wrong_task_run','taskId':t['id']})
  if run['state'] in runterminal and t['status'] not in terminal:findings.append({'kind':'active_task_in_terminal_run','taskId':t['id']})
  if t['status'] in terminal:
   pending=[i for i in t.get('inputs',[]) if i['state'] not in ['processed','deferred','dead_letter','blocked']]
   if pending:findings.append({'kind':'unsettled_inputs','taskId':t['id'],'inputs':pending})
 for a in attempts:
  if a['agentTaskId'] not in d['tasks']:findings.append({'kind':'orphan_attempt','attemptId':a['id']})
  if run['state'] in runterminal and a['state'] not in ['succeeded','failed','cancelled']:findings.append({'kind':'active_attempt_in_terminal_run','attemptId':a['id']})
  task=d['tasks'].get(a['agentTaskId'],{})
  expected={'completed':'succeeded','failed':'failed','cancelled':'cancelled'}.get(task.get('status'))
  if task.get('currentAttemptId')==a['id'] and expected and a['state']!=expected:findings.append({'kind':'task_attempt_disagree','taskId':task['id'],'attemptId':a['id'],'task':task['status'],'attempt':a['state']})
 seq=[e['sequence'] for e in es]
 if seq!=sorted(set(seq)):findings.append({'kind':'bad_event_sequence','runId':rid})
 for e in es:
  if e['type']=='agent_tool.failed' and not (e.get('payload') or {}).get('toolCallId'):findings.append({'kind':'missing_tool_call_id','runId':rid,'eventId':e['id']})
for sid,v in d['sessions'].items():
 msgs=v.get('messages',{}).get('messages',[]);evs=v.get('events',{}).get('events',[])
 errs=[x for x in msgs if x.get('role')=='tool' and ('Error' in str(x.get('toolOutput')) or 'error' in str(x.get('toolOutput')))]
 if errs:print('SESSION ERRORS',sid,[(x.get('toolName'),str(x.get('toolOutput'))[:140]) for x in errs])
print('COUNTS', {k:len(d[k]) for k in ['issues','runs','tasks','attempts','sessions']})
print('RUNS',collections.Counter(g['run']['state'] for g in d['runs'].values()))
print('TASKS',collections.Counter(t['status'] for t in d['tasks'].values()))
print('ATTEMPTS',collections.Counter(a['state'] for a in d['attempts'].values()))
print('FINDINGS',json.dumps(findings,ensure_ascii=False))
(ROOT/(name+'-checks.json')).write_text(json.dumps({'checks':findings,'counts':{k:len(d[k]) for k in ['issues','runs','tasks','attempts','sessions']}},ensure_ascii=False,indent=2))
