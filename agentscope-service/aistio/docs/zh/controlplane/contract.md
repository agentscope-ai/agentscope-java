# 数据面契约一致性文档

本文档定义控制面与数据面之间的 HTTP 契约 API。无论 Agent 采用 Declarative 还是 BYO 部署模式，数据面都需要实现一套标准的 HTTP 接口，控制面通过这些接口与数据面交互。这类似于 Istio 要求 Envoy 实现 xDS 协议。

契约分为三个等级（Contract Level），数据面按自身能力实现其中之一。

---

## 契约等级（Contract Level）

### Level 1 -- 最小可纳管

实现 Level 1 即可被控制面发现与纳管。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/agentscope/info` | GET | 返回 agent 元数据 |
| `/agentscope/health` | GET | 健康检查，返回 200 表示健康 |

#### `GET /agentscope/info`

控制面发现数据面后调用的第一个接口。返回 agent 元数据，控制面据此填充 Agent CRD 的 `status.dataPlaneInfo`。

**请求：**

```
GET /agentscope/info HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应（200 OK）：**

```json
{
  "name": "customer-support-agent",
  "displayName": "客服助手",
  "description": "处理客户咨询的智能体",
  "runtime": "agentscope-java",
  "version": "1.2.0",
  "sdkVersion": "0.8.0",
  "contractLevel": 3,
  "capabilities": [
    "session-reporting",
    "hot-reload",
    "context-compression",
    "sandbox-request"
  ],
  "agentConfig": {
    "modelProvider": "DashScope",
    "model": "qwen-max",
    "tools": ["search_docs", "get_faq", "create_ticket"],
    "maxTurns": 50
  },
  "port": 8080
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | agent 标识名称 |
| `displayName` | string | 否 | 显示名称 |
| `description` | string | 否 | 描述信息 |
| `runtime` | string | 是 | 运行时类型：`agentscope-java` / `agentscope-go` / `langchain` / `custom` |
| `version` | string | 否 | 数据面应用版本 |
| `sdkVersion` | string | 否 | AgentScope SDK 版本 |
| `contractLevel` | int32 | 是 | 实现的契约等级（仅 1/2/3；**禁止**声明 `contractLevel: 4`，细粒度能力一律走 `capabilities`） |
| `capabilities` | []string | 否 | 数据面声明支持的能力列表（见文末「Capabilities 词汇表」） |
| `agentConfig` | object | 否 | BYO 模式下数据面自报的 agent 配置 |
| `port` | int32 | 否 | 服务端口，默认 8080 |

#### `GET /agentscope/health`

健康检查端点，控制面定期轮询以判断数据面是否健康。

**请求：**

```
GET /agentscope/health HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应：**

- `200 OK` -- 数据面健康
- 非 200 或连接失败 -- 数据面不健康

---

### Level 2 -- 会话观测

在 Level 1 基础上增加会话查询能力，使控制面能拉取活跃会话列表并查看会话详情。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/agentscope/sessions` | GET | 返回活跃会话列表 |
| `/agentscope/sessions/{id}/state` | GET | 返回会话详细状态 |

#### `GET /agentscope/sessions`

返回数据面当前所有活跃会话的快照列表。控制面通过 `SessionPoller` 每 15 秒轮询该接口，将结果同步到 `AgentSession` CRD。

**请求：**

```
GET /agentscope/sessions HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应（200 OK）：**

```json
{
  "sessions": [
    {
      "id": "sess-abc123",
      "phase": "Active",
      "startedAt": "2026-06-26T10:00:00Z",
      "lastActiveAt": "2026-06-26T10:35:00Z",
      "messageCount": 42,
      "tokenUsage": {
        "promptTokens": 15000,
        "completionTokens": 8000
      },
      "contextPressure": 0.56,
      "taskSummary": {
        "total": 5,
        "pending": 1,
        "inProgress": 2,
        "completed": 2
      }
    }
  ]
}
```

