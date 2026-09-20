# AgentScope Service 原生 Issue 协作机制落地计划

> 状态：v3 已完整实现并通过最终验证
>
> 日期：2026-08-26
>
> 适用范围：AgentScope Service、aistio 控制面、Managed Agent、External Agent、Runtime Host、Agent Team、Automation、Console、CLI/SDK
>
> 核心参照：Multica 的 `Issue -> Comment/Discussion -> Mention -> AgentTask -> Comment result`。这里的 Issue 是 Multica 原生 Issue，不是 GitHub/Jira Issue。

## 1. 设计立场

本计划按“Multica-first、低包袱”原则重新设计 AgentScope Service：

- 不把现有 `ControlTask`、`TeamTask`、`TeamRun`、`TeamTemplate` 视为必须保留的产品概念；
- 有独立价值的能力保留，没有独立价值或造成重叠的概念替换、合并或删除；
- 直接采用 Multica 已验证的 Issue、Comment、Mention、AgentTask、Inbox、Attachment/Artifact 协作主链路；
- AgentScope Service 的核心差异化是同一套协作模型可以驱动 Managed Agent、External Agent 和 Runtime Host 三类数据面；
- 数据库保存协作事实和执行义务，push/event 只负责唤醒；
- AgentScope Service 尚未正式发布，旧 API、旧表、旧行为和现有开发数据都不构成兼容约束；
- 直接删除或重写与终态冲突的实现，同一改动中同步更新 Console、CLI、SDK、示例和测试；
- 不建立 compatibility layer、双写、shadow 路由、legacy adapter、版本分支或过渡 feature flag；
- 以终态模型的清晰、一致和正确性为最高优先级，开发环境数据库允许直接重建。

目标不是在现有控制面旁边加一个 Issue 模块，而是让 Issue 成为 AgentScope Service 的工作主对象，让 Runtime、Session、Execution 和 Team 围绕 Issue 工作。

## 2. 目标架构

```text
Tenant / Namespace（逻辑协作边界）
  |
  |-- Issue（长期工作与协作事实来源）
  |     |-- Comment / Discussion / Mention
  |     |-- Artifact / ResourceRef
  |     |-- Subscriber / Human Inbox
  |     |-- Activity / Audit
  |     |-- child Issue / dependency
  |     `-- AgentTask（一次 Agent 工作义务）
  |             `-- ExecutionAttempt（一次物理尝试）
  |
  |-- Agent（稳定身份与能力）
  |     `-- RuntimeBindingResolver
  |             |-- Managed Session
  |             |-- External AgentInstance / ASDP
  |             `-- Hosted ExecutionAttempt / Runtime Host
  |
  `-- Team（可分配、可 mention 的持久协作单元）
        |-- leader Agent
        |-- members / roles / policy
        `-- Issue 分配或 mention -> leader AgentTask
```

核心执行链路：

```text
Issue 创建/分配/Comment mention
  -> 在事务内写 Comment + route + AgentTask/Input + outbox
  -> RuntimeBindingResolver 选择数据面
  -> Managed / External / Hosted 执行
  -> Agent 实时读取 Issue + discussion
  -> Agent 写 result Comment / Artifact
  -> AgentTask/Execution 完成
  -> 新 Comment 可再次唤醒 assignee、worker 或 team leader
```

## 3. 哪些现有能力保留，哪些不再作为约束

### 3.1 明确保留

| 能力 | 原因 |
|---|---|
| Agent registry / AgentInstance | External Agent 注册、健康和路由必需 |
| RuntimeProfile / RuntimePool / RuntimeHost | Hosted coding runtime 调度的核心优势 |
| RuntimeBindingResolver | 屏蔽 Managed、External、Hosted 差异 |
| ExecutionAttempt + attempt | lease、fencing、retry、checkpoint 和物理执行历史有独立价值 |
| Session / provider session | 对话连续性和不同数据面的运行绑定需要 |
| durable outbox | 跨副本可靠唤醒所需，但必须事务化 |
| Approval | 有价值，但应从 TeamApproval 泛化为 Issue/Task/Execution Approval |
| tenant / namespace 隔离 | 继续作为逻辑协作和授权边界 |

### 3.2 重新定义或降级为内部实现

| 现有概念 | 新定位 |
|---|---|
| ControlTask | 被 AgentTask 语义和实现直接取代，不保留现有抽象作为产品边界 |
| TeamTemplate | 收敛为持久 Team 定义；revision 可作为内部发布快照，不要求用户理解 template/run 两层 |
| TeamRun | 可保留为一次团队协调的执行追踪，但不再是 discussion、任务板或完成事实来源 |
| TeamMemberRun | 如运维确有需要可作为 projection；AgentTask + Session + Execution 已能表达大部分成员运行状态 |
| Team policy JSON | 改为有 schema 的 TeamPolicy；不继续依赖任意 JSON |

### 3.3 删除或不再建设

- 不建设独立 `TeamTask V2`：Team 内任务就是 child Issue；
- 不建设独立 `TeamMessage V2`：持久通信就是 Comment + mention；
- 不把旧 name-addressed `team_tasks/team_messages` 表迁成新终态；
- 不把 Team event history 冒充 discussion；
- 不把 Session transcript 当协作事实来源；
- 不让 lead Session 终态直接决定 Issue 或 Team 整体完成；
- 不引入 WorkItem 作为 Issue 的同义包装层；
- 不为外部 GitHub/Jira 模型限制内部 Issue 设计，外部对象只通过 source reference 关联；
- 不保留旧 API response shape、旧数据库数据、旧 SDK 方法或旧测试所隐含的兼容要求。

## 4. 核心领域模型

