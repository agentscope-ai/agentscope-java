# 重新部署后关键场景集成测试

日期：2026-09-07（Asia/Shanghai）。前置核对开始于11:27，业务创建于11:29–11:35；完整状态采集于11:37，API关闭状态在11:39再次核对。资源前缀：`it-20260907-1130`。

## 结论

**本轮不满足完整验收要求。** 三种运行方式均能完成基础任务，Agent/Team Endpoint 的发布、调用和执行链路可用，但同一 Issue 的最新输入处理、重复投递、Team 失败收敛，以及 Endpoint 的结果/错误/会话状态仍存在明确缺陷。

覆盖14项业务场景、3项协议检查及1项能力边界检查。3项基础业务通过，其余11项至少存在业务结果、数据一致性、诊断或API交付缺陷，不能仅凭 Run succeeded 判为通过。协议鉴权与事件顺序/断点续读通过，幂等重试检查失败。

**版本前提：本次重启并未部署上一轮 worktree 的修复。** 当前进程确实于11:18:11启动，但启动的构建产物来自 `/Users/ken/agentscope-2/agentscope-java/agentscope-service/`。实际 aistiod 二进制中不含 `coordinatorChildren.outcomes`、`io.agentscope/toolCallId`、新的当前子 Issue 收尾守卫等标记；实际 dataplane JAR 内嵌 Core 的 `McpTool.class` 也不含调用 ID 元数据改动。部署目录源码同样未包含这些改动。源项目与构建记录仍为 `9c01530abe49c0202ca9ac3dc187381322045f50`，Go 构建为 `vcs.modified=true`，不能据相同提交号视为相同源码。详见 `evidence/baseline-build.json`。因此，本轮是当前实际部署版本测试，不能作为上一轮修复的部署验收。

## 执行范围与资源

- 真实使用网关18080登录管理员账号；`/api/auth/me`返回 `userId=admin`、`isAdmin=true`。所有管理员评论、mention、验收均使用此登录身份，而非伪造 Agent 或 human author。
- 创建5个Agent：3个Managed（lead/calc/analyst）、Hosted Qoder、Hosted Codex。创建纯Managed Team与Managed+Qoder混合Team各1个。
- 创建并发布6个Endpoint：Managed/Qoder Agent各有job与conversation服务，两个Team分别发布job服务；实际调用5个job和2个conversation的各2轮，共9个Invocation。
- 保留20个Issue（5个直接测试根Issue、5个Endpoint job Issue、8个委派子Issue、2个Hosted conversation内部Issue）、14个Run、36个Task/Attempt，以及相关27个Session的诊断。
- 最终Run：11 succeeded、2 failed、1 waiting。Task：31 completed、5 failed；Attempt：31 succeeded、5 failed，36个Attempt编号均为1。**没有仍在执行的测试Task/Attempt**；Host在线且active=0。唯一waiting Run已经没有任务能继续推进。
- 全量采集318条Run事件，并检查每个Issue的export、评论/路由/活动、Run图与事件、Task及inputs、Attempt、可关联的Session messages/events/turns。另验证Endpoint公开查询和SSE，而非只看管理员后台。
- 没有修改实现、运行时权限、模型或凭证，没有重启服务，没有伪造完成状态。成功且需要review的Qoder/Codex/Team根Issue由管理员验收为done；交互错误的Issue留在in_review，异常Team留存blocked/waiting现场。

## 场景结果

