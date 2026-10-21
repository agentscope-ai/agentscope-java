---
title: "执行参考：Session、Run 与 Attempt"
en_link: /v2/en/service/sessions
---

当前 Portal 重建版本保留 Managed 完整原生身份，在 Service 私有存储分区中使用
固定长度键保存日志路径；该版本使用新的运行数据空间。fail-fast Run 先持久化
原始失败，再分批清理兄弟工作；节点 timeout 也等待自身义务排空。公开 Turn
终态仍需物理停止证明，之后才释放并发预留。

<Note>
本页使用 `2.1.0-BETA1` 预发布版本。
</Note>

应用通常从 Agent API 会话、Issue 或Session API 提交工作。本页用于沿业务请求查找 Run、Task、Attempt 和 Session，理解它们的状态与控制接口。执行详情仍受空间、工作自身权限及运维能力约束。

在源码 MQ/Redis 部署中，Managed Console Chat 在第一条消息前注册原生 Session。
Chat 与 Session 诊断页读取 `/v1/sessions/{id}` 的快照、Turn 和持久游标事件。
长工具挂起后恢复同一逻辑 Turn，后续 Turn 等待它结束。删除 Chat 要求没有待执行的
Turn，仅从个人列表移除，原生 Session 与执行记录仍然保留；归档 Chat 可以继续查看。

源码重构版本中的 Managed AgentTask 通过持久 Outbox 派发，每个 Attempt 使用独立原生
Session。受理响应不确定时，重试复用原始输入与请求键，不创建替代 Attempt。
控制面查询原生 Turn 状态，取消要等到原生执行确认停止后才结束。私有任务工具凭据在挂起
与 Resume 后仍绑定当前 Attempt 和 Session，每次调用都检查当前执行与资源授权。
配置的执行超时先请求原生取消，确认停止后才记录失败。其他调用来源与故障恢复仍在验收中。

单 Agent Managed 任务的终态 MCP 响应丢失后，可在原始原生 Turn 仍活动时只读重试已提交
结论；不能替换结果或发起新工作。其他外部工具的副作用仍需要各自的幂等与恢复约定。

源码重构版本已删除旧 Managed 的 start、heartbeat 和事件上报回调。公开 Invocation
结束前还会等待原始原生 Turn 的持久终态；查询失败时保持等待。Hosted 与 External
继续使用各自的执行与工具确认协议。

共享 Run 诊断中的 Managed 任务进度直接读取原生事件，每个 Attempt 使用独立游标。
原生 Required Action 的回复与回执重试保留原 Attempt、Turn 和命令身份；取消后的
Attempt 不会因迟到的工具决定被重新释放。

## 关联一项工作

从 Issue ID 查询关联 Run，先看输入、运行方式与实际目标，然后查询节点、AgentTask 和最新 Attempt。Session 记录模型或 provider 上下文。提交排障信息时保留这些 ID，避免只提供可重复的显示名称。

| Run mode | 形态 |
| --- | --- |
| direct | 单 Agent 工作 |
| adaptive | Lead 动态协调的 Team 工作 |
| declared | 固定 Workflow revision |
| subrun | 父节点调用的子流程 |

## 查询执行的 API

以下接口可在本地模式直接调用。列表查询还需显式提供 `tenant`、`namespace`。完整请求示例见 [Workflow API](/v2/zh/service/workflows) 与[任务 API](/v2/zh/service/issues)。

| 操作 | API | 查询参数 / 返回 |
| --- | --- | --- |
| 查关联 Run | `GET /api/v1/orchestration-runs` | `issueId`、`definitionId`、`state`、`active`、`offset`、`limit`；返回 `{runs}` |
| 查 Run | `GET /api/v1/orchestration-runs/{runId}` | 返回 `{run}`，含 `state`、`input`、`output`、`waitReason`、失败信息 |
| 查执行图 | `GET /api/v1/orchestration-runs/{runId}/graph` | `run`、`nodes`、`edges`、`tasks`、`attempts`、可选 `childRuns` |
| 查历史事件 | `GET /api/v1/orchestration-runs/{runId}/events` | `after` 为 sequence 游标，`limit` 控制数量；返回 `{events}` |
| 查任务 | `GET /api/v1/agent-tasks/{taskId}` | 返回 `{task,inputSummaries}`，含结果和待处理输入概览 |
| 查 Attempt | `GET /api/v1/execution-attempts` | `taskId`、`state`、`limit`；返回 `{attempts}` |
| 查单次 Attempt | `GET /api/v1/execution-attempts/{attemptId}` | 返回 `{attempt}`，含后端、执行状态、结果和会话关联 |

`RunEvent.sequence` 用于本 Run 内增量读取，`type` 表示事件类型，`nodeId` / `agentTaskId` / `attemptId` 关联受影响对象。这里的 events 是 JSON 查询，不是 SSE。发布服务的业务客户端应优先使用 Turn 返回的 statusUrl / eventsUrl，见[统一服务 API](/v2/zh/service/service-api)。

