import json,pathlib,collections
ROOT=pathlib.Path(__file__).resolve().parent
d=json.loads((ROOT/'final-inventory.json').read_text())
s=json.loads((ROOT/'state.json').read_text())
checks=[]
def check(name,ok,observed,refs=None):
 checks.append({'name':name,'status':'PASS' if ok else 'FAIL','observed':observed,'refs':refs or []})
def issue(name):return d['issues'][d['scenarios'][name]]
def graph(name):return d['runs'][issue(name)['runs'][0]['id']]
def delivery(g):
 ids={t['id'] for t in g['tasks']}
 comments=[c['content'] for e in d['issues'].values() for c in e.get('comments',[]) if c.get('sourceTaskId') in ids]
 return json.dumps([t.get('result') for t in g['tasks']]+comments,ensure_ascii=False)
build=json.loads((ROOT/'evidence/baseline-build.json').read_text())
check('运行产物包含已合入修复',all(build['fixMarkers'].values()),build['fixMarkers'],['evidence/baseline-build.json'])
for name,marker in [('single-managed','MANAGED=400'),('single-qoder','QODER=apple,pear,zebra'),('single-codex','CODEX=prime')]:
 e=issue(name);r=min(e['runs'],key=lambda r:r['createdAt']);g=d['runs'][r['id']]
 check(name+' 基础业务结果',r['state']=='succeeded' and marker in delivery(g),{'runId':r['id'],'state':r['state'],'expected':marker,'delivery':delivery(g)})
check('人类管理员验收',all(s.get('adminAcceptance',{}).get(n,{}).get('status')==200 and issue(n)['issue']['status']=='done' for n in ['single-qoder','single-codex']),{n:issue(n)['issue']['status'] for n in ['single-qoder','single-codex']})
e=issue('single-managed')
cross=s['adminCross']['comment']['Comment']['id'];correct=s['adminCorrection']['response']['Comment']['id']
for label,cid,marker in [('mention A→B→A',cross,'CROSS_ACK=144'),('管理员更正需求',correct,'ADMIN_FINAL=156')]:
 r=next(r for r in e['runs'] if r.get('triggerRef')==cid);g=d['runs'][r['id']];out=delivery(g)
 check(label+' 内容',r['state']=='succeeded' and marker in out,{'runId':r['id'],'state':r['state'],'expected':marker,'delivery':out})
 if label.startswith('mention'):
  check('mention 回合数',len(g['tasks'])==3,{'runId':r['id'],'expectedTasks':3,'actualTasks':len(g['tasks']),'agents':[t['agentId'] for t in g['tasks']]})
for name in ['team-success','api-team-job-ok']:
 e=issue(name);g=graph(name);calc=[t for t in g['tasks'] if t['agentId']==s['agents']['calc']]
 final=[t.get('result') for t in g['tasks'] if t['agentId']==s['agents']['lead']][-1]
 check(name+' 交付一致且正确',len(e['children'])==2 and g['run']['state']=='succeeded' and 'CALC=400' in str(final) and all('400' in str(t.get('result')) for t in calc),{'issueId':e['issue']['id'],'state':e['issue']['status'],'runState':g['run']['state'],'final':final,'calculatorResults':[t.get('result') for t in calc],'children':[(c['id'],c['status']) for c in e['children']]})
for name in ['team-web-research','api-team-job-research']:
 e=issue(name);g=graph(name)
 check(name+' 失败收敛',len(e['children'])==2 and g['run']['state']=='failed' and all(t['status'] in ['completed','failed','cancelled'] for t in g['tasks']),{'issueId':e['issue']['id'],'issueState':e['issue']['status'],'runState':g['run']['state'],'taskStates':dict(collections.Counter(t['status'] for t in g['tasks'])),'failureMessage':g['run'].get('failureMessage')})
for name,marker in [('managed-job-ok','API_TOTAL=144'),('qoder-job-ok','API_WORDS'),('team-job-ok','TEAM_OK;CALC=400;SORT=apple,pear,zebra')]:
 g=graph('api-'+name);p=d['public'][name]['invocation']
 if name!='team-job-ok':check(name+' 内部业务',g['run']['state']=='succeeded' and marker in delivery(g),{'runId':g['run']['id'],'delivery':delivery(g)})
 check(name+' 公开 API 返回业务结果',marker in json.dumps(p.get('result'),ensure_ascii=False),{'invocationId':p['id'],'status':p['status'],'result':p.get('result')})
for name in ['qoder-job-fail','team-job-research']:
 p=d['public'][name]['invocation']
 check(name+' API 失败详情',p['status']=='failed' and bool(p.get('errorMessage')) and p.get('errorCode') not in [None,'','run_failed'],{'invocationId':p['id'],'status':p['status'],'errorCode':p.get('errorCode'),'errorMessage':p.get('errorMessage')})
for ep in ['managed-chat','qoder-chat']:
 r=s['invocations'][ep+'-turn1']['response'];v=d['sessions'][r['sessionRef']];turns=v['turns'].get('turns',[])
 answers=[m.get('content','') for m in v['messages'].get('messages',[]) if m.get('role')=='assistant' and not m.get('toolName')]
 check(ep+' 两轮内部会话',len(turns)==2 and all(t['status']=='completed' for t in turns) and len(answers)>=2 and all('API_MEMORY_1130' in a and '42' in a for a in answers[-2:]),{'sessionRef':r['sessionRef'],'turns':turns,'answers':answers})
 pts=d['public'][ep+'-turn2']['turns']
 check(ep+' 公开 Turn 状态',len(pts)==2 and all(t['status']=='completed' for t in pts),{'conversationId':r['conversationId'],'turns':pts})
 if ep=='qoder-chat':
  attempts=[a for a in d['attempts'].values() if a.get('sessionId')==r['sessionId']]
  check('Hosted 历史 Attempt 保留 SessionRef',len(attempts)==2 and all(a.get('sessionRef')==r['sessionRef'] for a in attempts),[{'id':a['id'],'state':a['state'],'sessionRef':a.get('sessionRef')} for a in attempts])
auth=json.loads((ROOT/'evidence/endpoint-auth-negative.json').read_text())
check('Endpoint 无 Key 鉴权',auth['status']==401,auth['status'])
idem=s['idempotency'];check('相同请求幂等重试',idem['sameStatus'] in [200,202] and idem['same'].get('invocationId')==idem['first']['invocationId'],idem)
check('幂等键冲突拒绝',idem['changedStatus']==409,idem['changedStatus'])
check('Team conversation 能力边界',s['teamConversationSupport']['status']==400,s['teamConversationSupport'])
replay=json.loads((ROOT/'evidence/sse-resume-managed-job.json').read_text());check('SSE 断点续读',replay['status']==200 and replay['ids']==[5,6],{k:v for k,v in replay.items() if k!='stream'})
streams={p.stem:json.loads(p.read_text()) for p in (ROOT/'evidence').glob('stream-*.json')}
check('全部公开 SSE 顺序及终止',len(streams)==len(s['invocations']) and all(x['httpStatus']==200 and x['idsIncreasing'] and x['readEnd']=='EOF' for x in streams.values()),{n:{k:v for k,v in x.items() if k!='stream'} for n,x in streams.items()})
mcp=json.loads((ROOT/'evidence/mcp-get.json').read_text());check('MCP GET 协议',mcp['status']==405 and mcp['allow']=='POST',mcp)
result={'at':d['at'],'counts':dict(collections.Counter(c['status'] for c in checks)),'checks':checks}
(ROOT/'business-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(result['counts'])
for c in checks:print(c['status'],c['name'])