遵循项目数据库规则：不建立数据库外键和级联；关系校验、依赖清理和需要原子性的写入由应用事务负责。所有子表冗余 `tenant`、`namespace`，避免只凭未经授权的 UUID 查询。

### 4.1 issues

```text
id                    UUID PK
tenant                TEXT
namespace             TEXT
identifier            TEXT nullable
title                 TEXT
description           TEXT
status                TEXT
priority              TEXT
assignee_type         TEXT nullable       # human/agent/team
assignee_id           UUID nullable
creator_type          TEXT                # human/agent/system/automation
creator_id            UUID nullable
parent_issue_id       UUID nullable
acceptance_criteria   JSONB
context_refs          JSONB
source_type           TEXT nullable
source_ref            TEXT nullable
due_at                TIMESTAMPTZ nullable
version               BIGINT
created_at            TIMESTAMPTZ
updated_at            TIMESTAMPTZ
resolved_at           TIMESTAMPTZ nullable
archived_at           TIMESTAMPTZ nullable
```

建议状态：

```text
backlog -> todo -> in_progress -> in_review -> done
                     |              |
                     +-> blocked <-+

backlog/todo/in_progress/in_review/blocked -> cancelled
blocked -> in_progress
in_review -> in_progress
done -> in_progress      # reopen
```

规则：

- Issue 是长期对象，可以跨多次 AgentTask、Execution、Session 和 Team 协作；
- Issue 状态不能由某次 Execution 自动决定；
- parent Issue 必须同 tenant/namespace，应用层拒绝循环；
- Team 内分工、Team-to-Team 委派都创建 child Issue；
- 首期不实现 Project、Board、Sprint、Label、自定义工作流；
- Issue 不硬删除，使用 cancelled/archived；
- `source_type/source_ref` 连接外部 issue、PR、channel thread、automation，不替代内部 Issue。

### 4.2 comments

```text
id                    UUID PK
tenant                TEXT
namespace             TEXT
issue_id              UUID
parent_id             UUID nullable
thread_root_id        UUID
author_type           TEXT                # human/agent/system
author_id             UUID nullable
content               TEXT
type                  TEXT                # comment/progress/result/status/system
source_task_id        UUID nullable
source_attempt_id   UUID nullable
version               BIGINT
created_at            TIMESTAMPTZ
updated_at            TIMESTAMPTZ
resolved_at           TIMESTAMPTZ nullable
resolved_by_type      TEXT nullable
resolved_by_id        UUID nullable
deleted_at            TIMESTAMPTZ nullable
```

规则：

- 每条 Comment 是独立记录，不把 discussion 存成大 JSON/字符串；
- reply 通过 `parent_id`，顶层 Comment 的 `thread_root_id = id`；
- 顺序由 `created_at, id` 稳定确定；
- Agent 最终结果必须成为 `type=result` 的 Comment，并携带 `source_task_id`；
- Comment 不因 Task/Execution 清理而删除；
- 已触发执行的 Comment 删除采用 tombstone；
- 修改已投递 Comment 时生成 correction input，不能静默修改 Agent 已领取的输入；
- Comment 可 resolve/unresolve，用于标记 discussion thread 的结论，不等于 Issue done。

### 4.3 comment_mentions 与 comment_routes

`comment_mentions` 保存结构化 mention：

```text
id, tenant, namespace, issue_id, comment_id,
target_type, target_id, created_at
```

目标类型首期支持：`human`、`agent`、`team`。

`comment_routes` 保存每个潜在收件目标的实际路由结论：

```text
id                    UUID PK
tenant                TEXT
namespace             TEXT
issue_id              UUID
comment_id            UUID
target_type           TEXT
target_id             UUID
route_type            TEXT      # explicit/thread_parent/assignee/team_leader/follow_up
outcome               TEXT      # queued/coalesced/deferred/suppressed/blocked
task_id               UUID nullable
reason_code           TEXT nullable
created_at            TIMESTAMPTZ
```

规则：

- API 使用结构化 `mentions[]` 为写入权威；Markdown `mention://...` 作为显示/CLI 兼容格式；
- 服务端验证 mention 目标存在、可见、可调用；
- explicit mention 优先于 thread routing，thread routing 优先于 assignee fallback；
- 同一 Comment 对同一目标幂等；
- self-trigger、权限拒绝、预算或循环阻断必须有 route outcome，不能表现为成功但实际什么也没发生；
- API 返回每个目标的 queued/coalesced/deferred/suppressed/blocked 结果。

### 4.4 agent_tasks

AgentTask 对应 Multica `agent_task_queue` 的核心语义：某个 Agent 因 Issue、Comment、assignment、automation 或 Team leader route 获得的一次工作义务。

```text
id                    UUID PK
tenant                TEXT
namespace             TEXT
issue_id              UUID
agent_id              UUID
status                TEXT
priority              INT
trigger_type          TEXT            # assignment/comment/automation/retry/manual/team
trigger_comment_id    UUID nullable
team_id               UUID nullable
team_role             TEXT nullable
is_leader_task        BOOLEAN
parent_task_id        UUID nullable
delegated_from_task_id UUID nullable
retry_of_task_id      UUID nullable
rerun_of_task_id      UUID nullable
originator_type       TEXT
originator_id         UUID nullable
accountable_human_id  UUID nullable
runtime_binding       JSONB nullable  # dispatch 时固定的后端选择快照
session_id            TEXT nullable
result                JSONB nullable
error_code            TEXT nullable
error_message         TEXT nullable
wait_reason           TEXT nullable
version               BIGINT
created_at            TIMESTAMPTZ
dispatched_at         TIMESTAMPTZ nullable
started_at            TIMESTAMPTZ nullable
completed_at          TIMESTAMPTZ nullable
```

建议状态：