以下示例沿用[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)中的本地地址和默认空间变量。将资源 ID 替换为前面创建或查询得到的值。

从实际工作记录取得 ISSUE_ID。列表可能包含多个 Run，先查看返回的 runs，再选择需要诊断的 RUN_ID；不要默认选择第一项。

```bash
ISSUE_ID="YOUR_ISSUE_ID"

curl -sS --fail-with-body -G "$BASE_URL/api/v1/orchestration-runs" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "issueId=$ISSUE_ID" \
  --data-urlencode "limit=25"
```

```bash
RUN_ID="RUN_ID_FROM_LIST"

curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-runs/$RUN_ID/graph"
```

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/orchestration-runs/$RUN_ID/events" \
  --data-urlencode "after=0" \
  --data-urlencode "limit=50"
```

从执行图的 tasks 选取 TASK_ID，读取任务与全部尝试，再根据实际状态选择 ATTEMPT_ID。

```bash
TASK_ID="TASK_ID_FROM_GRAPH"

curl -sS --fail-with-body "$BASE_URL/api/v1/agent-tasks/$TASK_ID"
```

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/execution-attempts" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "taskId=$TASK_ID" \
  --data-urlencode "limit=25"
```

```bash
ATTEMPT_ID="ATTEMPT_ID_FROM_LIST"

curl -sS --fail-with-body "$BASE_URL/api/v1/execution-attempts/$ATTEMPT_ID"
```

## Run 状态与控制

planned 表示尚未开始，running 表示正在推进，waiting 表示等待条件、信号或外部结果。paused 停止新节点派发，cancelling 等待取消收敛。终态为 cancelled、succeeded、partial_succeeded 或 failed。

Pause 不冻结已经开始的外部进程。Cancel 也不自动回滚已经产生的文件或外部操作，更不会自动接受 Issue。查看节点和 Attempt 的最终状态确认取消是否完成。

控制 Run 使用 `POST /api/v1/orchestration-runs/{runId}/pause`、`/resume`、`/cancel`，请求体 `{}`，返回 `{run}`。终态后用 `/rerun` 提交必填 `idempotencyKey` 和可选 `input`，返回新的 `{run}`，其中 `rerunOfRunId` 保留来源。

任务取消使用 `POST /api/v1/agent-tasks/{taskId}/cancel` 与当前 `expectedVersion`；任务重试使用 `/retry`。不要把 Task ID 传到 Run 控制接口，也不要直接改写 Attempt 状态来代替取消。

<Accordion title="按需控制 Run">

这些是互斥的运维选择。暂停停止新调度，取消请求结束整项运行；提交后重新 GET Run 确认状态。rerun 创建新的 Run，不恢复原 Attempt。

<Tabs>
<Tab title="暂停">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/orchestration-runs/$RUN_ID/pause"
```

</Tab>
<Tab title="继续调度">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/orchestration-runs/$RUN_ID/resume"
```

</Tab>
<Tab title="取消">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/orchestration-runs/$RUN_ID/cancel"
```

</Tab>
<Tab title="Rerun">

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-runs/$RUN_ID/rerun" \
  -H "Content-Type: application/json" \
  --data-binary @- <<'JSON'
{
  "idempotencyKey": "report-rerun-001"
}
JSON
```

</Tab>
</Tabs>

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-runs/$RUN_ID"
```

</Accordion>

## 三种重复执行

基础设施重试可以为同一 Task 产生新的 Attempt；节点策略重试可以产生新 Task；终态 Run 的人工 Rerun 创建新 Run 并保留来源。每次都要查看实际执行目标和输入，避免把旧的失败和新的成功混为一次运行。

显式配置 fresh fallback 才允许按策略跨候选后端重建执行上下文，恢复依赖 Issue、Comment 和 Artifact，而非原进程内状态。

## 等待与失败

先看 waitReason/error，再判断是否需要人工动作、在线 Host、Worker、模型凭据或更多容量。`requires_action` 的 Session 可能在等待工具结果，不能只依据该状态判断是人工审批。

工具事件、最终回复、Attempt 成功和 Issue 验收是不同证据。对最终交付使用[验收 API](/v2/zh/service/issues#inbox)；Managed 结果语义见[任务结果](/v2/zh/service/issues#managed-harness-task-outcomes)。

## 断线后继续观察

刷新页面后重新打开原工作，查询当前状态和已保存事件。SSE 长连接结束不代表任务失败。代理应及时转发事件；不要因为前端连接断开就用新的幂等键重复提交。

## 查询 Session 诊断

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/sessions" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "limit=25"
```

使用列表中的 `sessions[].session.id` UUID；这是运行诊断记录 ID，不是 `/agent-sessions` 的业务 Session ID。

