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
| `contractLevel` | int32 | 是 | 实现的契约等级（1/2/3） |
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

返回指定会话的详细状态快照，包括上下文压力、任务列表等。

**请求：**

```
GET /agentscope/sessions/sess-abc123/state HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应（200 OK）：**

```json
{
  "sessionId": "sess-abc123",
  "summary": "用户咨询了订单退款流程，已完成退款申请提交...",
  "currentIter": 3,
  "contextPressure": {
    "usedTokens": 18000,
    "maxTokens": 32000,
    "ratio": 0.5625
  },
  "tasks": [
    {"id": "task-1", "subject": "查询订单信息", "state": "completed"},
    {"id": "task-2", "subject": "提交退款申请", "state": "in_progress"}
  ]
}
```

**`SessionState` 字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | string | 会话 ID |
| `summary` | string | 会话摘要 |
| `currentIter` | int32 | 当前迭代次数 |
| `contextPressure` | object | 上下文压力详情 |
| `contextPressure.usedTokens` | int64 | 已使用 token 数 |
| `contextPressure.maxTokens` | int64 | 最大 token 数 |
| `contextPressure.ratio` | float64 | 使用比例 |
| `tasks` | []object | 任务列表 |

**响应（404 Not Found）：** 会话 ID 不存在时返回 404。

---

### Level 3 -- 全功能协调

在 Level 2 基础上增加主动控制指令，使控制面可以对会话下发压缩或终止操作。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/agentscope/sessions/{id}/compress` | POST | 触发会话上下文压缩 |
| `/agentscope/sessions/{id}/terminate` | POST | 终止会话 |

#### `POST /agentscope/sessions/{id}/compress`

触发指定会话的上下文压缩。当 `contextPressure` 超过阈值时，控制面可主动下发该指令。

**请求：**

```
POST /agentscope/sessions/sess-abc123/compress HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应：**

- `200 OK` -- 压缩指令已接收
- `404 Not Found` -- 会话不存在

#### `POST /agentscope/sessions/{id}/terminate`

终止指定会话。

**请求：**

```
POST /agentscope/sessions/sess-abc123/terminate HTTP/1.1
Host: <agent-pod-ip>:8080
```

**响应：**

- `200 OK` -- 终止指令已接收
- `404 Not Found` -- 会话不存在

---

### Capability 门控扩展端点（Level 3+，已实现）

在三级契约之上，数据面可通过 `capabilities` 细粒度声明以下扩展端点（sdk-design.md §4）。控制面未看到对应 capability 时不调用这些端点；数据面未实现时应返回 `501 Not Implemented`，而不是空数据。

| 端点 | 方法 | Capability | 说明 |
|------|------|------------|------|
| `/agentscope/sessions/{id}/context` | GET | `context-query` | Level 4：当前生效 Context 快照 |
| `/agentscope/sessions/{id}/messages` | GET | `message-query` | Level 3：完整消息历史，`?offset=&limit=` 分页 |
| `/agentscope/subagents` | GET | `subagent-inventory` | 当前实例 subagent 清单 |
| `/agentscope/workspaces` | GET | `workspace-inventory` | 当前实例 workspace 清单 |

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
| `AddSession(snap)` | 数据注入 | 添加一个会话到列表 |
| `SetSessionState(id, state)` | 数据注入 | 设置会话详细状态 |
| `CompressCalledFor(id)` | 断言 | 检查 compress 是否被调用 |
| `TerminateCalledFor(id)` | 断言 | 检查 terminate 是否被调用 |
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
  - SessionPoller 跳过该 agent（contractLevel < 2）
  - Agent status 的 SessionPolling condition 为 SessionPollingUnsupported
```

### 场景 2：contractLevel = 2（会话观测）

```
输入：mock 数据面，contractLevel=2，预置 2 个 session
预期：
  - 控制面探测并纳管成功
  - SessionPoller 拉取到 2 个会话，创建对应的 AgentSession CRD
  - AgentSession CRD 的 status 反映会话的 phase、messageCount、tokenUsage 等
  - 如果 mock 移除一个 session，控制面将对应 CRD 标记为 Terminated
  - Agent status.activeSessions 反映活跃会话数
```

### 场景 3：contractLevel = 3（全功能协调）

```
输入：mock 数据面，contractLevel=3，预置 session，contextPressure 较高
预期：
  - 会话观测行为同 Level 2
  - 控制面可成功调用 compress 端点，mock.CompressCalledFor(id) 为 true
  - 控制面可成功调用 terminate 端点，mock.TerminateCalledFor(id) 为 true
```

### 运行一致性测试

```bash
cd control-plane
go test ./test/... -v -run TestContractLevel
```

---

## Capabilities 词汇表

数据面在 `GET /agentscope/info` 的 `capabilities` 字段与 ASDP `ConnectRequest.capabilities` 中声明以下能力词汇。控制面按词汇门控行为：未声明的能力不会被调用或期待。

| 词汇 | 含义 | 对应通道与端点 |
|------|------|----------------|
| `session-reporting` | Level 1：会话摘要快照上报 | ASDP `SessionReport`；HTTP `GET /agentscope/sessions` |
| `event-reporting` | Level 2：事件流摘要上报（默认关闭，SDK `enable_events` 开启） | ASDP `EventReport` |
| `context-reporting` | Level 4：生效 Context 变更主动推送（hash 变更防抖 + compaction 立即推） | ASDP `ContextReport` |
| `context-query` | Level 4 按需查询当前生效 Context | HTTP `GET /agentscope/sessions/{id}/context` |
| `message-query` | Level 3：完整消息历史分页拉取 | HTTP `GET /agentscope/sessions/{id}/messages` |
| `session-command` | 接收 compress / terminate 控制指令 | HTTP `POST .../compress\|terminate`；ASDP `SessionCommand` 下行 |
| `subagent-inventory` | subagent 运行时清单上报与查询 | ASDP `InventoryReport`；HTTP `GET /agentscope/subagents` |
| `workspace-inventory` | workspace 运行时清单上报与查询 | ASDP `InventoryReport`；HTTP `GET /agentscope/workspaces` |

门控语义：

- 控制面在握手 / info 中未看到某 capability 时，**不调用**对应端点、不期待对应上报；依赖该能力的 REST 读路径返回明确的「数据面不支持」错误，而不是空数据。
- 数据面未实现某 capability 对应端点时，应返回 `501 Not Implemented`，而不是 200 空数据。
- 历史词汇（如 `hot-reload`、`context-compression`、`sandbox-request`）与本表正交，数据面可按需附加声明。
