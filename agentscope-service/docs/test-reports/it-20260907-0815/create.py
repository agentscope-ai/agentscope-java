from probe import *
state={'agents':{},'teams':{},'issues':{},'chats':{}}
opts=api('/api/v1/agents/runtime-options?tenant=default&namespace=default')
common='你是集成测试助手。只在当前测试 Issue/工作区工作，不操作其他资源，不修改实现或权限。无工具的算术和文本题直接作答；需要实时资料时实际使用可用工具，工具不可用须明确说明，禁止编造来源。遵守运行时协作协议，真实协作必须调用工具，不能只声称已委派或在正文写@。'
for role,kind,profile,sys in [('lead','managed',None,'你是协调员，按照任务要求创建和委派子任务，收到结果后验收并明确完成或失败整个run。'),('calc','managed',None,'你是计算员，擅长整数运算。'),('analyst','managed',None,'你是文本分析员，擅长排序和从给定材料提取事实。'),('qoder','hosted-runtime','auto-qoder','你是行业研究员和协作成员。'),('codex','hosted-runtime','auto-codex','你是复核员，擅长独立验证计算结果。')]:
 name=PREFIX+'-'+role
 binding={'kind':kind,'priority':100}
 if profile: binding['configuration']={'runtimeProfileId':next(p['id'] for p in opts['profiles'] if p['name']==profile),'runtimePoolId':opts['pools'][0]['id']}
 body={'tenant':'default','namespace':'default','agentKey':name,'displayName':name,'ownerType':'user','description':'真实集成测试保留资源','binding':binding,'definition':{'name':name,'system':common+sys,'maxIters':20}}
 out=api('/api/v1/agents',body,label='create-agent-'+role)
 print(role,json.dumps(scrub(out),ensure_ascii=False)[:250]);state['agents'][role]=out['agent']['id']
for name,lead,members in [('managed','lead',[('calc','calculator'),('analyst','analyst')]),('mixed','lead',[('qoder','researcher'),('calc','calculator')])]:
 body={'tenant':'default','namespace':'default','name':PREFIX+'-'+name,'leaderAgentId':state['agents'][lead],'instructions':'所有子Issue标题以 '+PREFIX+' 开头。必须实际委派指定成员并收集结果，不要自己替代成员工作。工具受限要如实传递。','members':[{'agentId':state['agents'][r],'role':rr} for r,rr in members]}
 out=api('/api/v1/teams',body,label='create-team-'+name);print(name,json.dumps(out,ensure_ascii=False)[:180]);state['teams'][name]=out['team']['id']
(ROOT/'state.json').write_text(json.dumps(state,indent=2))
