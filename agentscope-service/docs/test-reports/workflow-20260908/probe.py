"""Workflow acceptance against the local service. Creates only wf-acceptance fixtures.
Run `python3 probe.py init`, then `python3 probe.py poll` to inspect durable results.
Uses the local development admin account; override WF_TEST_PASSWORD if configured.
"""
import urllib.request,urllib.error,json,pathlib,os,sys,datetime,re,uuid,base64
ROOT=pathlib.Path(__file__).resolve().parent
BASE=os.environ.get('WF_TEST_BASE','http://localhost:18080')
login=json.load(urllib.request.urlopen(urllib.request.Request(BASE+'/api/auth/login',json.dumps({'username':'admin','password':os.environ.get('WF_TEST_PASSWORD','admin')}).encode(),{'Content-Type':'application/json'}),timeout=20))
TOKEN=login['token']
def scrub(x):
 if isinstance(x,dict):return {k:'[REDACTED]' if re.search(r'token|password|secret|credential|authorization',k,re.I) else scrub(v) for k,v in x.items()}
 if isinstance(x,list):return [scrub(v) for v in x]
 if isinstance(x,str):return re.sub(r'eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+','[REDACTED]',x)
 return x
def api(path,body=None,method=None,expected=(200,201,202,204)):
 request=urllib.request.Request(BASE+path,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'},method=method or ('GET' if body is None else 'POST'))
 try:
  with urllib.request.urlopen(request,timeout=50) as response:code=response.status;raw=response.read()
 except urllib.error.HTTPError as error:code=error.code;raw=error.read()
 data=json.loads(raw) if raw else None
 with (ROOT/'evidence/http.jsonl').open('a') as f:f.write(json.dumps(scrub({'at':datetime.datetime.now().isoformat(),'path':path,'method':request.method,'request':body,'status':code,'response':data}),ensure_ascii=False)+'\n')
 if code not in expected:raise RuntimeError(f'{request.method} {path}: {code}: {scrub(data)}')
 return data
statefile=ROOT/'evidence/state.json'
state=json.loads(statefile.read_text()) if statefile.exists() else {'prefix':'wf-acceptance-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'),'definitions':{},'runs':{},'checks':{}}
def persist():statefile.write_text(json.dumps(state,ensure_ascii=False,indent=2))
def definition(label,spec):
 if label in state["definitions"]:return state["definitions"][label]["id"],state["definitions"][label]["revision"]
 d=api('/api/v1/orchestration-definitions',{'tenant':'default','namespace':'default','name':state['prefix']+'-'+label,'description':'Workflow acceptance fixture. Safe to archive after review.','draftSpec':spec})['definition']
 r=api('/api/v1/orchestration-definitions/'+d['id']+'/publish',{'expectedVersion':d['version']})['revision'];state['definitions'][label]={'id':d['id'],'revision':r['id']};persist();return d['id'],r['id']
def launch(label,did,rid,input=None,title=None):
 req={'revisionId':rid,'idempotencyKey':state['prefix']+'-'+label,'input':input or {},'issue':{'title':title or state['prefix']+' '+label,'description':'Workflow acceptance only. Do not modify files, run network research, or send messages to other people.'}}
 run=api('/api/v1/orchestration-definitions/'+did+'/runs',req)['run'];state['runs'][label]={'id':run['id'],'issue':run['rootIssueId'],'state':run['state'],'request':req,'definition':did};persist();return run