**`SessionSnapshot` 字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 会话唯一标识 |
| `phase` | string | 会话阶段：`Active` / `Idle` / `Compressing` / `Terminated` |
| `startedAt` | string | 会话开始时间（RFC 3339） |
| `lastActiveAt` | string | 最近活跃时间（RFC 3339） |
| `messageCount` | int32 | 消息总数 |
| `tokenUsage` | object | Token 使用量 |
| `contextPressure` | float64 | 上下文压力比（0.0 ~ 1.0） |
| `taskSummary` | object | 任务统计 |
| `framework` | string | Level 1 扩展：框架标识，如 `claude-agent-sdk` / `langchain` / `adk` |
| `frameworkVersion` | string | Level 1 扩展：框架版本 |
| `contextHash` | string | Level 1 扩展：生效 Context 的 SHA-256 前 16 hex，控制面据此判断 Context 是否变化 |
| `isCompacted` | bool | Level 1 扩展：是否经过了 Context 压缩 |
| `effectiveMessageCount` | int32 | Level 1 扩展：压缩后的生效消息数（≠ `messageCount`） |

#### `GET /agentscope/sessions/{id}/state`

返回指定会话的详细状态快照。本响应形状已按 BYO Console 能力规划**冻结**；控制面 / Dashboard 据此渲染会话详情。

**请求：**

```
GET /agentscope/sessions/sess-abc123/state HTTP/1.1
Host: <agent-pod-ip>:8080
```

**规范响应（200 OK）：**

```json
{
  "id": "sess-abc123",
  "phase": "Active",
  "busy": true,
  "model": "qwen-max",
  "startedAt": "2026-06-26T10:00:00Z",
  "lastActiveAt": "2026-06-26T10:35:00Z",
  "messageCount": 42,
  "tokenUsage": {
    "promptTokens": 15000,
    "completionTokens": 8000,
    "totalTokens": 23000,
    "maxTokens": 128000
  },
  "contextPressure": 0.56,
  "isCompacted": true,
  "taskSummary": {
    "total": 5,
    "pending": 1,
    "inProgress": 2,
    "completed": 2
  },
  "framework": "langgraph",
  "frameworkState": {}
}
```

**`SessionState` 字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 会话唯一标识 |
| `phase` | string | 是 | 运维状态机（权威）：`active` / `idle` / `compressing` / `archived` / `terminated` |
| `busy` | bool | 否 | **派生/兼容字段**：`busy := (phase == active)`。新实现以 `phase` 为准；未上报时控制面不得默认 `false` |
| `model` | string | 否 | 当前生效模型名 |
| `startedAt` | string | 否 | 会话开始时间（RFC 3339） |
| `lastActiveAt` | string | 否 | 最近活跃时间（RFC 3339） |
| `messageCount` | int32 | 否 | 消息总数 |
| `tokenUsage` | object | 否 | Token 使用量 |
| `tokenUsage.promptTokens` | int64 | 否 | 输入 token |
| `tokenUsage.completionTokens` | int64 | 否 | 输出 token |
| `tokenUsage.totalTokens` | int64 | 否 | 合计（prompt + completion） |
| `tokenUsage.maxTokens` | int64 | 否 | 上下文窗口上限；UI 进度条分母 |
| `contextPressure` | float64 | 否 | 上下文压力比（0.0 ~ 1.0） |
| `isCompacted` | bool | 否 | 是否经过 Context 压缩 |
| `taskSummary` | object | 否 | 任务聚合统计 |
| `framework` | string | 否 | 框架标识，如 `langgraph` / `claude-agent-sdk` |
| `frameworkState` | object | 否 | 框架私有状态；**仅**供 Console Raw 面板展示，控制面不解析业务语义 |

**`phase` 状态机（运维视角）：**

| phase | 含义 | instanceRef | compress | 新 turn |
|-------|------|-------------|----------|---------|
| `active` | 正在推理 | 硬绑定当前实例 | 禁止 | 进行中 |
| `idle` | 推理结束，可运维 | 软亲和（保留偏好，可换实例） | 允许 | 允许 → `active` |
| `compressing` | 正在压缩 | 硬绑定执行实例 | 进行中 | 禁止 |
| `archived` | 久不活跃、主动归档，或数据面 Level-1 暂时不再上报（Console History） | 可空/只读 | 禁止 | 禁止（restore → `idle`） |
| `terminated` | **仅**显式硬销毁（Operate Terminate / DELETE / team 销毁 / DP 明确上报 `terminated`）。DP 失踪 ≠ terminated | — | 禁止 | 禁止 |

软亲和：`idle` 保留 `instanceRef` 作偏好；用户在新实例发起 turn 后，控制面 poll/ASDP upsert 会用非空 `instanceRef` 覆盖。compress 优先打亲和实例，不可达则 fallback 到同 agent 健康实例。

**Session / Turn / Duration（冻结）：**

