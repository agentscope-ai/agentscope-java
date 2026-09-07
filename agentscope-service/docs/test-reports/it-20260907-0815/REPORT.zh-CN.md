# 当前运行集群真实集成测试报告

测试日期：2026-09-07（Asia/Shanghai）。执行窗口：08:12–08:26；主终态采集开始于08:21:22，08:25复查两个异常Run仍未收敛。测试前缀：`it-20260907-0815`。

## 结论与范围

本轮已实际创建5个Agent、2个Team、11个根Issue、6个子Issue和2个Chat，真实调用现有网关API，检查Run、Task、Attempt、Comment routes、Session事件及服务日志。**16项场景：11通过、4失败、1阻塞。**“通过”按场景目标判定；预期的工具不可用导致研究失败，不计为平台功能失败。mention投递本身通过，但因多余执行，完整收敛场景判为失败。

11个根Issue及6个子Issue关联的12个Run：9 succeeded、1 failed、2 waiting；32个AgentTask/32个Attempt：31 completed/succeeded、1 failed，全部Attempt编号为1，未发现同一Task基础设施重试。上述统计不含Chat底层内部执行资源。Hosted两轮Chat另产生2个operational Issue、2个Run、2个Task；完整残留身份见chat-internal-resources.json及closing-chat-internal导出，因此保留Issue总数为19（17个显式测试/子Issue + 2个Chat内部Issue）。2个Chat共4次真实交互，消息正确。所有测试资源均保留，未修改/删除已有资源，未更改权限、模型配置、代码或服务，未重启/重建。工作树原有前端assets增删未触碰。

两个waiting Run已没有queued/running任务；这是本轮未收敛状态，不是仍在正常执行。保留现场，不人为改成成功或失败。报告中的代码分析仅解释已观察行为，不作为已部署版本的唯一证据，不开展修复。

## 集群与版本基线

- 实际服务：网关 `http://localhost:18080`，控制面8081（ASDP 15010），Managed执行面8082，调度器8083，PostgreSQL容器 `agentscope-dev-pg` 映射5432。网关登录后使用 `/api/v1/*`。
- 四个核心进程启动于09-06 23:37:22，PID分别为aistiod 15205、dataplane 15210、scheduler 15215、gateway 15224。控制面二进制来自源项目 `agentscope-service/aistio/bin/aistiod`，Java运行源项目target中的2.0.3-SNAPSHOT jar。
- 源项目和本worktree HEAD均为 `9c01530abe49c0202ca9ac3dc187381322045f50`（fix(service): make direct agent mentions converge）。`go version -m`从**正在运行对应二进制文件**读出同一revision，且`vcs.modified=true`：不能声称二进制精确等同干净提交。Java jar有构建时间、大小和SHA256，但未获得可确认的嵌入提交，源码与部署细节一致性保留此限制。
- 所有4个artifact SHA256、mtime、进程开始时间、两个工作区git状态保存在 `evidence/baseline-build.json`。
- Managed实际模型日志为`qwen-max`，使用已有owner=admin的`default-local`环境。未改模型、Environment或工具权限。
- 当前Runtime Host在线，ID `348b0331-630a-49dd-93c0-ef553858b8ae`，daemon 0.2.0，darwin/arm64，capacity=1；提供Codex CLI 0.152.1、Qoder 1.0.37。Runtime options的profile/pool直接用于创建Hosted Agent。
- Qoder沿用已有`auto-qoder` profile：`allowedTools=[mcp__agentscope-collaboration__*]`、`strictMCPConfig=true`、`permissionMode=default`。实际provider system事件工具清单仅有协作工具；没有WebSearch/WebFetch/Bash。本轮没有发生“可见联网工具被运行时拒绝”的调用：覆盖的是**工具从目录中缺失**路径，不能把它写成网络请求已发出或权限拒绝已验证。
- Codex沿用`auto-codex` profile，sandbox=workspace-write。单Agent正常题真实完成。未为测试另起Host/External服务。
- Multica Docker前后端分别在3000/8080；本轮AgentScope业务API由18080提供，没有将Multica另一套数据误当成本轮集群。遵守源项目AGENTS.md；关联项目AGENTS.md/CLAUDE.md已阅读。本轮未操作UI，页面视觉展示未验证，错误“可见性”指API/导出/事件层。

