from probe import *
s=json.loads((ROOT/'state.json').read_text())
def save(): (ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
def issue(name,typ,ref,prompt):
 x=api('/api/v1/issues',{'tenant':'default','namespace':'default','title':PREFIX+'-'+name,'description':prompt,'assigneeType':typ,'assigneeRef':ref},label='submit-'+name)
 assert api.last_status==201,(name,x)
 s['issues'][name]=x;save();print(name,x['issue']['id'],flush=True)
for name,role,prompt in [('managed','calc','计算19×21+1。回复 MANAGED=400。'),('qoder','qoder','将zebra,apple,pear按字典序排序，回复 QODER=apple,pear,zebra。'),('codex','codex','判断101是否为质数，简述需要试除哪些质数，结果包含 CODEX=prime。')]:
 issue('single-'+name,'agent',s['agents'][role],prompt+'业务任务不需要联网。必须按照协作协议提交最终结果并完成任务，不主动委派。之后同一Issue如收到管理员的新评论，执行最新指令。')
normal='必须真实创建两个子Issue分给calculator和analyst，不能自己替代。calculator计算19×21+1；analyst把zebra,apple,pear按字典序排序。当前子Issue收到结果就验收，不等待兄弟才验收；两份成果都验收后汇总 TEAM_OK;CALC=400;SORT=apple,pear,zebra 并完成整个run。子Issue标题前缀 '+PREFIX+'。'
research='并行委派两个不同成员：researcher调研新能源汽车行业，analyst调研手机行业。每个行业要求实际使用可用web搜索/访问工具，找到至少2个可访问的官方来源URL并给出发布日期、两条近期事实；不能凭训练知识编造近期事实或来源。两份子任务都必须创建，标题前缀 '+PREFIX+'。任何成员缺少所需web工具/凭证或无法核实来源，必须明确task.fail，上报行业名和缺失能力。协调员收到失败不得当成功验收；必须明确让整个run failed并说明缺口。若两份调研均真实完成，才验收汇总并完成run。不要修改权限或配置来完成测试。'
s['prompts']={'normal':normal,'research':research};save()
issue('team-success','team',s['teams']['managed'],normal)
issue('team-web-research','team',s['teams']['mixed'],research)
