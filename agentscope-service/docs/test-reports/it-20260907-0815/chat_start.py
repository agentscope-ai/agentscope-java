from probe import *
s=json.loads((ROOT/'state.json').read_text())
for role in ['analyst','qoder']:
 c=api('/api/v1/chats',{'tenant':'default','namespace':'default','agentId':s['agents'][role],'title':PREFIX+'-chat-'+role},label='chat-create-'+role)
 s['chats'][role]=c
 if c.get('chat'):
  x=api('/api/v1/chats/'+c['chat']['id']+'/turns',{'message':'记住测试标记 MEMORY_0815。计算21+21，仅回答标记和答案。无需工具。'},label='chat-turn1-'+role)
  print(role,json.dumps(scrub(x),ensure_ascii=False)[:500])
 else: print(role,c)
 (ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