## 资源清单

|角色|类型|Agent ID|
|---|---|---|
|lead|Managed|`068b4f8a-39c2-4482-b328-5d46cf28c113`|
|calc|Managed|`2dc24303-742b-4ee8-9f1f-b0f5ff2a47e7`|
|analyst|Managed|`e0511f1c-4e0d-4b3b-ae93-58e181bfdede`|
|qoder|Hosted qoder|`679b7bf9-c9c1-4828-a129-4045be75090e`|
|codex|Hosted codex|`cb3a3959-a01c-42df-a510-a9872e83cf41`|

Agent名称均为前缀加角色。lead负责协调，calc负责运算，analyst负责文本分析，qoder负责研究，codex负责独立复核。均由真实创建API返回身份。

|Team|ID|成员|
|---|---|---|
|managed|`e711dab4-812f-44ba-9a7a-4474f0cd48b0`|lead + calc/calculator + analyst/analyst|
|mixed|`7089f084-903e-4bf2-b395-2f610e24e81a`|lead + qoder/researcher + calc/calculator|

## 场景结果

|编号|场景|判定|实际结果|
|---|---|---|---|
|S01|单Managed计算|通过|17×23+19=410，run succeeded，1次Attempt。|
|S02|单Managed分析|通过|甲+25%、乙−10%，2024销量乙18>甲15；1次Attempt。|
|S03|单Hosted Qoder|通过|8×7−6=50，run succeeded，1次Attempt。|
|S04|单Hosted Codex|通过|97为质数，试除2、3、5、7；run succeeded，1次Attempt。|
|S05|Managed两轮Chat|通过|同一session记住MEMORY_0815，两轮答案均42；turns返回2个completed。|
|S06|Hosted两轮Chat|通过|同一session记住MEMORY_0815，两轮答案均42；消息功能通过，turns诊断缺项另记。|
|S07|纯Managed Team正常分工与汇总|失败|2名worker均完成；累计7个task/attempt全完成，root仍in_progress、run waiting/node_wait，无TEAM_OK汇总。|
|S08|Managed+Hosted混合Team正常题|通过|真实委派2个子Issue，均done；最终MIXED_OK;BATTERY=25%;SUM=300，run succeeded。执行中有3次协作工具错误：兄弟Issue读取not found、worker未结束时提前complete、子Issue未验收时提前complete，另记。|
|S09|手机研究工具缺失与错误传递|通过|原始研究run failed/websearch_tool_unavailable；Issue一度blocked，错误评论可见，未编造来源。此“通过”指预期失败路径通过，非研究成功。|
|S10|Team微服务研究与最终失败状态|失败|Hosted明确无联网工具，却task.complete成功交付失败说明；leader接受研究子Issue为done，未调用run.node.fail，root仍in_progress、run waiting。|
|S11|Managed成员A→B→A mention|失败|真实explicit路由、B排序和A ACK均成功；预期3次执行，实际4次，ACK完成自动follow_up再唤醒B。最终succeeded。|
|S12|Hosted↔Managed成员mention|失败|真实A→B→A，CROSS_B=144及CROSS_ACK；同样额外自动唤醒B，4次执行后succeeded。|
|S13|只有@文本的阴性对照|通过|无assignee、mentions=[]时无routes、无task/run；08:16:32至08:18:18期间未唤醒Agent。|
|S14|结构化mentions阳性对照|通过|同一Issue添加mentions=[{type:agent,ref:analystId}]，产生task并返回STRUCTURED_WAKE=OK，run succeeded。|
|S15|失败后继续交互|通过|同一失败Issue结构化mention提交离线9×9，生成新run并返回RECOVERED=81；新run succeeded，旧run failed仍保留。|
|S16|External Application类型|阻塞|现有集群无External Agent注册/在线实例、无运行中的External应用；不新起运行时冒充现有部署，不虚报覆盖。|

## 已发现问题与影响

### IT-01（P1）：正常Team没有汇总收口，全部执行成功但Run长期waiting

