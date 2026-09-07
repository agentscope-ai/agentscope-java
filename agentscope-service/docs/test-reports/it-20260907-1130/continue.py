from probe import *
s=json.loads((ROOT/'state.json').read_text());keys=json.loads(pathlib.Path('/tmp/agentscope-it-20260907-1130-endpoint-keys.json').read_text())
for ep in ['managed-chat','qoder-chat']:
 first=s['invocations'][ep+'-turn1']['response'];body={'message':'上一轮要求记住的标记是什么？计算6×7。只回答之前的标记和数字，不使用联网工具。'}
 x=api('/invoke/v1/conversations/'+first['conversationId']+'/turns',body,headers={'X-API-Key':keys[ep],'Idempotency-Key':PREFIX+'-'+ep+'-turn2'},label='invoke-'+ep+'-turn2')
 s['invocations'][ep+'-turn2']={'endpoint':ep,'request':body,'response':x,'status':api.last_status};print(ep,api.last_status,json.dumps(x)[:170])
# Successful human-owned work remains in_review until the administrator accepts it.
for name in ['single-qoder','single-codex','team-success']:
 iid=s['issues'][name]['issue']['id'];cur=api('/api/v1/issues/'+iid,label='before-admin-accept-'+name)['issue']
 x=api('/api/v1/issues/'+iid+'/accept',{'expectedVersion':cur['version'],'reason':'管理员已核对本轮结果及完成状态，验收通过。'},label='admin-accept-'+name)
 s.setdefault('adminAcceptance',{})[name]={'status':api.last_status,'response':x};print('accept',name,api.last_status)
(ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
