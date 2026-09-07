from probe import *
s=json.loads((ROOT/'state.json').read_text());keys=json.loads(pathlib.Path('/tmp/agentscope-it-20260907-fix2-endpoint-keys.json').read_text())
for ep in ['managed-chat','qoder-chat']:
 if ep+'-turn2' in s['invocations']: continue
 first=s['invocations'][ep+'-turn1']['response']
 turns=api('/api/v1/sessions/'+first['sessionRef']+'/turns',label='before-turn2-'+ep).get('turns',[])
 if not turns or turns[-1]['status']!='completed':
  print(ep,'first turn still pending');continue
 body={'message':'上一轮要求记住的标记是什么？计算6×7。只回答之前的标记和数字，不使用联网工具。'}
 x=api('/invoke/v1/conversations/'+first['conversationId']+'/turns',body,headers={'X-API-Key':keys[ep],'Idempotency-Key':PREFIX+'-'+ep+'-turn2'},label='invoke-'+ep+'-turn2')
 s['invocations'][ep+'-turn2']={'endpoint':ep,'request':body,'response':x,'status':api.last_status};print(ep,api.last_status,json.dumps(x)[:170])
(ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