S07两个Worker在08:15:45、08:15:47分别完成。leader follow-up先尝试读取兄弟Issue，再读取父Issue评论，收到4次`store: not found`；模型未验收当前子Issue，就完成follow-up并声明等待/人工干预。另一次follow-up看不到排序的实际交付内容，要求analyst重交，产生第三次worker执行；最后一个leader再次说等待早已完成的calculator。最终7个Task全部completed、7个Attempt均succeeded，而两个子Issue和根Issue都in_progress，Run waiting/node_wait，根Issue仅留下初始委派摘要。

证据：`final-team-managed-export.json`、对应final graph/events，以及`final-session-*.json`。首个错误task `e62ef0ac-30a5-4684-9252-0d14f9b5e4e6`，末个follow-up `401619d0-961f-4854-b8ae-566fbc18740e`完整ID同时保存在inventory。没有Task在运行意味着继续轮询没有可证明的进展来源。08:25复查仍waiting，距首次提交约10分钟；最后一个Task于08:17:58完成，已静止约7分钟。S10最后Task于08:19:34完成，08:25仍waiting。复查见closing-*-graph.json。

已验证的边界：MCP工具只允许当前task的Issue，传兄弟/父Issue返回not found；这不是这些Issue不存在（人类API可读取）。工具task.get提供协调上下文，但仅当前子Issue及done/cancelled兄弟带结果，未验收兄弟没有result body。源码解释与真实返回吻合。模型未按已存在角色协议先验收当前结果，是行为层失败；是否应改上下文、工具界面、模型或收敛保障需统一规划，不能直接归因“应该放宽权限”。

### IT-02（P1）：Worker的result与summary分离，协调员结果上下文可丢失实际答案

S07 analyst调用`task.complete(result="SORT=apple,banana,cherry", summary="已将字符串按字典序排序。")`，Task.result确有完整答案，但result Comment只保存summary。leader的task.get → coordinatorChildren.results返回的是Comment，仅看到“已排序”，随后要求成员重交格式化结果。重交仍只在Task.result中有排序字符串。完整答案可以从人类Run graph查询到，不能称数据库丢失；缺口在leader默认读取的结果投影。

源码`collaboration/service.go` CompleteTask优先采用summary写Comment，addCoordinatorContext只聚合result Comments；真实工具输入/输出可复核。该链路是本轮重复工作与无法汇总的重要成因之一，不是唯一已证明根因。

### IT-03（P1）：研究能力不足被Team成功交付/验收，未传播为要求的Run失败

S10 Qoder实际运行并发现工具清单没有联网能力，返回`NO_WEB_SEARCH_TOOL_AVAILABLE`，零来源；但调用task.complete而非task.fail，Attempt succeeded。leader对研究子Issue调用issue.accept，状态done，尝试run.node.complete。另一个计算子Issue未验收，收口被`coordinator has active child issue ...`拒绝；leader最终完成自己的Task并声称计算状态未知/需要人工干预。研究目标明确要求不能验证来源就Run failed，实际却waiting且failureCode为空。

这里“没有联网工具”是预期环境能力限制；把无法研究视为满意完成，以及未按要求fail coordinator，是失败语义/决策缺陷。收口守卫拒绝仍有active child的完成请求是正确保护，未发生整个Run伪成功。S09同一Qoder在单Agent场景正确调用task.fail，表明差异与Team执行指令及角色有关，不能归因该模型一概无法报告失败。

### IT-04（P2）：结构化回复ACK仍触发额外自动执行

S11和S12均可看到Agent署名、sourceTaskId、explicit route、目标task ID及后续工具调用，是真实A→B→A，不是@文本。B显式回复与其完成消息成功coalesced到同一个A任务，这是正确的去重。可是A的ACK task完成后，系统又以follow_up路由把结果投给B，形成第四次执行，尽管任务指令要求ACK即结束。S12 A没有再调用comment.add/mention，额外执行仍发生，更能排除只是模型多写了一次mention。最终第四次完成被self_trigger suppressed，Run succeeded，没有无限循环。

