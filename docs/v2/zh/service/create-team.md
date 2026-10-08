---
title: "创建与运行 Team"
en_link: /v2/en/service/create-team
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Team 将多个已注册 Agent 组织成一个可被分派和调用的协作单元。Leader 理解目标、选择成员并汇总交付，成员通过任务与讨论交换结果。业务应用只需提交目标和输入，不必把团队内部每次委派改写成前端流程。

Managed、External、Hosted Agent 可以按各自能力加入同一 Team。先确认每个 Agent 已注册、可执行任务，且运行时支持所需协作协议；组成 Team 不会自动为运行时增加工具或协议能力。

第一次建团队时，可以按[托管 Agent 教程](/v2/zh/service/create-managed-agent)分别创建资料助手和复核助手，让两个 Managed Agent 完成本页流程。Team 不依赖 External 或 Hosted；需要已有能力时再添加对应成员。内部 Subagent、Team 与 Workflow 的区别见[协作方式选择](/v2/zh/service/orchestration)。

<span id="teams"></span>
<span id="本章节"></span>
<span id="api-与资源关系"></span>
<span id="试运行与发布"></span>

## 检查就绪度

| 状态 | 含义与处理 |
| --- | --- |
| Ready | 当前配置与成员能力满足就绪检查，可进行小任务验证 |
| Degraded | 部分成员或能力不可用，阅读每个成员的原因 |
| Unavailable | 当前无法开始有效协作，优先修复 Lead 或运行时依赖 |

成员可能使用不同运行方式。配置 Runtime policy 或成员覆盖前，确认所需能力、目标运行时及安全约束都能满足；更多候选运行时不意味着无损迁移会话。

## 创建一个复核团队