| 概念 | 标识 | 含义 |
|------|------|------|
| Session | `sessionId` | 可 resume 的对话线程；`phase` 描述线程运维态 |
| Turn | `(sessionId, turnIndex)` | 一轮用户请求的推理周期（DP 一次 `call()`） |
| Turn duration | `session_turns.duration_ms` | `active` 开始 → `idle`/abort/terminate 结束；**同 sessionId 多轮各自记一条** |
| Session lifetime | `sessions.started_at` → end | 线程墙钟寿命；**不作** Overview duration 排行依据 |

- 控制面在 Level-1 phase 边沿开/关 turn（进入 `active` → open；离开 `active` → close）。
- Overview「按 duration」只展示 **`phase=active` 的当前 running turn elapsed**。
- Agent 详情 Active/History（`archived`）是 **session 级**；Session 详情 Turn timeline 是 **turn 级**，勿混用 History 一词。

**字段缺失语义（冻结规则）：**

- 仅 `id` + `phase` 必填；其余字段未上报时**省略该字段**（或显式 `null`），UI 显示为「未上报」。
- **禁止**用 `0` / `false` / `""` 填补未知值——假零会误导进度条与任务统计。
- `busy` 已弃用为正交信号；与 `phase` 冲突时以 `phase` 为准。
- `tokenUsage.maxTokens` 供 UI 进度条使用；缺省时进度条不可用。
- `frameworkState` 仅透传给 Raw 面板；结构化观测走 `taskSummary` / `GET .../tasks` 等显式字段。

**响应（404 Not Found）：** 会话 ID 不存在时返回 404。

#### `GET /agentscope/sessions/{id}/tasks`

按需拉取会话内任务明细。Capability：`task-query`。未声明该能力时控制面不调用；数据面未实现应返回 `501`。

**请求：**

```
GET /agentscope/sessions/sess-abc123/tasks HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应（200 OK）：**

```json
{
  "tasks": [
    {
      "id": "task-1",
      "subject": "查询订单信息",
      "state": "completed",
      "owner": "main",
      "blockedBy": null,
      "updatedAt": "2026-06-26T10:30:00Z",
      "frameworkMeta": {}
    },
    {
      "id": "task-2",
      "subject": "提交退款申请",
      "state": "in_progress",
      "owner": "main",
      "blockedBy": null,
      "updatedAt": "2026-06-26T10:35:00Z",
      "frameworkMeta": {"node": "refund_node"}
    }
  ]
}
```

**`Task` 字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 任务唯一标识 |
| `subject` | string | 任务主题 / 简述 |
| `state` | string | `pending` / `in_progress` / `completed` / `blocked` / `cancelled` |
| `owner` | string | 任务归属（主会话、subagent 名等） |
| `blockedBy` | string \| null | 阻塞来源任务 ID；非 blocked 时为 `null` 或省略 |
| `updatedAt` | string | 最近更新时间（RFC 3339） |
| `frameworkMeta` | object | 框架私有元数据（可选） |

**错误：** `404` 会话不存在；`501` 数据面不支持 `task-query`。

---

### Level 3 -- 全功能协调

在 Level 2 基础上增加主动控制指令，使控制面可以对会话下发压缩或终止操作。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/agentscope/sessions/{id}/compress` | POST | 触发会话上下文压缩 |
| `/agentscope/sessions/{id}/terminate` | POST | 终止会话 |

#### Command 响应与错误语义（冻结）

所有会话控制指令（`compress` / `terminate` / `abort` 及可选的 undo/redo 等）成功与失败语义统一如下。与 ASDP `SessionCommand` 下行同源——HTTP 路径为同语义的 REST 对等实现。

**成功响应（200 OK）：**

```json
{
  "accepted": true,
  "commandId": "cmd-7f3a",
  "phase": "Compressing",
  "result": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `accepted` | bool | 指令已被数据面接受 |
| `commandId` | string | 指令跟踪 ID（幂等 / 审计） |
| `phase` | string | 接受后的会话阶段（可与接受前相同） |
| `result` | object | 指令相关附加结果；无可为 `{}` |

**错误语义：**

| HTTP | 含义 | 说明 |
|------|------|------|
| `501 Not Implemented` | unsupported | 数据面未声明 / 未实现该指令对应 capability |
| `409 Conflict` | busy / wait_idle | 会话 `busy=true`，需等待当前 turn 结束后再下发 |
| `404 Not Found` | not_found | 会话不存在 |
| `503 Service Unavailable` | unreachable | **控制面侧**：无法到达数据面实例（无健康 endpoint / ASDP 连接） |
| `500 Internal Server Error` | failed | 数据面执行失败 |

#### `POST /agentscope/sessions/{id}/compress`

触发指定会话的上下文压缩。当 `contextPressure` 超过阈值时，控制面可主动下发该指令。

**请求：**

```
POST /agentscope/sessions/sess-abc123/compress HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应：** 见上方「Command 响应与错误语义」。