S11 A另有一次误mention自己被self_trigger blocked；S12无此歧义。源码completionTargets对`RouteAssignee`有终止逻辑，但explicit回复继续走ParentTaskID follow_up，与观察一致。记录为过度唤醒/收敛缺陷；并未验证无界循环。

### IT-05（P2）：Managed工具错误诊断不完整、关联ID为空

S07有5个持久`agent_tool.failed`，S08成功Run仍保留3个，S10有3个；错误摘要和导出留存可用。**11个事件的payload.toolCallId均为空**，但Session消息包含实际toolCallId，跨层不能稳定一一关联。

S07一次issue.comment.add误传issueId，被本地JSON Schema拒绝：“架构中未定义属性issueId，架构不允许附加属性”。Session明确保存错误和toolCallId，但Run events/export toolFailures没有对应事件。网络MCP服务端错误可见，本地参数校验错误漏投影。该次模型修正参数后调用成功，因此不能凭最终Attempt succeeded断言没有工具失败。

### IT-06（P2）：运行类型之间Session诊断能力不一致

Managed Chat的turns返回2条completed，而Hosted Qoder Chat明明messages包含4条消息、两个真实完成回复，`GET /api/v1/sessions/<id>/turns`返回200和空turns。两个Chat的`/tasks`都返回unsupported，理由“task-query requires a registered instanceRef”。Hosted Issue Attempt有runtime/provider session ID和完整provider事件，但sessionRef为空；Managed Attempt有可用sessionRef。结果/事件仍可从Run取到，尚未UI复核，不声称Hosted会话完全丢失。

### IT-07（P3）：Managed MCP初始化产生HTML/SSE警告

多次新Managed session启动时日志记录`McpTransportException: Invalid SSE response. Status code: 200 Line: <!doctype html>`，之后协作MCP工具仍注册成功且真实可用。观察为初始化协议/路由噪声；未证明它导致业务失败。源码/网关回退细节未进一步验证根因，不提出修复。

## 正确工作的保护与恢复

- Structured mentions真的调度，纯@文本不调度；自触发被blocked/suppressed，重复结果可coalesce。
- Mixed Team真实Managed+Hosted协作成功。Host容量1导致`policy runtime candidate 0 is unavailable`排队，随后自动dispatch_recovered并执行，属于可恢复容量等待。没有增加容量或权限。
- 并行成员结果引发的leader follow-up通过“Team leader follow-up already active”串行等待并恢复；没有同一Task多Attempt重试。多次不同Task不等于同一Attempt重复执行。
- Coordinator不能在仍有active worker/child时被错误完成；失败保留在Run诊断中，即使最终run succeeded。
- 手机研究错误代码、错误说明、失败评论和failed Run均持久；同Issue下一条真实用户mention生成新Run，离线题成功，原失败历史未被覆盖。研究来源确实为零，不通过本测试人员联网补答案。

## 复现与证据索引

所有请求的时间、method、path、body、HTTP状态、脱敏response在`evidence/http.jsonl`；创建请求和每个场景初始输入有独立`create-*`/`submit-*`文件。`state.json`记录原始创建响应及Chat IDs，`inventory.json`包含最终图、完整Task/Attempt/Issue IDs与状态。`final-*-export.json`同时包含评论、活动、子Issue、Run诊断；`final-*-graph.json`用于避免把根Issue export.tasks（只含直接关联task）误当完整Team执行集合。`final-session-*-messages.json`与events提供Managed工具调用和结果；Hosted的工具目录与调用在Run的`attempt.provider_event`。`logs-{data,control,scheduler,runtime-host}.txt`为时间过滤日志，`baseline-*.json`为原始基线。

人工复现：使用有权限的登录会话，对网关POST `/api/v1/agents`，分别用managed binding及现有Hosted profile/pool；POST `/api/v1/teams`，leader单独定义、members不重复包含leader；POST `/api/v1/issues`使用`assigneeType=agent/team`与对应UUID。严格按本报告附录输入提交。随后GET `/api/v1/issues/<id>/export`及 `/api/v1/orchestration-runs/<runId>/graph`、`events?limit=500`。mention必须在`/issues/<id>/comments` body提供`mentions:[{type:"agent",ref:"目标Agent UUID"}]`，仅内容@名称不能替代。