|编号|场景|实际结果与判定|
|---|---|---|
|S01|Managed单Agent Issue|初次19×21+1正确得到400；Task/Attempt/Run成功并进入in_review。基础通过，后续交互另见S04/S05。|
|S02|Qoder单Agent Issue|排序apple,pear,zebra正确；Run成功；管理员验收后Issue done。通过。|
|S03|Codex单Agent Issue|101为质数，试除2/3/5/7正确；Run成功；管理员验收后Issue done。通过。|
|S04|管理员在已回复的Issue中mention A，要求A→B→A|真实结构化路由与回复发生，但该回合预期3个Task、实际5个；B显式回复及完成又创建不同A任务，A完成再回投B。A未给出CROSS_ACK=144，回复旧题400。失败。|
|S05|管理员在同一Issue中明确更正为12×13|评论成功以human/admin身份投递；新Task的对应input已processed，但Qoder仍回答旧题19×21+1=400，且Run succeeded。失败。|
|S06|纯Managed Team正常分工|两个子Issue最终done，汇总400与排序正确，管理员验收根Issue done；但calculator持久result为399，和最终汇总400不一致，analyst又被重复执行。完整性不通过。|
|S07|混合Team同时调研新能源汽车与手机|确实创建两个行业子Issue。Qoder缺web工具，Managed web_search实际报TAVILY_API_KEY缺失，两成员均failed；协调员完成回合后Run仍waiting、根Issue blocked。未按要求失败收口。|
|S08|Managed Agent job Endpoint|input参数被正确处理，Task.result为API_TOTAL=144，Issue done、Run succeeded；公开结果仅有runState，没有144；该job的6条公开事件也不携带答案。API结果交付不通过。|
|S09|Qoder Agent job Endpoint|Task正确输出API_WORDS=4，Issue done、Run succeeded；公开Invocation.result仍仅有runState，缺业务结果。API结果交付不通过。|
|S10|Team正常job Endpoint|两成员完成、子Issue done、根Issue自动done、Run succeeded；SSE节点事件有最终汇总，但公开Invocation.result仅有runState。标准结果查询不完整。|
|S11|Team双行业研究job Endpoint|整体Run/Invocation最终failed，公开错误说明可见，根Issue cancelled；但新能源汽车worker将blocked=true和未产出来源作为completed/succeeded交付，另有失败协调Task的input仍delivered。内部状态不一致。|
|S12|Qoder缺web能力的job Endpoint|Task/Attempt/Run均failed，真实错误MISSING_WEB_TOOL可见于后台；公开Invocation却只有run_failed和空errorMessage。错误传播不完整。|
|S13|Managed conversation Endpoint连续两轮|答案和上下文记忆正确，Session两轮均completed；公开Invocation一直running，之后两轮均timed_out/endpoint_timeout。会话完成状态投影失败。|
|S14|Qoder conversation Endpoint连续两轮|答案及API_MEMORY_1130记忆正确，两轮公开Invocation completed；Session turns仍为空，第一轮历史Attempt丢失SessionRef，第二轮有链接。诊断完整性不通过。|

协议检查：无API Key调用返回401；相同Idempotency-Key及完全相同请求却返回409，任务完成后重复同一请求仍返回409，幂等重试不通过；改动请求返回409的阴性对照符合预期。各公开事件流的ID严格递增，Managed job用Last-Event-ID=4重连仅返回5、6，断点续读通过。

能力边界：Team conversation Endpoint创建明确返回400：`conversation endpoints require targetType=agent`。本版本Team可以发布job服务，不能宣称支持conversation服务。本轮没有已注册在线External Application，不虚报覆盖。

## 关键问题与复现证据

### P1：同一Issue的最新管理员输入未正确执行

Issue `e2db0375-8928-487e-b26c-4dfbfe656680`。管理员更正Comment `6e43e9e4-d08c-43be-9140-e988fd757926`要求停止旧任务并仅计算12×13，目标输出ADMIN_FINAL=156。Task `5c182cf6-06c1-47b0-989c-b3f003280f95` 的triggerCommentId、causationId和processed input均指向该评论，却持久化答案MANAGED=400；Run `1aa7449a-f945-4f96-a448-4d52da96bcf2`为succeeded。这证明创建和投递发生，不能把问题归为管理员没有正确mention。是否为上下文组装、优先级指令或模型决策问题，需进一步修复定位。

同Issue的跨Agent回合Run `58f9b768-65e6-47a6-a3d0-5804a7ed85a2`产生5个Task。B的explicit回复与完成follow_up分别生成两次A执行，A回复又自动唤醒B；最终受自触发抑制停止，没有无界循环，但多余执行且答案错误。

证据：`admin-start-cross-mention.json`、`admin-correction-156.json`、`final-issue-e2db0375-8928-487e-b26c-4dfbfe656680-export.json`、两个Run graph/events及Task详情。

### P1：Team失败收敛与成员失败语义仍不一致

直接研究Issue `f19740bc-3b68-42fb-8eb3-512c99521f03` / Run `fe5237e6-d65b-45e9-8ee7-8a8bcca7ea32`：两成员失败，5个Task/Attempt均终止，根Issue blocked、Run waiting；在多次快照中持续不变。能力不可用是预期测试输入，不能把整个Run未收口解释为仍在研究。

Endpoint研究Issue `16c4ec41-b449-5465-b26d-938007d72365` / Run `39f4f910-c409-5d4c-bca7-886ff2cb9ca5`虽最终正确failed，但Qoder成员持久result中明确blocked=true、无来源交付，Task/Attempt却成功。失败协调Task `05a9a05e-d54f-4955-b13c-d89e7aee4f0d`的input `f7d10d6b-1234-4bb1-a325-03aeddcabefc`仍delivered，未进入processed/deferred/blocked/dead_letter等结算状态。

两个行业均实际委派；Managed的手机分支真实调用web_search并得到缺凭证错误，Qoder的新能源汽车分支因工具目录缺少web能力无法发起联网调用。本轮没有完成行业研究，也没有用模型记忆或自行联网替代被测Agent的工具调用。