```text
queued -> dispatched -> running -> completed
   |          |            |    -> failed
   |          |            |    -> waiting
   |          |            `----> cancelled
   |          `-> queued           # lost response / lease reclaim
   `-> cancelled

waiting -> queued/cancelled/failed
failed -> queued only through explicit retry creating a new lineage record/attempt policy
```

AgentTask 是产品和 API 的正式名称。直接以 `agent_tasks` 和新领域服务替换现有 `control_tasks` 路径，并在同一阶段删除旧表、旧 API 和旧调用方；不做原位兼容演进。

### 4.5 agent_task_inputs

该表解决 Multica 中 `trigger_comment_id + coalesced_comment_ids + delivered_comment_ids` 所解决的问题，但使用规范化记录：

```text
id                    UUID PK
tenant                TEXT
namespace             TEXT
task_id               UUID
comment_id            UUID
comment_version       BIGINT
sequence              BIGINT
state                 TEXT       # planned/delivered/acknowledged/processed/deferred
delivered_at          TIMESTAMPTZ nullable
acknowledged_at       TIMESTAMPTZ nullable
processed_at          TIMESTAMPTZ nullable
response_comment_id   UUID nullable
created_at            TIMESTAMPTZ
```

唯一键：`(task_id, comment_id, comment_version)`。

正确性规则：

- Comment route 与 AgentTask/Input 在同一事务内创建或合并；
- 同一 `(issue, agent, team role)` 已有 queued Task 时，新 Comment 合并为新的 input；
- Task 已 running 时，不修改已领取快照，而是创建一个 queued successor Task；
- 已存在 successor 时合并到 successor；
- claim 时固定 input 集合和 comment version；
- Runtime 明确 ack 实际收到的 input IDs；
- Task completion 对每个 input 标记 processed/deferred，并可关联 response Comment；
- completion reconciliation 检查运行期间新增或未 ack 输入，必要时保留/创建后继 Task；
- 不允许 `HasPendingTask` 一类去重逻辑直接吞掉 Comment。

### 4.6 teams 与 team_members

Team 直接采用 Multica Squad 的产品语义：一个可以被分配 Issue、可以被 mention 的持久协作单元。

```text
teams:
  id, tenant, namespace, name, description,
  leader_agent_id, policy, version, created_at, updated_at, archived_at

team_members:
  id, tenant, namespace, team_id,
  agent_id, role, instructions, capability_requirements,
  runtime_binding_policy, created_at, archived_at
```

规则：

- Team 必须有 leader Agent；
- Issue 分配给 Team 时，执行目标解析为 leader AgentTask；
- mention Team 时同样唤醒 leader；
- leader 的 claim context 包含 Team roster、roles、instructions、policy；
- leader 通过创建 child Issue 或在 Comment 中 mention worker 分工；
- worker 结果回写 Issue/child Issue Comment；
- worker result/progress Comment 按 thread/assignee/team leader route 再唤醒 leader；
- 一个 Agent 同时作为 leader 和 worker 时，`team_role/is_leader_task` 参与 self-trigger guard；
- 跨 Team 协作就是 child Issue 分配或 mention 另一个 Team；
- TeamTemplate revision 如需审计，可在每次 leader Task/可选 TeamRun 中保存 Team snapshot，不需要成为主要用户心智。

### 4.7 team_runs：可选执行追踪，不是协作主对象

如果 Console、预算或运维确实需要聚合一次团队协作，可保留精简 TeamRun：

```text
id, tenant, namespace, issue_id, team_id,
leader_task_id, team_snapshot, state,
budget, usage, started_at, completed_at
```

它的职责仅限：

- 聚合一次 Team 协作的 tasks/executions/cost；
- 固定当时 Team 配置快照；
- 提供取消和运维视图；
- 记录一次自动化或人工发起的团队 run。

它不拥有：

- discussion；
- Team task board；
- agent 间持久消息；
- Issue 完成状态；
- child work 的唯一事实来源。

如果这些运维需求可以直接由 Issue + AgentTask 查询满足，TeamRun 可以完全删除。

### 4.8 artifacts、subscribers、inbox、activity

```text
artifacts:
  id, tenant, namespace, storage_provider, storage_key,
  filename, content_type, size_bytes, checksum,
  uploader_type, uploader_id,
  source_task_id, source_attempt_id,
  metadata, created_at, expires_at

artifact_links:
  artifact_id, target_type, target_id, relation, created_at

issue_subscribers:
  issue_id, subscriber_type, subscriber_id, created_at

inbox_items:
  id, tenant, namespace,
  recipient_type, recipient_id,
  type, severity, issue_id, comment_id, approval_id,
  actor_type, actor_id, title, body, details,
  read, archived, dedupe_key, created_at

activity_log:
  id, tenant, namespace, issue_id,
  actor_type, actor_id, action,
  object_type, object_id,
  causation_id, correlation_id, details, created_at