#### `POST /agentscope/sessions/{id}/terminate`

终止指定会话。

**请求：**

```
POST /agentscope/sessions/sess-abc123/terminate HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应：** 见上方「Command 响应与错误语义」。

---

### Capability 门控扩展端点

在三级契约之上，数据面可通过 `capabilities` 细粒度声明以下扩展端点（sdk-design.md §4）。控制面未看到对应 capability 时不调用这些端点；数据面未实现时应返回 `501 Not Implemented`，而不是空数据。**不引入 `contractLevel: 4`**——扩展一律走 capability 门控。

#### 已实现

| 端点 | 方法 | Capability | 说明 |
|------|------|------------|------|
| `/agentscope/sessions/{id}/context` | GET | `context-query` | 当前生效 Context 快照 |
| `/agentscope/sessions/{id}/messages` | GET | `message-query` | 完整消息历史，`?offset=&limit=` 分页 |
| `/agentscope/subagents` | GET | `subagent-inventory` | 当前实例 subagent 清单 |
| `/agentscope/workspaces` | GET | `workspace-inventory` | 当前实例 workspace 清单 |

#### 新增（BYO Console 能力规划，capability 门控）

| 端点 | 方法 | Capability | 说明 |
|------|------|------------|------|
| `/agentscope/sessions/{id}/abort` | POST | `session-abort` | 中止当前 turn（不 terminate 会话） |
| `/agentscope/sessions/{id}/tasks` | GET | `task-query` | 会话任务明细（见上方规范） |
| `/agentscope/sessions/{id}/subagent-tasks` | GET | `subagent-task-query` | 子代理任务列表 |
| `/agentscope/sessions/{id}/subagent-tasks/{taskId}` | DELETE | `subagent-task-command` | 取消 / 清理指定子代理任务 |
| `/agentscope/sessions/{id}/undo` | POST | `session-undo` | （可选）撤销上一步 |
| `/agentscope/sessions/{id}/redo` | POST | `session-redo` | （可选）重做 |
| `/agentscope/sessions/{id}/plan-mode` | POST | `plan-mode` | （可选）切换 / 设置 plan mode |
| `/agentscope/sessions/{id}/export-transcript` | GET | `export-transcript` | （可选）导出会话 transcript |

`abort` / `undo` / `redo` / `plan-mode` 的成功与错误响应遵循上方「Command 响应与错误语义」。

#### `GET /agentscope/sessions/{id}/context`

返回指定会话的当前**生效** Context（压缩后视图，不是全部历史）。与 ASDP `ContextReport` / Store `context_snapshots` 同构。

**响应（200 OK）：**

```json
{
  "sessionId": "sess-abc123",
  "capturedAt": "2026-07-28T10:00:00Z",
  "contextHash": "3fa85f64c91a2b10",
  "systemPrompt": "你是客服助手...",
  "messages": [
    {"role": "system", "content": "[压缩摘要] 用户咨询订单退款...", "isCompaction": true},
    {"role": "user", "content": "我的订单什么时候到？"}
  ],
  "tools": [{"name": "search_docs", "description": "检索知识库"}],
  "isCompacted": true,
  "compactionSummary": "用户咨询订单退款...",
  "originalMessageCount": 58,
  "compactedAt": "2026-07-28T09:58:00Z",
  "totalTokens": 18000,
  "maxTokens": 32000,
  "framework": "claude-agent-sdk",
  "frameworkState": {"session_type": "store-backed"}
}
```

**错误：** `404` 会话不存在；`501` 数据面不支持 `context-query`。

#### `GET /agentscope/sessions/{id}/messages`

Level 3 完整消息历史（Level 2 事件流只存摘要；全文走本端点按需拉取，不主动上报）。

**响应（200 OK）：**

```json
{
  "sessionId": "sess-abc123",
  "offset": 0,
  "limit": 50,
  "total": 120,
  "messages": [
    {
      "seq": 1,
      "role": "user",
      "content": "...完整内容...",
      "toolName": null,
      "occurredAt": "2026-07-28T10:00:00Z"
    }
  ]
}
```

**错误：** `404` 会话不存在；`501` 数据面不支持 `message-query`。

#### `GET /agentscope/subagents` / `GET /agentscope/workspaces`

返回当前实例的 subagent / workspace 清单：

```json
{
  "subagents": [
    {
      "name": "code-reviewer",
      "description": "代码审查子代理",
      "tools": ["read_file", "git_diff"],
      "workspaceMode": "isolated",
      "url": "",
      "invokeCount": 12,
      "lastInvokedAt": "2026-07-28T09:30:00Z"
    }
  ]
}
```

```json
{
  "workspaces": [
    {"path": "/workspace/sess-abc123", "mode": "isolated", "sizeBytes": 1048576, "ownerRef": "sess-abc123"}
  ]
}
```

**错误：** `501` 数据面不支持对应 inventory capability。

---

## 各等级下控制面行为

控制面根据数据面上报的 `contractLevel` 自动降级行为：

| 功能 | Level 1 | Level 2 | Level 3 |
|------|---------|---------|---------|
| 发现与纳管 | 支持 | 支持 | 支持 |
| 健康监测 | 支持 | 支持 | 支持 |
| 会话列表拉取 | 不支持 | 支持 | 支持 |
| 会话状态查看 | 不支持 | 支持 | 支持 |
| 会话压缩指令 | 不支持 | 不支持 | 支持 |
| 会话终止指令 | 不支持 | 不支持 | 支持 |

**降级逻辑实现（`SessionPollerReconciler`）：**

- `contractLevel < 2`：控制面跳过会话轮询，仅执行健康探测。Agent 的 `SessionPolling` condition 标记为 `SessionPollingUnsupported`。
- `contractLevel = 2`：控制面拉取会话列表和状态，同步到 `AgentSession` CRD，但不能下发 compress/terminate 指令。
- `contractLevel = 3`：完整功能，包括会话观测和 compress/terminate 指令下发。

对应代码位于：
- 轮询控制器：`internal/controller/session_poller.go`
- HTTP Prober：`internal/prober/http_prober.go`
- Prober 接口：`internal/prober/prober.go`

---

## Mock 数据面

项目提供了 Mock 数据面服务（`test/mock/dataplane.go`），用于 CI 测试和本地开发验证。Mock 实现了完整的 Level 1~3 契约 API，以及 capability 门控扩展端点（`context` / `messages` / `subagents` / `workspaces`，见 `SetContext` / `SetMessages` 等数据注入方法）。

### 使用方式

```go
import "github.com/agentscope/agentscope-go/control-plane/test/mock"

