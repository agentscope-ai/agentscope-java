import json,pathlib,collections,datetime
ROOT=pathlib.Path(__file__).resolve().parent
d=json.loads((ROOT/'final-inventory.json').read_text())
s=json.loads((ROOT/'state.json').read_text())
b=json.loads((ROOT/'business-checks.json').read_text())
c=json.loads((ROOT/'final-checks.json').read_text())
build=json.loads((ROOT/'evidence/baseline-build.json').read_text())
def link(name,label=None):return f'[{label or name}]({ROOT/name})'
def state_counts(items,key):return dict(collections.Counter(x[key] for x in items))
counts={k:len(d[k]) for k in ['issues','runs','tasks','attempts','sessions']}
counts.update(runEvents=sum(len(x.get('events',[])) for x in d['runEvents'].values()),sessionEvents=sum(len(x['events'].get('events',[])) for x in d['sessions'].values()),issueActivities=sum(len(x.get('activity',[])) for x in d['issues'].values()))
states={'issues':state_counts([x['issue'] for x in d['issues'].values()],'status'),'runs':state_counts([x['run'] for x in d['runs'].values()],'state'),'tasks':state_counts(d['tasks'].values(),'status'),'attempts':state_counts(d['attempts'].values(),'state')}
assert set(states['runs'])<= {'succeeded','failed','cancelled'}
assert set(states['tasks'])<= {'completed','failed','cancelled'}
assert set(states['attempts'])<= {'succeeded','failed','cancelled'}
findings=[
 {'id':'F1','severity':'high','title':'新评论需求被旧 Issue 描述覆盖','detail':'管理员要求只计算 12×13 并交付 ADMIN_FINAL=156；评论输入已标为 processed，Task、Attempt、Run 全部成功，实际仍交付 MANAGED=400。','issueId':'48695df5-5ced-4512-bf3b-a4dc78f68ebd','runId':'18af6759-46f3-4aff-8796-308c6516784e','taskId':'3d311add-5cbd-430d-abce-68b3af8815c4'},
 {'id':'F2','severity':'high','title':'mention 回合重复且未遵循最新指令','detail':'预期 A→B→A 共 3 个 Task，实际 A→B→A→A→B→A 共 6 个；B 的 explicit 回复与 task.complete 自动 follow_up 分别创建 A Task，之后仍有 thread_parent 回复。最终没有 CROSS_ACK=144，持续回到 MANAGED=400。','runId':'f27a05d7-49bb-4ebf-bc32-28b114f86d19'},
 {'id':'F3','severity':'high','title':'Team 验收了错误计算结果','detail':'直接 Team 子任务描述明确为 19×21+1，但计算员交付 CALC=399，leader 验收后最终也输出399。API Team 子任务同样返回399，leader最终改报400，却没有纠正已验收的子任务结果。','issueIds':['e6c1e78c-299c-47e4-b937-e2b6e72c1bfe','14e95238-a8dd-57a2-861c-5443f9adb0dc']},
 {'id':'F4','severity':'high','title':'Managed conversation 完成后错误超时','detail':'两轮 Session Turn 均 completed，答案均包含记忆标记与42；公开 Invocation 一直 running，随后两个均变成 timed_out/endpoint_timeout。','conversationId':'ace01b6b-1ded-4b70-b208-025a5e99f8b6','sessionRef':'6a94b4cd-623f-49f4-9806-f60c3b04d5bc'},
 {'id':'F5','severity':'high','title':'Endpoint 相同请求幂等重试被拒绝','detail':'相同 Idempotency-Key 与完全相同 JSON 请求，首次202，立即重试及执行完成后重试都409，提示different input；改动请求409的阴性对照正常。','invocationId':'e88afff7-2a92-4bd2-bcdd-8e8e0ced303e'},
 {'id':'F6','severity':'high','title':'公开 Job 查询没有返回业务答案','detail':'Managed、Qoder、Team 三个成功 Job 的 Invocation.result 均只有 {runState:succeeded}。Managed Job SSE 也只有6个生命周期事件，没有144；Team节点事件可读到输出，但公开查询结果仍缺失。'},
 {'id':'F7','severity':'medium','title':'失败的 coordinator Task 输入没有结清','detail':'直接和 API Team 调研已进入失败终态，但两个 leader outcome Task 的输入仍是 delivered。','taskIds':['42efb9dd-342c-4d88-9874-6a37abe26509','f1977751-2a02-44ed-860d-99df440c109b']},
 {'id':'F8','severity':'medium','title':'本地 Web 工具报错仍记录为 SUCCESS','detail':'两次 web_search 明确返回缺少 TAVILY_API_KEY，Session agent.tool_result.frameworkMeta.state 却为 SUCCESS，对应 Run 没有 agent_tool.failed。远程 MCP 失败的真实 toolCallId 已正常，但本地工具错误标记仍不完整。','sessionRefs':['5d0faa5f-b7a4-4ff5-a746-fc0d65485a01','27397d0c-f364-4a77-99e1-7d37019ef193']}
]
summary={'verdict':'FAIL','prefix':ROOT.name,'collectedAt':d['at'],'generatedAt':datetime.datetime.now().astimezone().isoformat(),'directory':build['directory'],'branch':build['branch'],'head':build['head'],'fixMarkers':build['fixMarkers'],'counts':counts,'states':states,'businessCheckCounts':b['counts'],'structuralFindings':c['checks'],'findings':findings,'activeRuns':0,'activeTasks':0,'activeAttempts':0,'scenarios':d['scenarios'],'invocations':{n:v['response'] for n,v in s['invocations'].items()}}
(ROOT/'SUMMARY.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
rows=[
 ('Managed 单 Agent','通过基础题','MANAGED=400；基础 Run/Task/Attempt 成功，后续另测管理员流程。'),
 ('Qoder 单 Agent + 管理员验收','通过','排序正确；管理员 accept 200，Issue done。'),
 ('Codex 单 Agent + 管理员验收','通过','101 质数复核正确；管理员 accept 200，Issue done。'),
 ('同 Issue 的 A→B→A mention','不通过','消息真实投递；3个预期Task变成6个，最终回复旧题400。'),
 ('管理员介入、更正需求','不通过','新评论 processed，但要求156仍交付400；Run却 succeeded。'),
 ('直接 Team 正常任务','不通过','2个子Issue均done；结果399错误，leader验收错误结果；主Issue保留in_review。'),
 ('直接 Team 双行业调研','失败收敛通过，信息链不完整','两个子Issue均创建；缺凭证后Run failed，另一成员任务cancelled；主Issue blocked；仍有delivered输入与错误工具状态。'),
 ('Managed Endpoint Job','执行通过，公开结果不通过','算术答案144正确；Issue done / Run succeeded / Invocation completed，但公开result无答案。'),
 ('Qoder Endpoint Job','执行通过，公开结果不通过','文本单词数4正确；Issue done / Run succeeded / Invocation completed，但公开result无答案。'),
 ('Team Endpoint Job 正常任务','不通过','子任务399已验收、最终汇总400；Issue done / Run succeeded，公开result缺业务答案。'),
 ('Team Endpoint Job 双行业调研','失败收敛通过，信息链不完整','Run/Invocation failed；API有MISSING_CREDENTIALS和原因；主Issue cancelled；leader输入仍delivered。'),
 ('Qoder Endpoint Job 缺Web能力','通过预期失败','Task/Attempt/Run/Invocation均failed；公开WEB_TOOL_UNAVAILABLE及原因完整；自动完成策略Issue cancelled。'),
 ('Managed Endpoint 两轮会话','不通过','记忆与两次计算答案正确；内部两Turn completed，公开两Invocation却timed_out。'),
 ('Qoder Endpoint 两轮会话','通过','前后轮记忆与42正确；两Turn/Invocation completed；两历史Attempt保留同一SessionRef。'),
 ('Endpoint 发布与能力边界','通过所支持模式','4个Agent Endpoint、2个Team Job Endpoint发布成功；Team conversation明确400不支持。'),
 ('API Key、幂等、SSE、MCP','幂等不通过，其余通过','无Key401；相同请求409错误；冲突请求409正确；9条SSE均顺序递增并EOF；续读4→5,6；MCP GET405且Allow:POST。')
]
lines=[f'# 新集群完整集成回归：{ROOT.name}','',
 '**结论：未通过，尚不能认定全部修复完成。** 本轮真实调用新集群，覆盖三种 Agent、同 Issue mention、管理员介入、Team 成功/失败分支，以及 Agent/Team Endpoint Job 和连续会话。没有遗留运行中的 Run、Task 或 Execution Attempt，但存在业务误判、回合重复、API 状态/结果和事件信息问题。','',
 f'测试时间：2026-09-07 11:58 建立基线，11:59:55 开始业务请求，最终状态采集 {d["at"]}（Asia/Shanghai）。接口入口：http://localhost:18080。所有脚本与报告位于主目录 `{build["directory"]}`；分支 `{build["branch"]}`，提交 `{build["head"]}`。','',
 '## 构建和执行基线','',
 f'已读取正在使用的 aistiod 二进制构建信息，vcs.revision 与上述提交一致。5项 Go/Java 修复标记均存在，包含嵌套 agentscope-core JAR 的 MCP toolCallId 元数据。本次不再是上一轮旧运行产物。进程 aistiod/dataplane 于11:56:06启动，scheduler/gateway 于11:56:07启动；构建记录含4份产物SHA-256及mtime。vcs.modified=true 已原样保留，不能声称构建目录全干净。见 {link("evidence/baseline-build.json","构建证据")}。','',
 '新建5个测试Agent（3个Managed角色、Qoder、Codex）、2个Team、6个Endpoint，共9次公开API调用（5个Job、4个会话Turn）。使用登录后的真实admin账号发评论和验收。Hosted池capacity=1，排队期间保留实际状态，未把正常排队当成最终卡死。没有修改权限、补配Web凭证或重启服务。','',
 '## 场景结果','', '| 场景 | 判定 | 观察结果 |','|---|---|---|']
lines += ['| '+' | '.join(r)+' |' for r in rows]
lines += ['', '三种单Agent题分别为19×21+1、英文排序、101质数复核；Team成员分别计算与排序。联网场景真实创建“新能源汽车”和“手机”两个子Issue并指派不同成员；手机成员实际调用web_search，缺凭证后协调者使Run失败，并取消另一未完成成员。此项验证的是无法完成任务时的失败处理，没有把缺Web条件下的输出算作调研成功。','',
 '直接正常Team最终答案错误，所以没有替用户验收为done；同Issue管理员/mention新需求也没有验收。两个in_review主Issue保留现场。Qoder、Codex基础题经结果检查后由admin验收为done，验收请求与activity均保存。','',
 '## 与上一轮相比','',
 '| 项目 | 上一轮 it-20260907-1130 | 本轮 |','|---|---|---|',
 '| 新构建修复标记 | 旧产物未包含 | 5项均存在，revision与主分支一致 |',
 '| 直接Team失败分支 | 所有Task结束但Run waiting | Run failed，未完成成员cancelled |',
 '| Hosted conversation Turn | Turn列表为空，旧Attempt丢SessionRef | 两Turn completed；两Attempt均关联原Session |',
 '| 远程MCP工具失败关联 | 3条事件缺toolCallId | 3条事件均含真实callId，无重复 |',
 '| 缺Web的Qoder公开失败详情 | 通用run_failed且原因空 | WEB_TOOL_UNAVAILABLE及详细原因 |',
 '| 同Issue交互 | 5个Task且回复旧400 | 6个Task，仍回复旧400；管理员156仍被忽略 |',
 '| 正常Team质量 | 子任务399、汇总400 | 直接Team最终399；API Team子任务399、汇总400 |',
 '| Managed公开会话、Job结果、幂等 | 不通过 | 仍不通过 |','',
 '以上是本轮观察到的结果；公开失败详情也可能受完成投影与读取时序影响，本轮通过不等于所有时序均已覆盖。task.complete(outcome=blocked)映射没有被此轮存活的成员调用直接触发，不能单凭构建标记把该分支列为已验证。','',
 '## 待修复问题与定位','']
for f in findings:
 lines += [f'### {f["id"]} — {f["title"]}', '', f['detail'], '']
 refs={k:v for k,v in f.items() if k.endswith('Id') or k.endswith('Ids') or k in ['sessionRef','sessionRefs']}
 if refs:lines += ['定位：'+ '；'.join(f'`{k}={v}`' for k,v in refs.items()),'']
lines += [
 'F1管理员评论ID：`86745a4d-6615-4c32-8499-8497465541b3`。更正后Task的结果为 `{"answer":"MANAGED=400","calculation":"19 × 21 + 1 = 399 + 1 = 400"}`，并非156。F2中B的explicit路由创建`5a40ac93-d63d-4b22-a133-b60865b71ac7`，同次完成的follow_up另建`3f63487f-d975-437e-a606-2f9bec0febcb`，这两个A Task曾同时running。mention执行期间主Issue仍为in_review，此过程状态也在mid快照中保留。','',
 f'原始结构检查：{link("final-checks.json")}；业务/协议31个检查点：{link("business-checks.json")}，21通过、10不通过。这是检查点计数，不是31个独立业务场景。另有4条结构/事件异常（2条未结清输入、2条工具错误状态）。','',
 '## 完整信息状态检查','',
 '| 对象 | 数量 | 最终状态/检查 |','|---|---:|---|',
 f'| Issue | {counts["issues"]} | done 11、blocked 5、in_review 2、cancelled 2；父子关系、评论、activity均已导出 |',
 f'| Run | {counts["runs"]} | succeeded 11、failed 3；无waiting/running；不能据成功状态推断答案正确 |',
 f'| AgentTask | {counts["tasks"]} | completed 26、failed 5、cancelled 2；无活动Task；两个失败Task输入仍delivered |',
 f'| ExecutionAttempt | {counts["attempts"]} | succeeded 26、failed 5、cancelled 2；均attempt=1；当前Attempt与Task状态对应 |',
 f'| Run Event | {counts["runEvents"]} | 序列递增无重复；Run/Task/Attempt引用无孤立项；3条远程工具失败callId齐全 |',
 f'| Session / Session Event | {counts["sessions"]} / {counts["sessionEvents"]} | 消息、事件、Turn、SessionRef已核对；2条Web错误状态错误 |',
 f'| Issue Activity | {counts["issueActivities"]} | 归属Issue正确，含真实admin评论/验收与状态变化 |',
 '| Public API / SSE | 9次调用 / 9份流 | 所有流顺序递增且最终EOF；Managed超时及成功Job结果缺失另列失败 |','',
 'Task检查使用AgentTask资源和Run graph，不把 `/sessions/:id/tasks` 的运行时todo列表当作协作Task；Hosted不支持该todo接口的响应也保存在证据中。文本任务无必须生成的文件产物，Issue artifacts原样导出。取消前未实际启动的Hosted成员也保留Task/Attempt记录，不伪造Session执行证据。','',
 '## 资源ID与证据','', '| 业务根场景 | Issue ID |','|---|---|']
for name,iid in d['scenarios'].items():lines.append(f'| {name} | `{iid}` |')
lines += ['', '| API调用 | Invocation ID |','|---|---|']
for name,v in s['invocations'].items():lines.append(f'| {name} | `{v["response"]["invocationId"]}` |')
lines += ['',f'完整快照：{link("final-inventory.json")}。摘要和定位：{link("SUMMARY.json")}。所有创建请求、管理员评论、幂等阴性/重复请求、逐资源HTTP响应及SSE在 `evidence/`，请求日志为 {link("evidence/http.jsonl")}。旧报告保留，新证据独立归档。','',
 '脚本顺序：`create.py` → `submit.py` → `endpoints.py` → 等基础题完成后 `admin_start.py` → 会话首轮完成后 `continue.py` → mention终态后 `admin_correct.py` → `collect.py final` → `analyze.py final` → `streams.py` → `business_checks.py`。管理员验收的实际HTTP请求保存在evidence中。创建脚本用于独立新轮次，不要直接重跑覆盖本轮state；新轮次需要更换目录、PREFIX及私有Endpoint key文件。','',
 '本轮没有修改产品实现或集群配置。测试资源保留用于复查；登录JWT和Endpoint Key未写入报告/证据，Endpoint Key只在本机权限0600的临时文件中。']
(ROOT/'REPORT.zh-CN.md').write_text('\n'.join(lines)+'\n')
print('report written',counts,states)