### P1：Endpoint公开结果和错误丢失

Managed成功job `d93a7354-e01d-4f5b-afe5-bdaee1f79cf5` 的Task.result为 `{"API_TOTAL":144}`，公开GET的Invocation.result却只有 `{"runState":"succeeded"}`，公开SSE的attempt/node/run succeeded均不含业务payload。Qoder成功job和Team成功job的标准结果查询同样只有runState；Team的SSE节点事件包含最终汇总，但不能补齐结果查询字段。

失败job `40a117dc-b99f-452a-bbcd-41c59e2df749`对应Run已保存MISSING_WEB_TOOL及完整解释；公开查询却保存 `errorCode=run_failed`、`errorMessage=""`。返回failed不等于完成了错误信息传递。

证据：`final-public-*-job-*.json`、`stream-managed-job-ok.json`、`stream-qoder-job-fail.json`与对应Run/Task详情。

### P1：Endpoint相同请求的幂等重试被错误拒绝

首次Managed job请求被接受并创建Invocation `d93a7354-e01d-4f5b-afe5-bdaee1f79cf5`。使用相同API Key、Idempotency-Key和完全相同的title/description/input重试，返回409并声称输入不同；任务完成后重试仍然如此。已比较保存的请求body，确认两次完全相等，没有重复创建执行资源，但客户端无法按约定可靠重试。

证据：`invoke-managed-job-ok.json`、`endpoint-idempotency-same.json`、`closing-idempotency-identical.json`。改变description的对照请求也返回409，但这个对照的正确拒绝不能掩盖相同请求的错误拒绝。

### P1：Managed Endpoint会话已完成，却最终公开超时

Conversation `62d9ac2e-7199-4d9b-bbcb-6800c1131bab`，SessionRef `5749abb8-19e0-49db-b8c9-f769909ad726`。两轮Session于11:30:50和11:33:04正常completed，消息包含正确标记与42；公开Invocation未同步完成，后续按Endpoint超时规则都成为timed_out。客户端可能已经收到了答案却仍等待，最终被告知超时。

证据：`final-session-5749abb8-19e0-49db-b8c9-f769909ad726-turns.json`、对应events/messages、`closing-public-managed-chat.json`。本场景Managed conversation没有独立Issue/Run/AgentTask，这是该调用模式的现有模型边界；已检查其Session/Turn/Event及公开Invocation，不以伪造执行记录补齐。

### P2：结果、错误诊断与历史链接不完整

- 正常Team calculator的持久结果399与要求/汇总400不一致；最终root成功和管理员验收不能消除中间交付数据的不一致。Analyst重复执行产生了新Task，而非同Task Attempt重试。
- 3条Managed协作工具失败事件的toolCallId为空。另有2次真实web_search缺凭证错误能在Session工具输出看到，但未进入对应Run的agent_tool.failed诊断。任务失败事件仍存在；这里指工具调用诊断缺项。
- Qoder conversation `3f26743f-86b4-41ac-9904-74018c5e0148`公开两轮completed且消息正确，Session turns为空；第一轮历史Attempt没有SessionRef。普通Hosted Issue Task未注册产品Session属于现有设计，不与此历史Chat链接缺陷混淆。

## 状态一致性核对

`final-checks.json`与`final-inventory.json`记录自动核对结果。所有Task的Run关联、Attempt的Task关联、当前Attempt与Task终态配对、Run事件sequence唯一且递增均通过；终态Run没有残留执行中的Task/Attempt。另发现上述1个无执行任务的waiting Run、3个缺toolCallId事件、1个失败Task未结算input。业务结果及公开API字段缺陷由场景断言补充，不被结构检查的通过掩盖。

成功的人工review工作经管理员验收进入done；错误交互根Issue留在in_review；可恢复的直接研究失败留在blocked，不能用人工accept掩盖。Endpoint job的自动完成策略与人工Issue的review策略分别核对。

## 证据与后续

- `state.json`：资源及调用入口；`final-inventory.json`：20个Issue、14个Run及全部Task/Attempt/Session和公开查询。
- `evidence/`：逐次HTTP请求响应、完整导出、事件与SSE、构建产物指纹；API凭证已脱敏，不包含在交付报告中。
- `final-checks.json`：结构一致性检查；Python脚本保留，可使用有效管理员和Endpoint凭证重复执行。
- 未修改本轮测出的失败数据、原有代码或集群配置。建议先确认上一轮worktree修复实际进入部署产物，再针对本轮新增的“最新评论执行”和“Endpoint幂等/结果/错误/Managed会话终态”缺陷修复并复测；仅重新构建未含改动的源目录不能完成修复验收。