```

- 文件字节进入对象存储，DB 只存 metadata/storage key；
- 不允许把某个 Runtime 的本地绝对路径当作共享协议；
- 代码协作优先 Git repository/branch/commit ResourceRef；
- 人类 mention、审批、失败、阻塞、review request 进入 Inbox；
- Agent 不消费人类 Inbox，Agent 工作只通过 AgentTask queue；
- outbox 是投递机制，不能替代不可变 Activity/Audit。

## 5. Comment 路由与 Agent 协作协议

### 5.1 Comment 创建事务

在一个应用事务中：

1. 认证 author，服务端确定 author_type/author_id；
2. 校验 Issue、parent Comment、thread 和 tenant/namespace；
3. 写 Comment；
4. 写结构化 mentions；
5. 计算 route：explicit mention > direct parent/thread owner > assignee fallback；
6. 对 human route 写 Inbox；
7. 对 agent route 创建或合并 AgentTask + AgentTaskInput；
8. 写 comment_routes；
9. 写 Activity；
10. 写 outbox；
11. 提交后发送 wake hint。

任何一步失败则整笔协作写入回滚。不得提交 Comment 后再 best-effort 创建 AgentTask。

### 5.2 Agent 读取 Issue

采用 Multica 的“少量推送 + 按需拉取”：

- claim 返回 Issue ID、触发 Comment、合并输入、Team role、task token 和摘要；
- Agent 使用 API/CLI/SDK 重新读取 Issue 当前状态；
- 先读取 comment roots summary，再按需读取相关 thread tail；
- 即使恢复同一 provider session，也必须刷新 Issue version 和未处理 inputs；
- DB 中的 Issue/Comment 是事实来源，session transcript 只是模型连续性；
- 长 discussion 可以产生摘要，但不得删除原 Comment 或只留下模型私有摘要。

### 5.3 Agent 回复

提供高层动作：

```text
issue.get(issueId)
issue.comment.list(issueId, rootsOnly/summary/thread/tail/cursor)
issue.comment.add(issueId, parentId, content, mentions, artifacts)
task.respond(inputId, content, artifacts, coversInputIds)
task.complete(taskId, result, processedInputs, deferredInputs)
```

`task.respond` 由服务端：

- 找到 input 对应 Issue/Comment/thread；
- 写入正确 parent；
- 自动写 `source_task_id`；
- 记录 Comment 覆盖哪些 inputs；
- 校验调用 Task 真实领取过这些 inputs；
- 处理回复中的新 mention；
- 传播 originator、causation、correlation 和 Team role。

comment-triggered Task 不能只留下终端输出。完成时必须满足以下之一：

- 已创建一个或多个 result/progress Comment；
- 控制面根据 completion summary 自动创建 result Comment；
- 对每个 input 记录明确的 `no_reply_required/deferred/rejected` 原因。

### 5.4 Agent 间通信

不提供另一套持久 `sendMessage(member, string)` 协议：

- 需要跨运行保留、需要回复、需要交付结果的通信：使用 Issue Comment + mention；
- Team 内分工：创建 child Issue 或 mention worker；
- worker 通知 leader：在 Issue/child Issue 写 progress/result Comment；
- Team-to-Team：创建/分配 child Issue 给另一个 Team；
- 临时 Runtime 控制信号：使用 event/wake，不进入 discussion；
- Session chat：只服务某个会话，不作为 Agent-to-Agent 可靠协作层。

## 6. 多数据面统一执行

### 6.1 稳定 Agent 身份，运行方式独立

Agent 是可分配、可 mention 的稳定主体；运行方式是配置和调度问题，不写入 Issue 协作语义。

```text
AgentTask
  -> RuntimeBindingResolver.resolve(agent, task, policy)
       -> ManagedBinding
       -> ExternalBinding
       -> HostedBinding
```

Resolver 输出不可变 dispatch snapshot：backend kind、AgentInstance/Session/RuntimeProfile/Pool、capabilities、policy、task token scope。

### 6.2 Managed Agent

```text
AgentTask queued
  -> find/create Managed Session
  -> persist task/session binding
  -> post wake with task locator
  -> Managed Agent pull Issue/inputs
  -> write Comment and complete Task
```

Managed session idle 不自动等于 Task completed；只有正式 Task completion 或可靠 reconciliation 才更新状态。

### 6.3 External Agent

```text
AgentTask queued
  -> select healthy AgentInstance
  -> persist fixed instance binding
  -> ASDP deliver task event
  -> External Agent ack and pull Issue/inputs
  -> heartbeat/progress/result
```

重投必须使用 task ID/event ID 幂等。已经选中的执行期间不因 registry 变化悄悄切换实例；失败后由 retry policy 创建新 attempt/dispatch。

### 6.4 Runtime Host

```text
AgentTask queued
  -> create ExecutionAttempt attempt
  -> Runtime Host claim with lease/fencing
  -> prepare isolated workspace
  -> provider adapter starts/resumes session
  -> Agent reads Issue through task-scoped credentials
  -> checkpoint/progress/result
```

保留现有 RuntimeProfile、Pool、Host、workspace allocation、checkpoint、journal、lease/fencing 和 provider adapter 能力。

### 6.5 统一 ContextEnvelope

三类数据面领取到相同的领域上下文：

```json
{
  "task": {"id": "...", "status": "dispatched"},
  "issue": {"id": "...", "title": "...", "status": "...", "version": 12},
  "inputs": [
    {
      "id": "...",
      "commentId": "...",
      "commentVersion": 2,
      "threadRootId": "...",
      "author": {},
      "content": "..."
    }
  ],
  "team": {"id": "...", "role": "reviewer", "isLeader": false},
  "contextRefs": [],
  "artifacts": [],
  "availableActions": [],
  "taskToken": "..."
}
```

差异只在 transport、session 和 execution 管理，不在 Issue/Comment/Task 语义。

## 7. Team 协作模型

### 7.1 Issue 分配给 Team

1. Issue `assignee_type=team`；
2. 服务端解析 Team leader；
3. 创建 `is_leader_task=true` 的 AgentTask；
4. claim 注入 Issue、Team roster、role instructions、policy；
5. leader 决定直接处理、mention worker、创建 child Issue 或委派另一个 Team；
6. worker 的 Comment/child Issue completion 再唤醒 leader；
7. leader 写 Issue conclusion/result Comment；
8. Issue 是否 done 由 acceptance policy/human 决定。

### 7.2 Lazy worker

Team member 定义是静态 roster，不预先创建 Session。只有发生以下行为才产生 AgentTask/Session/Execution：

- leader 创建/分配 child Issue；
- Comment 明确 mention worker；
- automation route 到 worker；
- retry/rerun；
- policy 明确要求某个 required role。

### 7.3 Team 完成

不要以 lead Session/Execution terminal 作为 Team 完成条件。至少检查：

- leader Task 已给出 completion decision；
- required child Issues 已 done；
- 没有未处理 leader inputs；
- 没有 pending approval；
- acceptance criteria 已评估；
- result Comment/Artifact 已写入；
- budget 超限没有被静默忽略。

完成决定归属于 Issue acceptance policy。可选 TeamRun 只记录结果，不拥有最终真相。

### 7.4 跨 Team

不增加 Team-to-Team mailbox：

```text
Team A leader
  -> 创建 child Issue
  -> assignee = Team B
  -> Team B leader AgentTask
  -> Team B workers 协作并回写 child Issue
  -> child Issue done/progress Comment 唤醒 Team A leader
  -> Team A 验收