采集脚本仅为测试/报告辅助文件，不是产品变更。`probe.py`为基础HTTP及脱敏；`create.py/submit.py/submit_more.py/chat_start.py/followups.py/mention_mixed.py`记录实际操作，**不要直接重跑固定前缀创建脚本造成重名数据**，新轮复现应使用新前缀并重新保存ID。脚本中的复查操作使用登录环境变量，不附凭证。测试探索中`/api/models`返回404、一次policy读取遗漏scope返回400，以及snapshot对无agentTask处理错误，均为测试器自身探测/脚本问题，已修正采集，不算产品缺陷。

本轮不含独立浏览器视觉验收、负载测试、长期租约故障注入、真实External实例执行或“工具存在但运行时deny”的分支。没有对缺失功能假报通过，也未用历史报告的成功代替本轮证据。

## 待讨论的总体规划问题（未实施）

1. Agent类型和能力如何表达：绑定/Host宣称能力与实际每次运行工具目录如何统一展示，如何区分在线、按需执行、无容量、缺工具和缺External实例。
2. Team交付契约：summary、结构化result、实际产物、验收条件是否应分开但都能被leader读取；一个研究子任务无法取得来源应是failed、blocked还是降级交付。
3. leader follow-up应持有哪一层上下文，如何理解“当前子Issue可写、兄弟只读”；在所有worker结束但coordinator未决时，应该如何明确表示人工待办/失败/等待。
4. mention对话如何明确停止：业务消息、结果、ACK与自动follow-up如何区分，避免靠self_trigger碰巧收敛。
5. 三类运行时的Session、Turn、Attempt、工具错误事件如何保持可比较；本地校验/目录缺失/运行时拒绝应如何独立记录。

## 场景原始输入与完整资源ID

### S01 单Managed计算

Issue：`4923de41-6abe-4da7-90db-d5d971f9c8cd`。最终Issue：in_review。

Run：`b359e38c-67d1-4d16-9ac5-cc96780d864f` (succeeded)。

实际初始输入：

> 计算 17×23+19，给出一个等式和最终答案。不要使用工具。预期输出 CALC=410。

### S02 单Managed分析

Issue：`aec979fd-14dc-4e5f-8e41-496cd81e6291`。最终Issue：in_review。

Run：`9312eb0e-83f5-4b55-b9f2-bc8ce155f35e` (succeeded)。

实际初始输入：

> 只依据给定材料：甲2023年销量12，2024年销量15；乙2023年销量20，2024年销量18。计算两家增速，并按2024销量降序排列。不要使用工具。

### S03 单Hosted Qoder

Issue：`a461c301-29bb-4ff4-b596-cbd391f7e55e`。最终Issue：in_review。

Run：`e1019f36-5a70-4256-b14b-5682cbea243d` (succeeded)。

实际初始输入：

> 计算 8×7-6，仅输出 QODER=50。不要调用任何工具。

### S04 单Hosted Codex

Issue：`2f9d88f9-1d5a-46de-8a58-6cc79e4cd8d5`。最终Issue：in_review。

Run：`f4225a25-aa68-4fd0-adf9-65b7e871fe8b` (succeeded)。

实际初始输入：

> 判断 97 是否为质数，说明只需要试除哪些质数。不要联网或使用工具。最终包含 CODEX=prime。

### S05 Managed两轮Chat

Chat：`a28247b4-72cb-48ca-ac65-e049b822be36`；Session：`dab956bd-44aa-40cc-a463-66b13b3eed3c`；Runtime session：`sess_e70c952738e2`。

第1轮：记住测试标记 MEMORY_0815。计算21+21，仅回答标记和答案。无需工具。

第2轮：上一轮我让你记住的测试标记是什么？另外计算6×7。仅输出标记和答案，不用工具。


### S06 Hosted两轮Chat

Chat：`ed996a03-d23e-4d87-a7f2-f563e3e3e08e`；Session：`16ce7d22-a889-4013-892e-ce8e44006ec2`；Runtime session：`c9ef057a-a57f-43ce-ab00-a2dfddc7a14f`。

