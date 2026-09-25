# Issue 协作终态契约

本文是 v4 终态中 Issue-first 持久协作层的精简运行契约。历史设计依据见 [Issue-first v3 计划](../multica-issue-collaboration-plan.md)，统一执行与编排契约见 [Orchestration v4](./orchestration-v4.md)，API 路由以 `aistio/internal/httpapi/server.go` 为准。

## 1. 事实、执行与通信

- Issue 是长期工作、状态、验收、parent/child 和外部 source reference 的事实源。
- Comment 是 human/Agent 的持久 discussion。reply 通过 `parentId` 连接，`threadRootId` 固化 thread；排序和分页使用稳定 cursor，不依赖内存消息顺序。
- Mention 与 CommentRoute 是独立记录。Comment、mentions、routes、AgentTask/input、human Inbox、Activity 和 outbox 在一个数据库事务内提交。
- AgentTask 是某个稳定 Agent 身份的一次工作义务；ExecutionAttempt 是 Managed、External Application、Hosted Runtime 共用的一次物理尝试，统一承载 generation、取消、超时、重试、结果与 usage。Session 只提供运行连续性。
- Agent 间需跨运行可靠协作时写 Comment + mention；Session chat 不是可靠协作层。
- 文件字节不进入 Comment 或 outbox JSON。上传到 Artifact provider 后，以 ArtifactLink 关联 Issue、Comment 或 AgentTask；另一个隔离 runtime 通过 task-scoped API 下载。

## 2. 身份与 scope

所有协作记录都保存 `tenant` 和 `namespace`。collection API 必须显式提供二者；UUID API 先读取对象的持久 scope，再授权，query 参数不能覆盖它。

Human 使用正常登录身份。Agent 只能使用 `X-Agent-Task-Token`：token 绑定一个 AgentTask，只能读取自己的 Task、Issue、discussion、Team 和可访问 Artifact，并能 ack/start/progress/respond/complete/fail、创建允许的 child Issue、请求与该 Task 相关的 Approval。共享 `X-Builder-Internal-Token` 不能访问协作平面。

ASDP stream identity 是 `(tenant, namespace, agentName, instanceId)`。External AgentTask 上报还必须匹配 dispatch snapshot 中选择的 AgentInstance UUID；同 namespace/instance 的跨租户连接不会覆盖或互相接收 task wake。

## 3. Inbox 中的 “you”

Inbox 是 human attention queue，不是 Agent mailbox。“you / your follow-up” 指当前登录并通过授权的 human operator，其稳定用户 ref 必须等于 InboxItem 的 `recipientRef`。服务端忽略客户端传入的 recipient，并始终按当前 operator 过滤；Agent/task token 不能 list、read 或 archive Inbox。

Agent 工作只进入 AgentTask queue。human Inbox 承载 human mention、approval、review request、blocked、failure、budget 和 dead-letter attention。

## 4. Issue 验收

Agent/Team Issue 进入 `done` 前必须满足：无未完成 child Issue、无未完成 AgentTask、无未终态 OrchestrationRun、无未覆盖 input，并且至少有一个未删除的 result Comment。Team `requireReview=true` 时，只有 human actor 能最终完成 Issue。Run 终态不会自动改变 Issue；验收始终是独立动作。

`acceptanceCriteria` 支持以下稳定 JSON schema；未知字段会原样保存，但不产生隐式执行语义：

```json
{
  "requiredResult": true,
  "minimumArtifacts": 1,
  "minimumApprovals": 1,
  "checklist": [
    {"id": "tests", "text": "All tests pass", "required": true, "satisfied": true}
  ]
}
```

`required` 省略时默认为 `true`。Artifact 必须以 targetType=`issue`、targetRef=`issueId` 关联；Approval 必须是 targetType=`issue`、targetRef=`issueId` 且状态为 approved。

## 5. Team policy 与用量

Team 是持久 leader + role/member roster。Issue assign/mention Team 时只先唤醒 leader；worker 由 leader 通过 mention 或 child Issue 延迟启动。worker result/progress 会通过 follow-up route 唤醒 leader。

typed TeamPolicy 包含 active task、fanout、hop、child depth/count、retry、Artifact size/media type、Issue SLA、Task timeout、review、PII/secret，以及 `maxIssueTokens`、`maxIssueCostMicros`。AgentTask result 可报告：

```json
{"usage":{"totalTokens":1200,"costMicros":3400}}
```

也接受 snake_case 及 input/output、prompt/completion token 字段。完成时按 Issue 已完成 Task 聚合；超限 Task 以 `budget_exceeded` fail，并生成 human attention item。

## 6. 可靠性边界

- queued Task 合并新 input；running Task 收到新 input 时创建 successor，输入不会被静默吞掉。
- input 状态为 planned/delivered/acknowledged/processed/deferred/retrying/dead_letter/blocked；每个 agent route 必须能反查 Task/input 或明确 blocked reason。
- outbox 仅负责唤醒，数据库是事实源。重复投递通过 event/task/input identity 幂等；失败指数退避并在上限后 dead-letter + human Inbox，可人工 replay。
- 每个新 AgentTask 都在同一领域事务写入 `agent-task.queued.v1`。多副本 outbox worker 通过 worker lease + `SKIP LOCKED` claim 加载持久 Task，然后调用唯一 `RuntimeBindingResolver`；Task 的 CAS 状态是重复 outbox 投递的幂等 fence。目标未配置或暂时不可调用时保持 durable、重试并最终 dead-letter，不要求 Comment 提交时目标实例在线。
- complete 在同一事务内 reconcile processed/deferred inputs、写 result Comment、生成 successor/follow-up/review route 和 Activity。
- WebSocket 只做 Console cache invalidation；断线后 REST polling 仍保持正确性。

AgentTask 的逻辑 claim 是数据库 CAS；物理执行 lease 位于具体 runtime attempt：External 固定 dispatch snapshot 中的 AgentInstance，Hosted 使用 ExecutionAttempt lease/fencing，Managed 固定 Session binding。不得用一个可变的 AgentTask lease 偷换已固定的实例或执行尝试。

## 7. 标准 Agent 工具入口

除 REST、CLI 和各语言 SDK 外，task-scoped Agent 可调用 `POST /mcp/collaboration`。该 JSON-RPC/MCP endpoint 提供 `issue.get`、Comment list/add、child Issue、Artifact upload/download、Task get/progress/respond/complete/fail、Team get 和 Approval request。它调用同一 collaboration/store 服务，不创建第二套状态机；human credential 不能冒充 task token，task token 也不能越过自己的 Issue/Task scope。

Team member 可保存经过 schema 校验的 RuntimeBinding candidate override。Console 支持 Managed owner/Agent、External selector、Hosted profile/pool；没有 node 或 member override 时 Resolver 必须读取该 Agent 的 `AgentRuntimePolicy`。策略缺失、candidate 不可用或 capability/security 不匹配时 fail closed，绝不隐式选择任意同名 External AgentInstance。跨 backend 只在 policy 显式配置 `fallbackMode=fresh` 后发生。