```

parent/child Issue、source Task、originator、correlation ID 提供完整因果链。

## 8. API 与 Agent 工具

### 8.1 Issue API

```text
POST   /api/v1/issues
GET    /api/v1/issues
GET    /api/v1/issues/{id}
PATCH  /api/v1/issues/{id}
POST   /api/v1/issues/{id}/transition
POST   /api/v1/issues/{id}/assign
POST   /api/v1/issues/{id}/children
GET    /api/v1/issues/{id}/activity

GET    /api/v1/issues/{id}/comments?rootsOnly=true&summary=true&cursor=...
GET    /api/v1/issues/{id}/comments?thread={commentId}&tail=30&cursor=...
POST   /api/v1/issues/{id}/comments
PATCH  /api/v1/issues/{id}/comments/{commentId}
POST   /api/v1/issues/{id}/comments/{commentId}/resolve
POST   /api/v1/issues/{id}/comments/preview-routing
```

### 8.2 AgentTask API

```text
GET    /api/v1/agent-tasks
GET    /api/v1/agent-tasks/{id}
POST   /api/v1/agent-tasks/{id}/claim
POST   /api/v1/agent-tasks/{id}/ack
POST   /api/v1/agent-tasks/{id}/progress
POST   /api/v1/agent-tasks/{id}/respond
POST   /api/v1/agent-tasks/{id}/complete
POST   /api/v1/agent-tasks/{id}/fail
POST   /api/v1/agent-tasks/{id}/retry
POST   /api/v1/agent-tasks/{id}/cancel
```

Runtime Host 和 External Agent 可继续使用版本化 machine transport，但必须调用同一 TaskService/IssueService，不能形成另一套状态机。

### 8.3 Artifact、Inbox、Team

```text
POST   /api/v1/artifacts/uploads
POST   /api/v1/artifacts/{id}/complete
POST   /api/v1/artifacts/{id}/download

GET    /api/v1/inbox
POST   /api/v1/inbox/{id}/read
POST   /api/v1/inbox/{id}/archive

POST   /api/v1/teams
GET    /api/v1/teams
GET    /api/v1/teams/{id}
PATCH  /api/v1/teams/{id}
POST   /api/v1/teams/{id}/members
DELETE /api/v1/teams/{id}/members/{memberId}
```

### 8.4 CLI/SDK/MCP

为所有数据面提供一致工具：

```text
issue get/list/create/update/assign
issue comment list/add/resolve
issue child create
artifact upload/download
task get/respond/complete/fail
team get/list-members
approval request/get
```

不向 Agent 暴露数据库、内部 dispatcher 或特定 backend API。

## 9. 事件、可靠性与因果链

### 9.1 版本化事件

```text
issue.created.v1
issue.updated.v1
issue.assigned.v1
issue.status-changed.v1
comment.created.v1
comment.updated.v1
comment.resolved.v1
agent-task.queued.v1
agent-task.input-added.v1
agent-task.dispatched.v1
agent-task.completed.v1
agent-task.failed.v1
artifact.created.v1
inbox-item.created.v1
approval.requested.v1
approval.decided.v1
```

Envelope：

```text
event_id, event_type, schema_version,
tenant, namespace,
aggregate_type, aggregate_id,
actor_type, actor_id,
causation_id, correlation_id,
occurred_at, payload
```

### 9.2 因果与授权传播

每个 AgentTask 至少保留：

- human/system originator；
- accountable human；
- source Issue/Comment；
- parent/delegated/retry/rerun Task；
- Team/role；
- causation/correlation ID；
- hop count 和 team depth；
- runtime dispatch snapshot。

Agent 通过 Comment mention 另一个 Agent 时，从 `comment.source_task_id` 继承根 originator 和授权上下文，不把被调用 Agent 当成新的无来源主体。

### 9.3 防循环和预算

- Agent 自己写的 Comment 默认不触发同一角色的自己；
- leader/worker 角色不同，self guard 按 `(agent, team, role)` 判断；
- source Comment + target + role 幂等；
- 最大 A2A hop、child Issue depth、Team delegation depth；
- 每 Issue/Team 最大 active Tasks、动态 fanout、token/time/cost；
- mention/routing rate limit；
- 超限写 blocked route + human Inbox，不静默 suppress；
- `@all` 默认只通知 human，触发 Agent 需 Team policy 显式允许。

### 9.4 投递状态

AgentTask input 和 outbox 必须区分：

```text
planned -> delivered -> acknowledged -> processed
                    \-> retry -> dead-letter / blocked
```

不得把达到最大重试次数的消息标记为 delivered。dead-letter 必须出现在运维视图和 human Inbox。

## 10. 工程实现结构

建议目标模块：

```text
aistio/internal/issue/
  service.go
  comments.go
  routing.go
  subscriptions.go
  activity.go