第1轮：记住测试标记 MEMORY_0815。计算21+21，仅回答标记和答案。无需工具。

第2轮：上一轮我让你记住的测试标记是什么？另外计算6×7。仅输出标记和答案，不用工具。


### S07 纯Managed Team正常分工与汇总

Issue：`b7496c7b-646d-4e1c-b241-2d1845ca8225`。最终Issue：in_progress。

Run：`7e124d99-47db-41c8-8507-1400eb728a71` (waiting)。

实际初始输入：

> 请实际分工并汇总。将17×23+19交给calculator成员，要求输出 CALC=410；将字符串 banana,apple,cherry 按字典序排序交给analyst成员，要求输出 SORT=apple,banana,cherry。必须真实创建两个子Issue并分配给指定成员，不得自己替代。收到两份结果后验收，最终输出 TEAM_OK;CALC=410;SORT=apple,banana,cherry 并完成run。子Issue标题使用 it-20260907-0815 前缀。

### S08 Managed+Hosted混合Team正常题

Issue：`b268c889-b18d-4da7-8ee2-b80f15794134`。最终Issue：in_review。

Run：`6c6253a0-92c4-44b1-a39c-ae4fb0321dfc` (succeeded)。

实际初始输入：

> 请实际分工：researcher成员只根据给定事实“手机A电池5000mAh，手机B电池4000mAh”计算A比B多多少，期望 BATTERY=25%；calculator成员计算100+200，期望 SUM=300。必须分别真实委派两个子Issue，收集并验收后输出 MIXED_OK;BATTERY=25%;SUM=300 并完成run。无需联网或额外工具。子Issue标题前缀 it-20260907-0815。

### S09 手机研究工具缺失与错误传递

Issue：`5ce54819-6a31-41a8-9cd2-a8139a9802b4`。最终Issue：in_review。

Run：`ace2548d-ae47-43d9-9bec-c7132318b20f` (succeeded), `6cd7a1e8-f797-401c-aee2-4c67b606f40b` (failed)。

实际初始输入：

> 请研究截至2026年9月的手机行业趋势，必须实际尝试WebSearch或当前可用联网工具搜索“2026 smartphone market shipments”，取得2条2026年发布来源，含标题、日期、URL。不允许用记忆替代、不安装工具、不修改权限或配置。若工具被拒绝，保留错误并明确未完成实时研究，结束本轮，不无限重试。

### S10 Team微服务研究与最终失败状态

Issue：`56ae8c89-9ca7-4438-8ed5-698707719695`。最终Issue：in_progress。

Run：`1ef6ff35-6ccb-48a7-be15-2bfabc79ec95` (waiting)。

实际初始输入：

> 请研究截至2026年9月的微服务行业趋势。必须由researcher成员实际尝试当前可用的联网/检索工具，取得至少2条2026年发布来源，包含标题、日期、URL；不要仅凭训练知识给答案。明确要求researcher先尝试一次WebSearch搜索“2026 microservices trends”，若不存在此工具，尝试可用的等效联网工具；工具被拒绝或不存在就如实报告工具名和错误，不得绕过权限、请求扩大权限、安装工具或修改配置。calculator成员只计算工具无关的基线2+3=5。协调员实际委派并收集结果；如果无法核实2026来源，明确判定研究目标失败并终结run为failed，禁止假装研究成功。子Issue标题前缀 it-20260907-0815。

### S11 Managed成员A→B→A mention

Issue：`559c0954-e48a-4625-b967-1be8af13453c`。最终Issue：in_review。

Run：`25c032fe-9dcb-4e9a-8331-a2c22b6a8d08` (succeeded)。

实际初始输入：

> 测试真实成员间mention。你是A（2dc24303-742b-4ee8-9f1f-b0f5ff2a47e7），B是文本分析员（e0511f1c-4e0d-4b3b-ae93-58e181bfdede）。先用 issue.comment.add 创建一条 comment：content="请对pear,apple进行排序，返回 MENTION_B=apple,pear，并用结构化mention回复A一次。", mentions=[{"type":"agent","ref":"e0511f1c-4e0d-4b3b-ae93-58e181bfdede"}]。这必须是真实工具调用，不能只输出JSON或@文字。然后立即结束本次任务，勿本地等待。B收到后必须用issue.comment.add真实回复一次，并结构化mention A（2dc24303-742b-4ee8-9f1f-b0f5ff2a47e7），消息中含MENTION_B=apple,pear。A被回复唤醒后，只输出 MENTION_ACK，禁止再次mention任何人，以免循环。