// 创建 Level 2 的 mock 数据面
dp := mock.NewMockDataPlane(2)
defer dp.Close()

// 预置会话数据
dp.AddSession(prober.SessionSnapshot{
    ID:           "sess-001",
    Phase:        "Active",
    MessageCount: 10,
})

// 设置会话状态
dp.SetSessionState("sess-001", &prober.SessionState{
    SessionID: "sess-001",
    Summary:   "处理用户咨询中",
    ContextPressure: &prober.ContextPressureInfo{
        UsedTokens: 8000,
        MaxTokens:  32000,
        Ratio:      0.25,
    },
})

// 获取 mock 服务端点
endpoint := dp.Endpoint() // http://127.0.0.1:<port>
```

### Mock 支持的操作

| 操作 | 方法 | 说明 |
|------|------|------|
| `NewMockDataPlane(level)` | 构造函数 | 创建指定 contractLevel 的 mock |
| `SetContractLevel(level)` | 配置 | 更新 advertised contractLevel |
| `SetCapabilities(caps)` | 配置 | 设置 `/info` 的 capabilities；未声明的能力门控端点返回 `501` |
| `InjectFault501(cap)` | 故障注入 | 指定 capability 的端点强制返回 `501` |
| `InjectFault409Compress()` | 故障注入 | `compress` 返回 `409` + `wait_idle` |
| `MarkStale()` / `ClearStale()` | 故障注入 | `/health` 返回 `503`（模拟无心跳） |
| `AddSession(snap)` | 数据注入 | 添加一个会话到列表 |
| `SetSessionState(id, state)` | 数据注入 | 设置会话详细状态 |
| `SetTasks(id, tasks)` | 数据注入 | 设置 `GET .../tasks` 返回值 |
| `CompressCalledFor(id)` | 断言 | 检查 compress 是否被调用 |
| `TerminateCalledFor(id)` | 断言 | 检查 terminate 是否被调用 |
| `AbortCalledFor(id)` | 断言 | 检查 abort 是否被调用 |
| `Endpoint()` | 查询 | 返回 mock 服务的 HTTP 地址 |
| `Close()` | 清理 | 关闭 mock 服务 |

---

## 一致性验证

使用 Mock 数据面验证控制面在各 contractLevel 下的行为：

### 场景 1：contractLevel = 1（最小可纳管）

```
输入：mock 数据面，contractLevel=1
预期：
  - 控制面成功探测 /agentscope/info，获取元数据
  - 控制面定期调用 /agentscope/health 进行健康检查
  - 实例出现在 registry / agent 列表中
  - 会话非必需（SessionPoller 可跳过；sessions 可为空）
  - Agent status 的 SessionPolling condition 为 SessionPollingUnsupported