aistio/internal/agenttask/
  service.go
  queue.go
  input.go
  completion.go
  context.go

aistio/internal/team/
  service.go
  routing.go
  policy.go

aistio/internal/artifact/
aistio/internal/inbox/
aistio/internal/approval/

aistio/internal/runtimebinding/
  resolver.go
  managed.go
  external.go
  hosted.go
```

可复用现有 registry、runtimehost、sessionops、store/outbox 代码，但领域服务以新 Issue/AgentTask 模型为边界。

需要删除或停止扩展：

- `internal/team` 中旧 name-addressed tasks/messages；
- 旧 `/api/v1/teams/*/tasks|messages|events`；
- Teams V2 中只靠 `getRun/activateRole/spawnMember` 的临时协作工具集合；
- lead terminal 直接关闭 run/root task 的逻辑；
- `TeamTask/Message V2` 待办方向。

## 11. 数据库迁移原则

- 不添加数据库 FK/cascade；
- 关系和租户归属由应用层事务验证；
- 每个索引使用独立 migration 和 `CREATE [UNIQUE] INDEX CONCURRENTLY`；
- Comment/Task/Input/route 写入必须事务化；
- 高频子表冗余 tenant/namespace；
- 直接以终态 schema 替换旧 control plane 和 Teams schema；
- 删除旧表、旧 migration 终态假设、旧查询和旧 store interface，不保留兼容 view；
- 当前开发数据不迁移、不 backfill，可通过重建数据库获得新 schema；
- 不设计双写、一次性数据搬运程序、旧新 ID 映射或旧客户端兼容；
- 迁移文件只服务新的干净安装和后续正式版本，不服务未发布实现之间的数据升级；
- 实施过程需要回退时使用代码版本回退和开发数据库重建，而不是在新业务代码中保留旧路径。

建议迁移组：

1. issues、comments、mentions、routes；
2. agent_tasks、agent_task_inputs；
3. teams、team_members；
4. artifacts、links；
5. subscribers、inbox、activity；
6. generalized approvals；
7. 每条关键查询路径的 concurrent indexes；
8. 旧 TeamTask/TeamMessage/不再使用的 TeamRuntime 表删除。

## 12. 分阶段实施

### M0：架构重置（1 个迭代）

交付：

- 正式 ADR：Issue-first、AgentTask、Team=Squad、多数据面 resolver；
- 修改总升级计划，删除 TeamTask/Message V2 方向；
- 对现有实体逐个做 keep/replace/delete 决定；
- 锁定 schema、状态机、actor、事件、权限矩阵；
- 删除与终态冲突的旧 API、store contract 和前端/SDK 类型，不增加 feature flag；
- store contract 和 E2E 测试骨架。

退出标准：

- Issue、AgentTask、Execution、Session、Team 的边界无重叠；
- 不再以兼容现有未稳定概念作为设计理由；
- 全仓只存在一套新领域类型和 API，不存在 legacy adapter 或双写；
- Managed/External/Hosted 三条链路共享同一 AgentTask 契约。

### M1：Issue/Comment 核心（1-2 个迭代）

交付：

- Issue CRUD、assignment、status、parent/child、acceptance criteria；
- Comment roots/thread/tail、reply、resolve；
- mention 持久化、route preview；
- subscriber、Activity；
- Console/CLI 最小 Issue 页面；
- cursor pagination、tenant/namespace authz；
- transaction + outbox。

退出标准：

- human/agent 都能在同一 Issue 写可归因 Comment；
- 新 Agent 只用 API 可以重建 discussion；
- thread 不跨 Issue/tenant；
- 同时间戳分页稳定。

### M2：Comment -> AgentTask 防丢闭环（2 个迭代）

交付：

- agent_tasks、agent_task_inputs、comment_routes；
- explicit mention、thread parent、assignee fallback；
- queued coalesce、running successor；
- claim snapshot、input ack、completion reconciliation；
- source Task/originator/correlation；
- self/hop/rate/budget guards；
- retry、dead-letter、人工重放。

退出标准：

- 每个 agent route 都可追踪到 AgentTask/Input 或明确阻断原因；
- 已有 queued/running Task 时不丢 Comment；
- crash、重复 event、lost response 不导致输入消失或重复执行；
- claim/coalesce/complete 竞态有数据库并发测试。

### M3：三类 Runtime 统一接入（2 个迭代）

交付：

- RuntimeBindingResolver 以 AgentTask 为唯一入口；
- Managed Session dispatch/ack/complete；
- External AgentInstance/ASDP dispatch/ack/complete；
- Hosted ExecutionAttempt/Host claim/complete；
- 统一 ContextEnvelope 和 task-scoped token；
- progress/result Comment；
- Runtime failure classification/retry。

退出标准：

- 同一 Issue 测试可分别由三类 runtime 完成；
- 三类 runtime 读取同一 Issue/Comment API、写同一 result Comment；
- backend 细节不泄漏到 Issue/Comment 模型；
- provider Session 恢复后仍刷新最新 inputs。

### M4：Artifact、Inbox、Approval（1-2 个迭代）

交付：

- Artifact provider SPI、上传/下载和 links；
- human Inbox：mention、review、failure、blocked、approval；
- Approval 泛化到 Issue/Task/Execution；
- result Comment 自动兜底；
- dead-letter/未 ack 运维视图；
- WebSocket cache invalidation/wake。

退出标准：

- 隔离 runtime 可以可靠交换 Artifact；
- Comment/outbox/Task JSON 不承载大文件；
- Inbox recipient 无歧义；
- AgentTask 完成后有可见结果或明确 no-reply reason。

### M5：Team/Squad 协作（2 个迭代）

交付：

- 持久 Team、leader、members、roles、typed policy；
- Issue assign/mention Team -> leader Task；
- leader briefing、lazy worker；
- child Issue 分工；
- worker result/progress 唤醒 leader；
- accept/reject/reopen；
- Team-to-Team child Issue delegation；
- 可选 TeamRun 追踪，或证明可删除；
- 删除旧 TeamTask/TeamMessage 和旧 Teams API。

退出标准：

- Team 内全部持久协作只依赖 Issue/Comment；
- leader terminal 不会使未处理 child Issue/inputs 提前完成；
- Team A 可把 child Issue 交给 Team B 并验收；
- 不存在新旧 Teams 双轨写入。

### M6：Automation、治理与 GA（2 个迭代）

交付：

- Cron/Webhook/Channel -> Issue/Comment/AgentTask；
- standing Team、SLA、budget、search、summary；
- retention/archive/export；
- 审计、quota、PII/Secret/Artifact policy；
- 性能、安全、故障注入、恢复测试；
- 正式发布后的契约版本政策、运维手册和 SLO。

退出标准：

- 任一 run 都能解释“为什么启动、读取了什么、输出到哪里”；
- DB/Server/dispatcher/runtime 重启不丢 Comment 输入；
- 跨租户、循环、预算和 dead-letter 场景全部 fail closed；
- 满足容量和可用性目标。

## 13. 首批工程 Backlog

1. ADR：Issue-first 与现有概念 keep/replace/delete；
2. Issue/Comment/AgentTask schema 和 actor model；
3. PostgreSQL + memory store contract；
4. Issue CRUD/state/assignment；
5. Comment thread/cursor；
6. structured mentions + route preview；
7. comment route transaction；
8. AgentTask queue/claim/lease；
9. AgentTaskInput coalesce/successor；
10. ContextEnvelope/task token；
11. Agent Issue/Comment/Task tools；
12. result Comment/source Task；
13. Managed adapter；
14. External ASDP adapter；
15. Hosted Runtime Host adapter；
16. Artifact provider；
17. Inbox/subscriber/activity；
18. generalized approval；
19. Team/Squad model；
20. leader/worker routing；
21. child Issue/cross-Team；
22. 旧 Team 实现彻底删除；
23. Console/CLI；
24. fault/load/security tests；
25. 正式发布准备和 operations runbook。

## 14. 测试矩阵

### 14.1 协作事实

- Issue/Comment/route/Task/Input/outbox 事务原子性；
- parent/thread tenant 边界；
- comment edit/delete 与已投递 input；
- stable cursor；
- source Task attribution；
- Activity 完整性。

### 14.2 防丢与并发

- 两条 Comment 并发合并 queued Task；
- Comment 与 claim 竞态；
- running Task 后 successor；
- dispatch 成功但响应丢失；
- Runtime 收到但 ack 丢失；
- completion 同时新增 Comment；
- retry/rerun 不覆盖历史；
- dead-letter/replay；
- 多副本 claim。

### 14.3 Runtime

- Managed/External/Hosted 契约一致性；
- instance/host 掉线；
- lease/fencing；
- Session resume；
- checkpoint；
- runtime binding snapshot；
- task token scope。

### 14.4 Team

- Team assignment/mention；
- leader-first；
- lazy worker；
- 同 Agent leader/worker self guard；
- child Issue accept/reject/reopen；
- worker Comment 唤醒 leader；
- pending input 阻止完成；
- cross-Team delegation；
- depth/budget/loop limit。

### 14.5 Artifact/Inbox/Auth

- 隔离 Runtime 文件传递；
- checksum/size/MIME/ACL；
- expired download；
- human vs Agent recipient；
- author 防伪造；
- private Agent mention 不泄漏；
- tenant/namespace fail closed。

## 15. 可观测性与 SLO

关键指标：

- Comment commit -> AgentTask queued；
- AgentTask/Input planned/delivered/acknowledged/processed 年龄；
- coalesced inputs、successor tasks；
- Comment -> first Agent response；
- self/permission/budget/loop route outcomes；
- dead-letter 数量和年龄；
- 未回复/未覆盖 input；
- Issue reopen/reject；
- 每 Issue/Team/AgentTask cost/time/token；
- Managed/External/Hosted 分数据面成功率和延迟。

初始正确性目标：

- 100% 已提交 agent-directed Comment 有 comment_route；
- 100% queued/coalesced route 可反查 AgentTask/Input；
- 100% Agent result Comment 可反查 source Task；
- 任何 input 最终进入 processed/deferred/dead-letter；
- 不存在 dropped 被标记为 delivered；
- runtime 可用时 99% Comment 在 5 秒内进入 queued/coalesced；
- dead-letter 在 1 分钟内产生 human attention item。

## 16. 开发切换与清理策略

当前阶段采用一次性终态切换，不做线上兼容发布：

1. 在 ADR 中锁定 Issue-first 终态和 keep/replace/delete 清单；
2. 直接替换数据库 schema、领域 model、store interface 和 HTTP API；
3. 同步修改 Console、CLI、SDK、Managed、External、Runtime Host 调用方；
4. 删除旧 ControlTask、TeamTask、TeamMessage 和无价值的 TeamRuntime 路径；
5. 删除兼容测试，改写为终态行为测试；
6. 重建开发数据库，运行 store、integration、E2E 和三类 runtime 测试；
7. 每个里程碑完成时全仓不得残留旧 API 调用、双写或 legacy adapter；
8. 正式发布前再制定面向未来已发布版本的 API/数据迁移政策。

开发过程中如果某个阶段不可用，应修复或回退该提交并重建开发数据库，不通过恢复旧业务路径来兜底。进入正式发布准备后，才开始保护已经公开的新 Issue/AgentTask 契约。

## 17. 端到端验收故事

1. 用户创建 Issue 并分配给 Team A；
2. Team A leader 获得 AgentTask，运行在 External Agent；
3. leader 创建两个 child Issues，分别 mention Managed worker 和 Hosted worker；
4. 用户连续在两个 thread 补充三条 Comment；
5. claim 前的输入合并，运行中的输入进入 successor Task，三条都不丢；
6. Managed worker 写 progress Comment；
7. Hosted worker 上传 Artifact 并写 result Comment；
8. worker Comment 自动唤醒 leader；
9. leader 将一个 child Issue 分配给 Team B 做复核；
10. Team B leader/worker 完成后，结果回到 child Issue discussion；
11. Team A leader 驳回一次并重新触发 worker；
12. 所有 child Issues 验收后，leader写父 Issue conclusion；
13. human Inbox 收到 review request，接受后 Issue done；
14. 任一点重启 Server、dispatcher、External Agent 或 Runtime Host，不丢输入；
15. Activity 能解释每个 AgentTask 的来源 Comment、身份、runtime、Execution、结果 Comment 和 Artifact。

## 18. 最终完成定义

完成后，AgentScope Service 的产品心智应收敛为：

```text
Issue 是工作和协作中心；
Comment 是人和 Agent 的持久通信；
AgentTask 是一次执行义务；
ExecutionAttempt 是一次物理尝试；
Team 是一组由 leader 协调的 Agent；
Managed / External / Hosted 只是 AgentTask 的不同执行后端。
```

最重要的系统性质是：

> 任意一条已经提交、需要 Agent 处理的 Comment，都能追踪到明确的 route、AgentTask 和 input；它最终要么被某次运行实际处理并产生可见结果，要么有明确的 blocked/deferred/dead-letter 原因，绝不因去重、并发、重启或已有运行中的 Task 而静默消失。

## 19. v3 实现证据

本节记录已通过最终验证的终态实现，不再代表未来 backlog。以这里的领域边界和公开契约作为后续演进基线。

| 里程碑 | 已落地能力 | 主要验证证据 |
|---|---|---|
| M0 | Issue-first ADR；删除旧 ControlTask、TeamTask、TeamMessage、AgentTeam API/表/控制器；单一 AgentTask 契约 | `docs/controlplane/adr-0001-issue-first-collaboration.md`；全仓旧术语扫描；干净 PostgreSQL migration |
| M1 | Issue CRUD/状态/归档/搜索/摘要/导出、parent/child、Comment thread/cursor/edit/tombstone/resolve、Mention/Route、Subscriber/Activity、事务 outbox、Console/CLI | memory/PostgreSQL store contract；HTTP authz/pagination tests；Console lint/build |
| M2 | queued coalesce、running successor、claim snapshot、ack、completion reconciliation、retry/rerun、dead-letter/replay、hop/depth/fanout/budget/self guard | `internal/store/storetest` backend-neutral reliability contract；`internal/collaboration` concurrency/causality tests |
| M3 | 一个 RuntimeBindingResolver 驱动 Managed、External/ASDP、Hosted/ExecutionAttempt；统一 ContextEnvelope、task token、tenant identity、Session persistence | `internal/runtimebinding/resolver_test.go`；ASDP、taskplane、runtimehost、Java/Python/DSH SDK tests |
| M4 | Artifact provider/ACL/checksum/expiry/policy、human Inbox、Approval、result Comment 兜底、outbox dead-letter attention、WebSocket invalidation | Artifact provider integrity/traversal tests；MCP 跨 Task ACL、expiry、checksum、size/MIME policy tests；Inbox/Approval、controller outbox tests |
| M5 | 持久 Team/typed policy/leader/member/runtime override、leader-first、lazy worker、child Issue、accept/reject/reopen、跨 Team 因果链 | `TestCrossTeamChildIssueResultWakesParentLeaderAndCanBeAccepted`；Team auth/budget/acceptance tests |
| M6 | Cron/Webhook/Channel Automation、SLA/timeout、token/cost quota、PII/Secret/Artifact policy、search/summary/archive/export、审计/SLO/runbook/version policy | `internal/automation/service_test.go`；governance sweep/content-policy tests；运维与版本文档 |

可靠唤醒的最终链路是：领域事务写 `agent-task.queued.v1` outbox，多副本 worker 通过 `SKIP LOCKED` claim 读取持久 Task 并调用唯一 Resolver。Task 的 CAS 状态是重复事件的幂等 fence；无法解析或投递的目标会退避重试，达到上限后进入 dead-letter 并产生 human Inbox attention，不会静默停留在队列中。

### 19.1 最终验证记录

- Go：`go test ./...`、`go vet ./...`、关键协作/存储/HTTP/ASDP/Controller/RuntimeBinding/Automation 包 `-race`、二进制构建、Proto 一致性全部通过；
- 存储与控制器：全新 PostgreSQL 数据库 migration + memory/PostgreSQL store contract 通过，真实 Kubernetes `envtest` controller integration 通过；
- Console 与 SDK：Console npm lint 为 0 error 并完成 production build；DSH 17 项测试及 TypeScript build、Python 3.9 clean venv 60 项测试、Java extension/service-dataplane/Paw 测试全部通过；
- 部署：manifest 生成同步、Helm lint/template、Artifact RWX PVC 与多副本 fail-closed 校验通过；
- 静态终态审计：migration 无 FK/cascade，每个索引均为单语句 `CREATE INDEX CONCURRENTLY`；源码、SDK、Console 不再残留 `ControlTask`、`TeamTask`、`TeamMessage`、`AgentTeam` 旧协作路径；`git diff HEAD --check` 通过。