```bash
DIAGNOSTIC_SESSION_ID="SESSION_UUID_FROM_LIST"

curl -sS --fail-with-body -G "$BASE_URL/api/v1/sessions/$DIAGNOSTIC_SESSION_ID" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE"
```

`GET /api/v1/sessions` 列出授权范围内的会话；具体会话使用 `GET /api/v1/sessions/{sessionRef}`。优先使用 Attempt 返回的 `sessionRef`（控制面记录 ID），不要混用运行时的 `sessionId` 或 provider 的 `providerSessionId`。查询时同时携带正确的 tenant/namespace。

诊断接口包括 `/messages`（`offset`、`limit`、`fromEnd`）、`/context`、`/events`、`/events/stream`、`/turns`。内容是否可用取决于运行时提供的观测与查询能力，注册为 Agent 不代表都支持完整上下文或恢复。该组接口用于执行诊断；业务侧会话管理优先使用下述 Agent API。

## Managed Session 的日志与恢复入口

Managed 原生执行使用 `/v1/sessions/{sessionId}`，其中 `turn_id` 标识该 Session 内的工作；内部执行 run 与编排 Run 不同。快照在 `resources` 下返回有界分页，并提供公共 `as_of` 游标。继续订阅 Session 事件流，使用受理时返回的 Turn ID 查询状态。

完整原生日志和检查点对象属于私有恢复记录。普通调用方读取公共投影及检查点元数据，这里不提供旧的特权 trace/recovery 路由。断线后重连，不另发任务。长工具自动恢复；真实待办通过 Turn Input 答复。当前原生 capabilities 未开放显式失败 Turn resume、checkpoint restore 或 Fork。详见[会话日志与恢复](/v2/zh/service/session-event-log)。

## 一次排障应记录什么

用[故障修复案例](/v2/zh/service/cases/incident-to-pr)练习：从主 Issue 查询 Run graph，在使用 Hosted 目标时找到执行任务与最新 Attempt，再核对其 Session、日志与文件。需要图形入口时参阅[控制台执行流程](/v2/zh/service/console/index#console-orchestration)。

| 记录 | 用途 |
| --- | --- |
| Issue ID 与验收要求 | 确定要交付什么，以及是否仍待人工验收 |
| Run ID、mode 与目标 revision | 确定是哪次执行、采用哪种编排和定义 |
| Node / Task / Attempt ID | 找到实际失败步骤，区分重试层级 |
| Session ID、Host/provider（如果适用） | 定位上下文与执行主机 |
| 状态、错误、时间与最后事件游标 | 区分正在等待、已经终止和仅观察连接中断 |
| Artifact 与测试日志 | 判断结果是否满足要求，而非仅查看状态标签 |

若第二次 Attempt 成功，仍保留第一次失败的记录，并把交付指向成功执行的文件。若只有 SSE 断线，按[SSE 指南](/v2/zh/service/sse-events)使用原调用的游标恢复观察；不要把它当作新的业务任务。

<span id="managed-agent-execution"></span>
<span id="创建运行上下文"></span>
<span id="模型与工具循环"></span>
<span id="持久化与恢复"></span>
<span id="通过统一-api-观察发布的服务"></span>
<span id="通过-agent-api-接入-managed-agent"></span>
<span id="完成不等于验收"></span>

## Managed 执行与持久化

控制面解析 Agent 定义、版本、环境及知识与凭据引用。Dataplane 将定义文件准备到会话目录，构建 Harness，并连接持久状态存储。定义快照、执行文件和共享资源各有自己的生命周期：保存 Agent 配置不等于修改正在执行的所有实例。

Workspace 保存能力定义；Environment 决定文件和命令在哪执行；Memory Store 是共享知识；Vault 在连接工具前解析凭据。资源用法集中在本分类的四个资源页面。

Harness 使用显式 Model 或部署默认 Model，按指令进行推理、请求工具并读取结果。`maxIters` 限制迭代，工具权限决定操作能否执行。需要确认时任务可能等待用户决定；这时重复发送相同工作可能造成额外执行。

Local 工具在 Dataplane 环境执行，sandbox 使用 E2B，remote 使用共享文件存储，self_hosted 将工具工作交给 Worker。self_hosted 中模型仍由 Dataplane 驱动；Worker 的职责是接收工具工作并返回结果。

会话状态、事件和协调记录存储在部署配置的持久存储中。多副本使用协调租约约束执行；恢复仍依赖数据库、工作文件、所选环境和外部工具可用。一次工具成功后的外部副作用不会因服务重启自动撤销。

共享 Memory 是按需访问的实时平台知识，不应理解为每次调用都完整复制进模型提示。修改它需要按共享知识维护流程处理，不能假设 Agent 定义版本同时固定所有外部知识。