```

### 场景 2：contractLevel = 2（会话观测）

```
输入：mock 数据面，contractLevel=2，预置 session，capabilities 无 session-command
预期：
  - 控制面探测并纳管成功，可拉取会话列表
  - compress / terminate 返回 unsupported（501），且不调用数据面指令端点
```

### 场景 3：contractLevel = 3（全功能协调）

```
输入：mock 数据面，contractLevel=3，预置 session，contextPressure 较高
预期：
  - 会话观测行为同 Level 2
  - 控制面可成功调用 compress 端点，mock.CompressCalledFor(id) 为 true
  - 控制面可成功调用 terminate 端点，mock.TerminateCalledFor(id) 为 true
  - 未声明 task-query 时 tasks 端点 / CP 门控返回 501，不假装支持
```

### 场景 4：故障与亲和

```
- 声明了 capability 但端点 501 → CP 按 unsupported 降级
- compress 返回 409 → 传播 busy / wait_idle
- 实例 stale → compress/abort 返回 503 unreachable，且不打到 sibling 实例
```

### 运行一致性测试

```bash
cd agentscope-service/aistio
go test ./test/mock/ ./internal/sessionops/ -count=1
```

---

## 第三方数据面符合性清单（短）

第三方 / BYO 数据面实现契约时，至少自检：

- [ ] `GET /agentscope/info` 返回正确的 `contractLevel`（仅 1/2/3）与 `capabilities`
- [ ] `GET /agentscope/health` 在实例存活时返回 200；无心跳 / 不健康时控制面可将其标 stale
- [ ] Level ≥ 2：实现 `GET /agentscope/sessions`（及按需 `/state`）
- [ ] Level ≥ 3 且声明 `session-command`：实现 `POST .../compress|terminate`；忙时 `409` + `hint=wait_idle`
- [ ] 未声明的 capability：**不要**返回 200 空数据；应 `501` 或根本不暴露端点；控制面未看到 capability 时不调用
- [ ] 声明了但未实现（过度声明）：端点 `501`；控制面运行时按 unsupported 处理
- [ ] 扩展能力（`task-query` / `session-abort` / `context-query` 等）一律走 capabilities 门控，**禁止** `contractLevel: 4`
- [ ] 会话指令必须打到持有该会话的实例（instanceRef）；控制面不得 fallback 到 sibling

自动化对照：`internal/sessionops` 中 `TestMatrix_*` + `test/mock` 故障注入用例。

---

## Capabilities 词汇表（冻结）

数据面在 `GET /agentscope/info` 的 `capabilities` 字段与 ASDP `ConnectRequest.capabilities` 中声明以下能力词汇。控制面按词汇门控行为：未声明的能力不会被调用或期待。本表为 BYO Console 规划下的**冻结词汇**；新增词汇需同步更新本文档。

### 既有词汇

| 词汇 | 含义 | 对应通道与端点 |
|------|------|----------------|
| `session-reporting` | 会话摘要快照上报 | ASDP `SessionReport`；HTTP `GET /agentscope/sessions` |
| `event-reporting` | 事件流摘要上报（默认关闭，SDK `enable_events` 开启） | ASDP `EventReport` |
| `context-reporting` | 生效 Context 变更主动推送（hash 变更防抖 + compaction 立即推） | ASDP `ContextReport` |
| `context-query` | 按需查询当前生效 Context | HTTP `GET /agentscope/sessions/{id}/context` |
| `message-query` | 完整消息历史分页拉取 | HTTP `GET /agentscope/sessions/{id}/messages` |
| `session-command` | 接收 compress / terminate 控制指令 | HTTP `POST .../compress\|terminate`；ASDP `SessionCommand` 下行 |
| `subagent-inventory` | subagent 运行时清单上报与查询 | ASDP `InventoryReport`；HTTP `GET /agentscope/subagents` |
| `workspace-inventory` | workspace 运行时清单上报与查询 | ASDP `InventoryReport`；HTTP `GET /agentscope/workspaces` |
| `team-coordination` | 加入/离开 AgentTeam（`team_join` / `team_leave`）并参与共享任务板 | ASDP `SessionCommand{command=team_join\|team_leave, params=TeamContext}`；HTTP `POST /agentscope/teams/join`；上行 `TeamEventReport` |

### AgentTeams：`team_join` / `team_leave`

控制面为 BYO 成员分配运行时 `sessionId` 与 `TeamContext` 后，向具备 `team-coordination` 的健康实例下发：

```json
{
  "sessionId": "<cp-allocated-id>",
  "command": "team_join",
  "params": {
    "teamName": "research",
    "objective": "...",
    "myRole": "worker-1",
    "isLead": false,
    "members": [{"name": "lead", "agentRef": "a", "status": "working"}],
    "availableActions": ["listTasks", "claimTask", "completeTask", "sendMessage", "broadcastMessage", "listMembers"]
  }
}
```

数据面收到后应：

1. `registerExternalSession(sessionId, gateKey)`（或等价映射），使后续 `runWakeup` 可命中该 id；
2. 挂载 `TeamsMiddleware`（按 `isLead` 裁剪工具面）并启动首轮 wakeup；
3. （可选）经 ASDP 上报 `TeamEventReport{event_type=member_joined}`。

`team_leave` 对称：停止团队工具、解除外部 session 映射，可选上报 `member_left`。

**Managed 成员不走 `team_join`。** 控制面调用 product `POST /api/internal/sessions/find-or-create`（`externalKey=team|{ns}/{team}|{member}`），把返回的 `sessionId` 写入 runtime `store.Session`（含 `teamContext`），经 `GET /api/internal/sessions/{id}/resolve` 的 `teamContext` 字段下发到数据面构建链路，再 `POST /api/sessions/{id}/events` 投起跑 `user.message`。

### 新增词汇（BYO Console）

| 词汇 | 含义 | 对应通道与端点 |
|------|------|----------------|
| `session-abort` | 中止当前 turn | HTTP `POST .../abort`；ASDP `SessionCommand`（`command=abort`） |
| `task-query` | 会话任务明细查询 | HTTP `GET .../tasks` |
| `subagent-task-query` | 子代理任务列表查询 | HTTP `GET .../subagent-tasks` |
| `subagent-task-command` | 子代理任务控制（取消等） | HTTP `DELETE .../subagent-tasks/{taskId}` |
| `session-undo` | （可选）撤销 | HTTP `POST .../undo` |
| `session-redo` | （可选）重做 | HTTP `POST .../redo` |
| `plan-mode` | （可选）Plan mode 切换 | HTTP `POST .../plan-mode` |
| `export-transcript` | （可选）导出 transcript | HTTP `GET .../export-transcript` |

### 冻结规则

- **禁止 `contractLevel: 4`**：契约等级仅 1/2/3；Context / tasks / abort 等扩展一律用 `capabilities` 门控，不抬升数字等级。
- 控制面遇到**未知** capability 字符串时**忽略**（向前兼容），不得因此拒绝握手或降级整条连接。
- **过度声明是 bug**：`capabilities` 中声明了某能力但对应端点返回 `501` / 未实现，视为数据面实现缺陷；控制面可在诊断中标记，但运行时仍按「不支持」降级。
- 控制面在握手 / info 中未看到某 capability 时，**不调用**对应端点、不期待对应上报；依赖该能力的 REST 读路径返回明确的「数据面不支持」错误，而不是空数据。
- 数据面未实现某 capability 对应端点时，应返回 `501 Not Implemented`，而不是 200 空数据。
- 历史词汇（如 `hot-reload`、`context-compression`、`sandbox-request`）与本表正交，数据面可按需附加声明。