### S12 Hosted↔Managed成员mention

Issue：`d5d34cec-ce1f-4aa0-9572-668d50771343`。最终Issue：in_review。

Run：`1a41d083-e533-492d-998c-d9dad5667a9d` (succeeded)。

实际初始输入：

> 测试同属混合Team的两个成员跨运行时真实mention：你是Hosted A=679b7bf9-c9c1-4828-a129-4045be75090e，Managed B=2dc24303-742b-4ee8-9f1f-b0f5ff2a47e7。初次执行：用issue.comment.add发送一次结构化mentions=[{"type":"agent","ref":"2dc24303-742b-4ee8-9f1f-b0f5ff2a47e7"}]，content="这是给B的新指令：计算12×12，输出 CROSS_B=144；再用issue.comment.add结构化mention回复A=679b7bf9-c9c1-4828-a129-4045be75090e一次，然后完成你的任务，不要执行原始A的发起步骤。"。A发出后立即结束本次任务，勿等待。A收到B回复后仅输出 CROSS_ACK 并完成任务，不再调用comment.add，不再mention。B只执行收到的comment的新指令。禁止重新执行已完成的发起步骤。

### S13 只有@文本的阴性对照

Issue：`d288e78c-be35-4297-9912-654dc1ee1d5b`。最终Issue：in_review。

Run：`df28d494-cc4f-4e10-b886-500a6754eeee` (succeeded)。

实际请求：

```json
{
  "content": "@it-20260907-0815-analyst 请只输出 TEXT_ONLY_SHOULD_NOT_WAKE",
  "mentions": []
}
```

### S14 结构化mentions阳性对照

Issue：`d288e78c-be35-4297-9912-654dc1ee1d5b`。最终Issue：in_review。

Run：`df28d494-cc4f-4e10-b886-500a6754eeee` (succeeded)。

实际请求：

```json
{
  "content": "这是新的结构化mention对照，请只输出 STRUCTURED_WAKE=OK，不要再mention任何人。",
  "mentions": [
    {
      "type": "agent",
      "ref": "e0511f1c-4e0d-4b3b-ae93-58e181bfdede"
    }
  ]
}
```

### S15 失败后继续交互

Issue：`5ce54819-6a31-41a8-9cd2-a8139a9802b4`。最终Issue：in_review。

Run：`ace2548d-ae47-43d9-9bec-c7132318b20f` (succeeded), `6cd7a1e8-f797-401c-aee2-4c67b606f40b` (failed)。

实际请求：

```json
{
  "content": "继续测试：接受本轮无法联网的事实，停止研究。现在请只计算9×9，回复 RECOVERED=81，不用任何联网工具，也不请求改变权限。",
  "mentions": [
    {
      "type": "agent",
      "ref": "679b7bf9-c9c1-4828-a129-4045be75090e"
    }
  ]
}
```

### S16 External Application类型

见baseline-agents、runtime-options、hosts和进程证据；现有部署缺运行条件。

## 保留资源与收尾

全部5个Agent、2个Team、17个测试Issue、2个Chat及2个Chat内部Issue均保留供复查；没有清理其他数据，也未取消或强行收敛两个waiting Run。下表是需重点复查的运行。

|场景|Run|最终状态|未完成事项|
|---|---|---|---|
|team-managed|`7e124d99-47db-41c8-8507-1400eb728a71`|waiting/node_wait|无活动Task，coordinator决策未收口|
|research-microservices|`1ef6ff35-6ccb-48a7-be15-2bfabc79ec95`|waiting/node_wait|无活动Task，coordinator决策未收口|

其余运行均已终态。再次测试不应复用这些等待中的Run掩盖本轮失败。结束时没有测试执行进程继续轮询；仅运行集群原有服务保留。
