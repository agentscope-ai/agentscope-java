from probe import *
s=json.loads((ROOT/'state.json').read_text());A=s['agents']['qoder'];B=s['agents']['calc']
prompt=f'''测试同属混合Team的两个成员跨运行时真实mention：你是Hosted A={A}，Managed B={B}。初次执行：用issue.comment.add发送一次结构化mentions=[{{"type":"agent","ref":"{B}"}}]，content="这是给B的新指令：计算12×12，输出 CROSS_B=144；再用issue.comment.add结构化mention回复A={A}一次，然后完成你的任务，不要执行原始A的发起步骤。"。A发出后立即结束本次任务，勿等待。A收到B回复后仅输出 CROSS_ACK 并完成任务，不再调用comment.add，不再mention。B只执行收到的comment的新指令。禁止重新执行已完成的发起步骤。'''
x=api('/api/v1/issues',{'tenant':'default','namespace':'default','title':PREFIX+'-mention-mixed','description':prompt,'assigneeType':'agent','assigneeRef':A},label='submit-mention-mixed');s['issues']['mention-mixed']=x;(ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2));print(x['issue']['id'])
