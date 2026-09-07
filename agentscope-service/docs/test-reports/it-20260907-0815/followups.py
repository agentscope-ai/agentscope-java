from probe import *
s=json.loads((ROOT/'state.json').read_text())
# Read authoritative state before submitting followups.
for role,c in s['chats'].items():
 sid=c['chat']['sessionId']
 x=api('/api/v1/sessions/'+sid+'/messages',label='chat-before-followup-'+role)
 print(role,json.dumps(x,ensure_ascii=False)[-600:])
 x=api('/api/v1/chats/'+c['chat']['id']+'/turns',{'message':'上一轮我让你记住的测试标记是什么？另外计算6×7。仅输出标记和答案，不用工具。'},label='chat-turn2-'+role)
 print('turn2',role,json.dumps(x,ensure_ascii=False)[:160])
iid=s['issues']['research-phone']['issue']['id']
x=api('/api/v1/issues/'+iid+'/comments',{'content':'继续测试：接受本轮无法联网的事实，停止研究。现在请只计算9×9，回复 RECOVERED=81，不用任何联网工具，也不请求改变权限。','mentions':[{'type':'agent','ref':s['agents']['qoder']}]},label='phone-recovery-comment')
print('phone recovery',json.dumps(x,ensure_ascii=False)[:350])
# Unassigned issue: now structured mention provides positive control.
iid=s['issues']['mention-text-control']['issue']['id']
api('/api/v1/issues/'+iid+'/export',label='text-control-before-structured-export')
x=api('/api/v1/issues/'+iid+'/comments',{'content':'这是新的结构化mention对照，请只输出 STRUCTURED_WAKE=OK，不要再mention任何人。','mentions':[{'type':'agent','ref':s['agents']['analyst']}]},label='structured-mention-comment')
print('structured control',json.dumps(x,ensure_ascii=False)[:300])
