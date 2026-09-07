from probe import *
s=json.loads((ROOT/'state.json').read_text());A=s['agents']['calc'];B=s['agents']['qoder'];iid=s['issues']['single-managed']['issue']['id']
content=f'''管理员开始新的协作回合。本条替代之前的计算要求。你是A={A}：请只调用一次issue.comment.add，结构化mentions=[{{"type":"agent","ref":"{B}"}}]。给B的content要求：你是B，计算12×12并输出 CROSS_B=144，然后用issue.comment.add结构化mention A={A} 回复结果一次，随后完成任务；不要重放A的发起指令。A发出任务后完成当前task；A收到B的回复后只输出 CROSS_ACK=144并task.complete，不再mention任何人。必须真实投递，不仅在文本里写@。'''
x=api('/api/v1/issues/'+iid+'/comments',{'content':content,'mentions':[{'type':'agent','ref':A}]},label='admin-start-cross-mention')
s['adminCross']={'issueId':iid,'comment':x};(ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2));print(json.dumps(x,ensure_ascii=False)[:1100])
