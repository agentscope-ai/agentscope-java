from probe import *
team=api('/api/v1/teams',{'tenant':'default','namespace':'default','name':'leader-wait-20260907','leaderAgentId':'67a796b7-02e7-493f-8990-931f0759f95e','instructions':'这是等待协作回复的集成验证。只在本测试创建的Issue工作。所有成果直接放在task.complete.result中，不使用文件路径交付。Lead追问后以task.complete(outcome=waiting)让出当前回合，等待新的结果触发后续回合。','members':[{'agentId':'b86719e8-f495-402f-bffa-b6d76eee8965','role':'worker'}]},label='create-team')
assert api.last_status==201,team
prompt='''请严格通过实际协作完成以下两阶段验收，不能自己代替成员工作：
1. MA1只创建一个子Issue给worker（MA2），第一轮要求MA2直接提交字符串 DRAFT_READY，不能写文件。
2. 收到DRAFT_READY后，MA1不应验收，而应在同一个子Issue通过issue.comment.add的结构化mentions明确让MA2计算12×12，并以FOLLOWUP_OK_144作为最终结果。mention成功后，MA1必须调用task.complete(outcome=waiting,summary="等待MA2补充验证结果")结束自己的当前回合，不要将等待视为失败。
3. MA2收到第二轮追问时用math.evaluate验证12×12，提交result="FOLLOWUP_OK_144"。
4. MA1收到FOLLOWUP_OK_144后才调用issue.accept验收，并调用run.node.complete，在主Issue总结第一次草稿、后续补充与最终144结果。不要重新追问或重复创建子Issue。只需这一个子Issue，不用web工具、也不要读写文件。'''
x=api('/api/v1/issues',{'tenant':'default','namespace':'default','title':'leader-wait-20260907 MA1追问MA2后继续执行','description':prompt,'assigneeType':'team','assigneeRef':team['team']['id']},label='create-issue')
assert api.last_status==201,x
(ROOT/'state.json').write_text(json.dumps({'team':team['team']['id'],'issue':x['issue']['id']},indent=2))
print(x['issue']['id'])