以下示例使用 Bash、`curl` 和 `jq`。先按[认证与空间](/v2/zh/service/api-reference#认证与空间)准备 `SERVICE_URL`（Service 地址）、`TOKEN`（用户 Bearer token）、`TENANT`、`NAMESPACE`，并定义请求函数：

```bash
api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' "$@"
}
```

准备资料助手和复核助手，将其 ID 分别设为 `LEADER_AGENT_ID`、`REVIEWER_AGENT_ID`。下面让 Leader 整理材料，成员负责复核。Leader 不需要重复列入 `members`，每个成员的 Agent ID 和 role 必须唯一。

```bash
created=$(api "$SERVICE_URL/api/v1/teams" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
  --arg leader "$LEADER_AGENT_ID" --arg reviewer "$REVIEWER_AGENT_ID" \
  '{tenant:$tenant,namespace:$namespace,name:"资料复核团队",leaderAgentId:$leader,
    instructions:"Leader 整理材料后委派 reviewer 检查证据与缺失项，按反馈修订并交付统一报告。",
    policy:{maxActiveTasks:3,maxFanout:2,requireReview:true},
    members:[{agentId:$reviewer,role:"reviewer",instructions:"检查事实、证据及未确认信息。"}]}')")
TEAM_ID=$(jq -r '.team.id' <<<"$created")
api "$SERVICE_URL/api/v1/teams/$TEAM_ID/overview"
```

`instructions` 描述协作方式，成员的 `instructions` 说明各自职责，`policy` 约束并发、委派与验收。概览返回团队的配置和运行情况；创建成功不代表每个运行时已经在线，应进一步核对成员可用性并执行一个小任务。完整策略见 [Team 配置](/v2/zh/service/team-configuration)。

## 提交团队任务并读取结果

团队准备好后，直接创建以它为目标的 Session。这里继续使用平台用户身份验证；接入业务后端时，可以按[服务 API](/v2/zh/service/service-api)改用授权这个 Team 的应用凭据。

```bash
session=$(api "$SERVICE_URL/api/v1/agent-sessions" --data "$(jq -n \
  --arg team "$TEAM_ID" '{target:{type:"team",id:$team}}')")
SESSION_ID=$(jq -er '.id' <<<"$session")
turn=$(api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns" \
  -H 'Idempotency-Key: team-report-001' \
  --data '{"message":"Review this week’s material and deliver a report with sources."}')
TURN_ID=$(jq -er '.id' <<<"$turn")
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns/$TURN_ID"
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/snapshot"
```

Service 在后台派发 Leader 和成员任务，并将它们的进度整理到这个 Turn。应用用 Session 快照恢复页面，再订阅事件；根 Turn 的状态用于判断团队是否完成，而不是任意一个成员的完成通知。需要进一步诊断时，可以从控制台进入关联的 Issue、Run 和执行图。人工分派与验收仍可使用[任务管理](/v2/zh/service/issues)，但应用调用无需自行创建这些内部记录。

## 调整成员与协作方式

读取 `GET /api/v1/teams/{teamId}` 获得完整配置。新增成员调用 `POST /api/v1/teams/{teamId}/members`，请求体包含 `agentId`、`role` 和可选 `instructions`；服务返回的 `member.id` 用于后续成员更新与移除。

更改团队名称、指令或策略使用 `PATCH /api/v1/teams/{teamId}`，携带当前 `expectedVersion`、`name`、`leaderAgentId`，并保留要沿用的策略和说明；更改成员角色使用 `PATCH /api/v1/teams/{teamId}/members/{memberId}`，携带 `expectedTeamVersion`。这些配置用于后续执行，已经开始的任务仍要按其实际执行记录跟进。不要通过移除成员来代替取消正在运行的任务。

## 提供给业务应用

应用调用 Team 时，直接创建 `target:{"type":"team","id":"TEAM_ID"}` 的 Session，再向 `/turns` 提交工作。Service 会将每个 Turn 转成一项团队任务，应用通过同一个 Session 查询进度、待办和产物。调用凭据在 Application 下签发，并显式授权这个 Team；调用方不需要获得修改成员或创建 Agent 的管理权限。完整流程见[服务 API](/v2/zh/service/service-api)。

这也是 Agent API 覆盖 Team 的方式：对外提供统一的调用与结果协议，内部仍使用团队自己的委派和协作机制。Team 的任务执行不等于 Managed Agent 的可恢复会话；不要把 Managed 专属的 checkpoint、文件等会话接口套用于所有成员。

如果步骤、依赖和审批顺序由业务固定，继续阅读 [Workflow API](/v2/zh/service/workflows)。需要在页面上操作，见[控制台：团队与编排](/v2/zh/service/console/index#console-orchestration)。协作原理见 [Team 参考](/v2/zh/service/create-team#teams)。

<span id="team-collaboration"></span>
<span id="从-issue-观察一次协作"></span>
<span id="策略与扩展"></span>
<span id="结束与验收"></span>

## 设计可协作的角色

以技术调研为例，Lead 明确问题并整合报告；Researcher 收集带来源的事实；Reviewer 检查证据与遗漏。每个角色都应有独立可验证的输出，避免多个 Agent 同时无边界地改同一文件。

成员可以混用 Managed、Hosted 和 External，但必须具备对应任务派发与协作能力。能在 Chat 回答并不一定能充当 coordinator。先单独验证每个成员，再把任务交给 Team。

## 业务应用的协作 API

以下操作使用用户 Bearer token 和相应工作授权。它们操作持久业务记录，不要求调用方知道成员当前在哪个运行时执行。

| 操作 | API 与关键参数 |
| --- | --- |
| 补充信息、请求成员处理 | `POST /api/v1/issues/{issueId}/comments`：`content`、可选 `parentId`、`type`、`mentions:[{type,ref}]` |
| 预览评论路由 | `POST /api/v1/issues/{issueId}/comments/preview-routing`：与评论相同的输入，返回 `targets` |
| 读取讨论 | `GET /api/v1/issues/{issueId}/comments`：`limit`、`cursor`、可选 `threadId`、`rootsOnly`；返回 `items`、`nextCursor` |
| 创建独立子目标 | `POST /api/v1/issues/{issueId}/children`：标题、说明、负责人及验收字段，返回 `issue`、`agentTask` |
| 查询子工作 | `GET /api/v1/issues`：`tenant`、`namespace`、`parentIssueId` |
| 文件交付 | `POST /api/v1/artifacts/uploads`：multipart 的 `tenant`、`namespace`、`issueId`、`relation`、`file` |
| 读取产物 | `GET /api/v1/issues/{issueId}/artifacts`，随后 `POST /api/v1/artifacts/{artifactId}/download` |

`mentions[].type` 选择 `agent`、`team` 或 `human`，`ref` 使用对应身份标识。正文中的名字不是结构化路由。提交评论后检查返回的 `routes`，确认是已排队、已合并还是被策略阻止；这些结果比“评论写入成功”更能说明是否产生了后续工作。仅记录进度时使用 `type:"progress"`，不设置 mentions，避免隐式派发。

## 持久沟通

用 Comment 和 mention 传递进展、问题与后续请求，用 Artifact 交付文件，用 child Issue 拆出可独立跟踪的子目标。不要依赖某个成员进程内的消息作为唯一协作记录。处理一次输入后需要记录处理情况，避免重试时重复响应。

Runtime Host 在任务执行中注入范围凭据和上下文。支持 Shell 的 provider 可以使用：

```bash
as task context
as issue current
as task progress --content-file ./progress.md
as task respond --content-file ./reply.md
as artifact upload ./report.md
as team current
as task run graph
```

这些命令在 Host 启动的任务环境中运行，不是在管理员普通终端里通过复制内部令牌模拟运行。MCP provider 可使用对应 collaboration 工具。Coordinator 使用节点完成/失败能力明确交付结果；普通回复不能替代流程收敛。

## 交付模板：让另一个成员能够复核

当[代码修复服务](/v2/zh/service/cases/incident-to-pr)需要扩展为多角色团队时，Developer 的交付评论可包含以下内容，并附 PR 和实际测试记录：

```text
目标：实现订单筛选与分页；保留原验收检查并补充边界测试。
修改：列出变更的文件与规则。
验证：JDK 版本、执行目录、命令、退出码、测试日志和 CI 链接。
交付：GitHub Issue、PR、head SHA、Review 与 Artifact 标识。
未完成项：列出没有验证的条件；没有则明确说明。
```

模板只是交付结构，不能把其中的占位内容当作已发生的执行。Lead 打开真实 Artifact 并核对证据后再汇总；成员各自的工作目录不自动共享，评论中的本地绝对路径也不等于另一个成员可以读取的文件。

## 运行时回报的身份与参数

执行适配器使用平台下发的任务凭据访问 `/api/v1/agent-tasks/{taskId}` 下的协议入口，业务用户 token 不能冒充正在执行的任务。`GET .../context` 返回当前工作上下文；`POST .../progress` 接受 `content`、可选 `parentId` 与 `mentions`；`POST .../complete` 可回报 `expectedVersion`、`summary`、`result`、`usage` 及已处理输入 ID。失败回报 `POST .../fail` 使用 `expectedVersion`、`code`、`message`。

Hosted provider 通常通过注入的 CLI / MCP 使用这些能力，External 适配器按[任务接入协议](/v2/zh/service/external-agent#external-agent-execution)接入。协调节点完成/失败还有专门的 coordinator 权限；普通成员的结果回报不会自动取得 Leader 权限。

页面操作见[控制台：团队与编排](/v2/zh/service/console/index#console-orchestration)与[任务反馈](/v2/zh/service/console/index#console-tasks)。

<span id="team-execution"></span>
<span id="从目标到执行"></span>
<span id="委派与持久沟通"></span>
<span id="通过-api-观察一次协作"></span>

## 定义与一次运行的关系

Team 定义包含 Leader、成员和策略；Run 固定这次协作的快照，便于追查当时的团队配置。控制面为 Team 创建一个 coordinator node 和初始 Leader 任务，而不是为每个成员无条件创建一次调用。

Leader 根据上下文选择成员。需要固定节点顺序、条件和汇合规则时使用 Workflow；Workflow 内也可以调用 Team 节点，把动态协作嵌入更大的流程。

## 如何结束工作

成员回复、Attempt 成功、coordinator node 完成、Run 终态和 Issue 验收分别表达不同层次。Leader 的最终文字不能代替节点完成。仍有必要子工作未完成时，应等待、调整计划或明确失败，不能用“报告已生成”掩盖未完成义务。

人工验收需要检查最终结果、重要来源和失败说明，再接受结果或要求修改。查看 Run succeeded/partial_succeeded 时也要核对业务验收要求。

## 扩展与恢复

新增成员前先单独验证它的能力，再检查 Leader 是否能正确使用该角色。混合 Managed、Hosted、External 时，用[成员配置](/v2/zh/service/team-configuration)中的检查条件逐个验收。

恢复依赖持久工作和执行状态；Runtime fresh fallback 重建上下文，不迁移旧进程。排障时沿 Issue → Run → Node → Task → Attempt 检查：未派发看就绪度与策略，执行卡住看后端与确认请求，交付未结束看成员义务和协调节点状态。

操作示例见 [Team API 指南](/v2/zh/service/create-team)，页面诊断见[控制台：团队与编排](/v2/zh/service/console/index#console-orchestration)。
