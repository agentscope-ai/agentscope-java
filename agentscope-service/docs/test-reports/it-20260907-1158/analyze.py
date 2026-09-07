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
  if e.get('runId')!=rid:findings.append({'kind':'wrong_event_run','eventId':e['id']})
  if e.get('agentTaskId') and e['agentTaskId'] not in d['tasks']:findings.append({'kind':'orphan_event_task','eventId':e['id']})
  if e.get('attemptId') and e['attemptId'] not in d['attempts']:findings.append({'kind':'orphan_event_attempt','eventId':e['id']})
  if e['type']=='agent_tool.failed' and not (e.get('payload') or {}).get('toolCallId'):findings.append({'kind':'missing_tool_call_id','runId':rid,'eventId':e['id']})
 for call,count in collections.Counter(e.get('payload',{}).get('toolCallId') for e in es if e['type']=='agent_tool.failed').items():
  if count>1:findings.append({'kind':'duplicate_tool_failure','runId':rid,'toolCallId':call,'count':count})
for iid,ex in d['issues'].items():
 for c in ex.get('children',[]):
  if c.get('parentIssueId')!=iid:findings.append({'kind':'wrong_issue_parent','issueId':c['id']})
 for c in ex.get('comments',[]):
  if c['issueId']!=iid:findings.append({'kind':'wrong_comment_issue','commentId':c['id']})
 for a in ex.get('activity',[]):
  if a['issueId']!=iid:findings.append({'kind':'wrong_activity_issue','activityId':a['id']})
for sid,v in d['sessions'].items():
 msgs=v.get('messages',{}).get('messages',[]);evs=v.get('events',{}).get('events',[])
 seq=[e['seq'] for e in evs]
 if seq!=sorted(set(seq)):findings.append({'kind':'bad_session_event_sequence','sessionRef':sid})
 errs=[x for x in msgs if x.get('role')=='tool' and str(x.get('toolOutput') or '').lstrip('"').startswith('Error:')]
 if errs:print('SESSION ERRORS',sid,[(x.get('toolName'),str(x.get('toolOutput'))[:140]) for x in errs])
 for e in evs:
  if e.get('eventType')=='agent.tool_result' and e.get('toolName')=='web_search' and 'TAVILY_API_KEY is not set' in str(e.get('toolOutput')):
   meta=e.get('frameworkMeta') or {}; findings.append({'kind':'web_error_reported_as_success' if meta.get('state')=='SUCCESS' else 'web_search_failure','sessionRef':sid,'seq':e['seq'],'toolCallId':meta.get('toolCallId'),'state':meta.get('state')})
print('COUNTS', {k:len(d[k]) for k in ['issues','runs','tasks','attempts','sessions']})
print('RUNS',collections.Counter(g['run']['state'] for g in d['runs'].values()))
print('TASKS',collections.Counter(t['status'] for t in d['tasks'].values()))
print('ATTEMPTS',collections.Counter(a['state'] for a in d['attempts'].values()))
print('FINDINGS',json.dumps(findings,ensure_ascii=False))
(ROOT/(name+'-checks.json')).write_text(json.dumps({'checks':findings,'counts':{k:len(d[k]) for k in ['issues','runs','tasks','attempts','sessions']}},ensure_ascii=False,indent=2))