command=sys.argv[1] if len(sys.argv)>1 else 'poll'
if command=='init':
 if state.get('initialized'):raise RuntimeError('Fixtures already initialized; use poll.')
 childid,childrev=definition('child',{'nodes':[{'key':'finish','type':'condition','input':{'value':'run.input.value'}}]})
 claims=json.loads(base64.urlsafe_b64decode(TOKEN.split('.')[1]+'=='));user=claims.get('sub','admin')
 did,rid=definition('gates',{'nodes':[{'key':'delay','type':'timer','timer':{'durationSeconds':15}},{'key':'release','type':'signal','signalName':'release'},{'key':'approval','type':'approval','approval':{'approverRef':user,'prompt':'Approve deterministic Workflow acceptance value 42.'}},{'key':'child','type':'subrun','definitionRevisionId':childrev,'input':{'value':'nodes.release.output.value'}},{'key':'join','type':'join','join':{'mode':'all'}}], 'edges':[{'from':'delay','to':'release'},{'from':'release','to':'approval'},{'from':'approval','to':'child'},{'from':'child','to':'join'}]})
 run=launch('gates',did,rid)
 duplicate=api('/api/v1/orchestration-definitions/'+did+'/runs',state['runs']['gates']['request'])['run'];assert duplicate['id']==run['id'] and duplicate['rootIssueId']==run['rootIssueId'];state['checks']['idempotent_start']=True
 api('/api/v1/orchestration-runs/'+run['id']+'/pause',{})
 api('/api/v1/orchestration-runs/'+run['id']+'/signals/release',{'idempotencyKey':'release-once','payload':{'value':42}})
 paused=api('/api/v1/orchestration-runs/'+run['id'])['run'];assert paused['state']=='paused';state['checks']['signal_does_not_resume_paused_run']=True
 api('/api/v1/orchestration-runs/'+run['id']+'/resume',{})
 agents=api('/api/v1/agents?tenant=default&namespace=default')['items'];managed=next(a for a in agents if a.get('agentKey')=='ma2')['id']
 did,rid=definition('managed',{'nodes':[{'key':'calculate','type':'agent','agentId':managed,'input':{'instruction':'"Use the workflow node input: calculate a+b and return exactly the number. Complete the task normally."','a':'run.input.a','b':'run.input.b'},'timeoutSeconds':180},{'key':'inspect','type':'condition','input':{'upstream':'nodes.calculate.output'}}],'edges':[{'from':'calculate','to':'inspect'}]})
 launch('managed',did,rid,{'a':19,'b':23},state['prefix']+' verify node input arithmetic')
 options=api('/api/v1/agents/runtime-options?tenant=default&namespace=default')['runtimes'];runtime=next((r for r in options if r['provider']=='codex'),None)
 if runtime:
  host=api('/api/v1/agents',{'tenant':'default','namespace':'default','agentKey':state['prefix']+'-hosted','displayName':'Workflow acceptance Hosted','ownerType':'user','binding':{'kind':'hosted-runtime','priority':100,'configuration':{'runtimeProfileId':runtime['runtimeProfileId'],'runtimePoolId':runtime['runtimePoolId']}},'definition':{'name':'Workflow acceptance Hosted','system':'You are a deterministic acceptance Agent. Read the current task context, calculate the supplied arithmetic, return the result and complete your task. Do not modify files or contact external services.'}})['agent'];state['hostedAgent']=host['id'];persist()
  did,rid=definition('hosted',{'nodes':[{'key':'calculate','type':'agent','agentId':host['id'],'input':{'instruction':'"Calculate a+b from this node input. Return exactly the integer and complete the task."','a':'run.input.a','b':'run.input.b'},'timeoutSeconds':240}]});launch('hosted',did,rid,{'a':17,'b':25})
 team=next(t for t in api('/api/v1/teams?tenant=default&namespace=default')['items'] if t['name']=='team1')
 did,rid=definition('team',{'nodes':[{'key':'team','type':'team','teamRef':team['id'],'timeoutSeconds':240},{'key':'done','type':'condition','input':{'delivery':'nodes.team.output'}}],'edges':[{'from':'team','to':'done'}]})
 launch('team',did,rid,title=state['prefix']+' 团队验收：请让 worker 计算 20+22，Lead 收到结果后在主 Issue 汇总 42，并显式完成协调节点。无需调研或修改文件。')
 did,rid=definition('cancel',{'nodes':[{'key':'wait','type':'signal','signalName':'never'}]});run=launch('cancel',did,rid);api('/api/v1/orchestration-runs/'+run['id']+'/cancel',{})
 state['checks']['cancel']=api('/api/v1/orchestration-runs/'+run['id'])['run']['state']=='cancelled'
 state['initialized']=True
 persist();print(json.dumps({'definitions':state['definitions'],'runs':state['runs'],'checks':state['checks']},ensure_ascii=False))
elif command=='poll':
 for name,record in state['runs'].items():
  graph=api('/api/v1/orchestration-runs/'+record['id']+'/graph');record['state']=graph['run']['state'];record['output']=graph['run'].get('output');record['failure']=graph['run'].get('failureMessage');(ROOT/'evidence'/f'{name}-graph.json').write_text(json.dumps(scrub(graph),ensure_ascii=False,indent=2))
  if name=='gates':
   approvals=api('/api/v1/approvals?tenant=default&namespace=default&runId='+record['id'])['items']
   for approval in approvals:
    if approval.get('runId')==record['id'] and approval['status']=='pending':api('/api/v1/approvals/'+approval['id']+'/decide',{'expectedVersion':approval['version'],'status':'approved','decision':{'value':42}});state['checks']['approval_decided']=True
  print(name,record['state'],'nodes',[(n['nodeKey'],n['state']) for n in graph['nodes']],'attempts',[(a['backendKind'],a['state']) for a in graph.get('attempts',[])])
 persist()
