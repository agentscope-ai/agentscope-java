from probe import *
s=json.loads((ROOT/'state.json').read_text());keys={}
keyfile=pathlib.Path('/tmp/agentscope-it-20260907-fix2-endpoint-keys.json')
def save():
 (ROOT/'state.json').write_text(json.dumps(scrub(s),ensure_ascii=False,indent=2))
 keyfile.touch(mode=0o600,exist_ok=True);keyfile.chmod(0o600);keyfile.write_text(json.dumps(keys))
for name,typ,ref,mode in [('managed-job','agent',s['agents']['calc'],'job'),('qoder-job','agent',s['agents']['qoder'],'job'),('managed-chat','agent',s['agents']['analyst'],'conversation'),('qoder-chat','agent',s['agents']['qoder'],'conversation'),('team-job','team',s['teams']['managed'],'job'),('team-research-job','team',s['teams']['mixed'],'job')]:
 x=api('/api/v1/endpoints',{'tenant':'default','namespace':'default','name':PREFIX+'-'+name,'slug':PREFIX+'-'+name,'targetType':typ,'targetRef':ref,'invocationMode':mode},label='endpoint-create-'+name)
 assert api.last_status==201,(name,x)
 keys[name]=x['credential'];s['endpoints'][name]=x['endpoint'];save()
 eid=x['endpoint']['id']
 ready=api('/api/v1/endpoints/'+eid+'/readiness',label='endpoint-ready-'+name)
 pub=api('/api/v1/endpoints/'+eid+'/publish',{'version':x['endpoint']['version']},label='endpoint-publish-'+name)
 print(name,'published',api.last_status,json.dumps(pub,ensure_ascii=False)[:180],flush=True)
 s['endpoints'][name]['publication']=pub;save()
# Explicitly verify unsupported Team conversation mode does not create a fake service.
x=api('/api/v1/endpoints',{'tenant':'default','namespace':'default','name':PREFIX+'-team-chat','slug':PREFIX+'-team-chat','targetType':'team','targetRef':s['teams']['managed'],'invocationMode':'conversation'},label='endpoint-team-chat-unsupported')
s['teamConversationSupport']={'status':api.last_status,'response':x};save()
def invoke(name,ep,body):
 path='/invoke/v1/endpoints/'+s['endpoints'][ep]['slug']+('/jobs' if s['endpoints'][ep]['invocationMode']=='job' else '/conversations')
 h={'X-API-Key':keys[ep],'Idempotency-Key':PREFIX+'-'+name}
 x=api(path,body,headers=h,label='invoke-'+name);s['invocations'][name]={'endpoint':ep,'request':body,'response':x,'status':api.last_status};save()
 print(name,api.last_status,json.dumps(x,ensure_ascii=False)[:220],flush=True)
 if name=='managed-job-ok':
  api(path,body,headers={},label='endpoint-auth-negative')
  same=api(path,body,headers=h,label='endpoint-idempotency-same')
  s['idempotency']={'first':x,'same':same,'sameStatus':api.last_status}
  diff=api(path,{**body,'description':'different input'},headers=h,label='endpoint-idempotency-conflict')
  s['idempotency'].update({'changed':diff,'changedStatus':api.last_status});save()
invoke('managed-job-ok','managed-job',{'title':PREFIX+'-endpoint-arithmetic','description':'读取任务input：计算quantity×unitPrice，输出 API_TOTAL=144 并完成任务。无需联网。','input':{'quantity':12,'unitPrice':12}})
invoke('qoder-job-ok','qoder-job',{'title':PREFIX+'-endpoint-text','description':'按input里的文本统计英文单词数，输出 API_WORDS=4 并完成任务。无需联网。','input':{'text':'red blue green yellow'}})
invoke('team-job-ok','team-job',{'title':PREFIX+'-endpoint-team-ok','description':s['prompts']['normal'],'input':{'scenario':'team-normal'}})
invoke('team-job-research','team-research-job',{'title':PREFIX+'-endpoint-two-industries','description':s['prompts']['research'],'input':{'industries':['新能源汽车','手机'],'mustUseWeb':True}})
for ep in ['managed-chat','qoder-chat']:
 invoke(ep+'-turn1',ep,{'message':'记住标记 API_MEMORY_1130，计算20+22，回答标记与42。无需联网。后续我会在同一会话提问。'})
invoke('qoder-job-fail','qoder-job',{'title':PREFIX+'-endpoint-required-web','description':'必须实际使用web工具调研手机行业近期变化，提供2个官方URL和日期。若缺少web工具或无法验证来源，必须task.fail并说明缺失能力，不得把无法调研作为成功结果，不修改权限。','input':{'requiredCapability':'web-search'}})
