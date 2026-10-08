---
title: "执行参考：Session、Run 与 Attempt"
en_link: /v2/en/service/sessions
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

应用通常从 Agent API 会话、Issue 或Session API 提交工作。本页用于沿业务请求查找 Run、Task、Attempt 和 Session，理解它们的状态与控制接口。执行详情仍受空间、工作自身权限及运维能力约束。

## 关联一项工作

从 Issue ID 查询关联 Run，先看输入、运行方式与实际目标，然后查询节点、AgentTask 和最新 Attempt。Session 记录模型或 provider 上下文。提交排障信息时保留这些 ID，避免只提供可重复的显示名称。

| Run mode | 形态 |
| --- | --- |
| direct | 单 Agent 工作 |
| adaptive | Lead 动态协调的 Team 工作 |
| declared | 固定 Workflow revision |
| subrun | 父节点调用的子流程 |

## 查询执行的 API

以下接口使用用户 Bearer token，并携带相同的 `X-AgentScope-Tenant`、`X-AgentScope-Namespace`。列表查询还需显式提供 `tenant`、`namespace`。完整请求示例见 [Workflow API](/v2/zh/service/workflows) 与[任务 API](/v2/zh/service/issues)。

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

## Run 状态与控制

planned 表示尚未开始，running 表示正在推进，waiting 表示等待条件、信号或外部结果。paused 停止新节点派发，cancelling 等待取消收敛。终态为 cancelled、succeeded、partial_succeeded 或 failed。

Pause 不冻结已经开始的外部进程。Cancel 也不自动回滚已经产生的文件或外部操作，更不会自动接受 Issue。查看节点和 Attempt 的最终状态确认取消是否完成。

控制 Run 使用 `POST /api/v1/orchestration-runs/{runId}/pause`、`/resume`、`/cancel`，请求体 `{}`，返回 `{run}`。终态后用 `/rerun` 提交必填 `idempotencyKey` 和可选 `input`，返回新的 `{run}`，其中 `rerunOfRunId` 保留来源。

任务取消使用 `POST /api/v1/agent-tasks/{taskId}/cancel` 与当前 `expectedVersion`；任务重试使用 `/retry`。不要把 Task ID 传到 Run 控制接口，也不要直接改写 Attempt 状态来代替取消。

## 三种重复执行

基础设施重试可以为同一 Task 产生新的 Attempt；节点策略重试可以产生新 Task；终态 Run 的人工 Rerun 创建新 Run 并保留来源。每次都要查看实际执行目标和输入，避免把旧的失败和新的成功混为一次运行。

显式配置 fresh fallback 才允许按策略跨候选后端重建执行上下文，恢复依赖 Issue、Comment 和 Artifact，而非原进程内状态。

## 等待与失败

先看 waitReason/error，再判断是否需要人工动作、在线 Host、Worker、模型凭据或更多容量。`requires_action` 的 Session 可能在等待工具结果，不能只依据该状态判断是人工审批。

工具事件、最终回复、Attempt 成功和 Issue 验收是不同证据。对最终交付使用[验收 API](/v2/zh/service/issues#inbox)；Managed 结果语义见[任务结果](/v2/zh/service/issues#managed-harness-task-outcomes)。

## 断线后继续观察

刷新页面后重新打开原工作，查询当前状态和已保存事件。SSE 长连接结束不代表任务失败。代理应及时转发事件；不要因为前端连接断开就用新的幂等键重复提交。

## 查询 Session 诊断

`GET /api/v1/sessions` 列出授权范围内的会话；具体会话使用 `GET /api/v1/sessions/{sessionRef}`。优先使用 Attempt 返回的 `sessionRef`（控制面记录 ID），不要混用运行时的 `sessionId` 或 provider 的 `providerSessionId`。查询时同时携带正确的 tenant/namespace。

诊断接口包括 `/messages`（`offset`、`limit`、`fromEnd`）、`/context`、`/events`、`/events/stream`、`/turns`。内容是否可用取决于运行时提供的观测与查询能力，注册为 Agent 不代表都支持完整上下文或恢复。该组接口用于执行诊断；业务侧会话管理优先使用下述 Agent API。

## Managed Session 的日志与恢复入口

Agent API 使用 session_id → turn_id → run_id 关联推理工作；run_id 不是编排 Run ID。GET snapshot 返回已保存的 items/tools/turns/required_actions 与公共 as_of cursor，再从 events/stream 续传。GET turns 按提交返回的 turn ID 查询队列和结果。

完整执行历史与 checkpoint 在原生 Session Log；普通客户端读公共投影，授权管理员才能读取 trace/recovery。断线只重连，不再 POST 输入；中断的执行先检查未知工具结果，再按 actions/resume 流程继续。具体位置、HTTP 示例和边界见 [Agent API、会话日志与持久 SSE](/v2/zh/service/session-event-log)。

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
