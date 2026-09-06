# Agent Protocol

`agentscope-extensions-agent-protocol` 把 AgentScope 的 [Harness Agent](../../docs/harness/architecture.md) 暴露为 [Agent Protocol](https://agentprotocol.ai/) 标准 HTTP 接口，让外部系统（CI、其他 Agent 平台、自动化任务）可以用统一的方式提交"任务"，无需关心你的 Agent 实现细节。

## 何时使用

- 想让 Agent 像云函数一样被远程调度。
- 已有团队在用 Agent Protocol 客户端，想直接接进去。
- 把 AgentScope Harness Agent 嵌进 Spring Boot 服务，自动暴露 `/tasks` REST 端点。
- 作为 [远程子 agent](../../docs/harness/subagent.md#远程子-agent) 的托管端，供另一个 Harness 父代理通过 HTTP 调用。

## 协议分层

AgentScope 用不同协议覆盖不同信任边界 / 交互面：

| 层级 | 角色 |
| --- | --- |
| **AG-UI** | 面向用户的聊天 UI 事件流（浏览器 ↔ 应用） |
| **Agent Protocol** | 内部远程子 agent / 任务 HTTP API（父 harness ↔ 远程 agent 服务） |
| **A2A** | 外部 agent 间互操作（独立扩展；不属于本次远程子 agent 流式 / HITL 改动） |

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-agent-protocol</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 启用方式

模块以 Spring Boot 自动配置形式提供，所以仅需在 Spring Boot 应用里：

1. 注入一个 `HarnessAgent` Bean（或自定义 `AgentFactory`）。
2. 在 `application.yml` 里启用：

```yaml
agentscope:
  agent-protocol:
    enabled: true
    # 可选 —— 协议控制面 TaskRecord 目录（不是执行 Agent 的 workspace）
    # task-store-path: ${user.dir}/.agentscope/agent-protocol
```

随后会自动注册 `/tasks` 系列 REST 接口。

### 控制面 vs 执行 workspace

`AgentProtocolTaskStore` 通过独立的 `ProtocolTaskRepository` 持久化协议任务元数据（submit / resume /
snapshot 用的 `TaskRecord`）。默认根目录为 `agentscope.agent-protocol.task-store-path`
（`${user.dir}/.agentscope/agent-protocol`），落在合成桶
`agents/_agentscope_protocol/tasks/` 下。

该路径与每个 `HarnessAgent` 自己的 `WorkspaceManager`（MEMORY、sessions、skills）**相互独立**。
多 agent 的 `AgentFactory` 可以为每个 agent 配置不同的 `.workspace(...)`；协议侧按 `task_id`
查找始终走控制面仓库。

也可以自行提供 `ProtocolTaskRepository` Bean 覆盖默认实现。`AgentProtocolTaskStore` 只接受
`ProtocolTaskRepository`，不要传入执行 Agent 的 `WorkspaceManager`。

## 并发执行

Agent 在调用之间是无状态的——单例即可服务多个并发任务。每个任务通过 `RuntimeContext` 携带独立的 `(userId, sessionId)`，状态完全隔离：

```java
@Bean
public HarnessAgent harnessAgent() {
    return HarnessAgent.builder()
            .name("protocol-agent")
            .model("dashscope:qwen-plus")
            .build();
}
```

同一 session 的并发请求会自动串行化；不同 session 完全并行。

## Agent 选择（`AgentFactory`）

由哪个 agent 执行任务，交给 `AgentFactory` bean 决定。不定义时，默认工厂对所有任务返回唯一的 `HarnessAgent` bean。

自定义 bean 即可按 `agent_id`、租户或任意自定义提交上下文字段路由：

```java
@Bean
AgentFactory agentFactory(Map<String, HarnessAgent> agentsByName) {
    return request -> {
        String tenant = request.contextString("tenant");
        log.info("task {} agent_id={} tenant={} resume={}",
                request.taskId(), request.agentId(), tenant, request.resume());
        return agentsByName.getOrDefault(request.agentId(), agentsByName.get("default"));
    };
}
```

`AgentRequest` 字段：

| 字段 | 说明 |
| --- | --- |
| `taskId()` | 任务标识，同时作为 agent 的 session id |
| `agentId()` | 提交时请求的 `agent_id` |
| `input()` | 用户输入；resume 运行时为空 |
| `userId()` / `parentSessionId()` | 解析自 `context.user_id` / `context.parent_session_id` |
| `resume()` | 为 `true` 表示这是等待工具确认后的续跑 |
| `context()` | 原样保留的提交 `context` map（含自定义键）；可用 `contextValue(key)` / `contextString(key)` 取值 |
| `attributes()` | 仅 `context.attributes` 这层 map；可用 `attributeString(key)` 取值 |

工厂每次运行调用一次——提交时一次，之后每次 `/resume` 再调用一次，且拿到的仍是最初的提交上下文，因此 HITL 暂停前后的路由结果保持一致。

任务并发执行时请每次返回独立实例（例如 prototype 作用域 bean）。返回 `null` 会让任务以错误状态结束。

## 上下文属性（`context.attributes`）

调用方的自定义数据放在 `context.attributes` 里，单独嵌一层，不与协议自身的上下文字段混在一起。除了 `AgentFactory` 能读到之外，这些属性还会随 `RuntimeContext` 进入实际执行的 agent。

它们**整体挂在一个带命名空间的键下**——`AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY`（`agentprotocol.context.attributes`）：

```java
Map<String, Object> attributes = ctx.get(AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY);
String tenant = attributes != null ? (String) attributes.get("tenant") : null;
```

之所以命名空间化而不是平铺成顶层键，是因为框架自身会读少量约定键：`agentId` 决定异步工具的唤醒路由，`outboundAddress` 携带网关回推地址。调用方一旦把属性命名成这些词，就会改变 agent 的行为。属性不会写进系统提示词，因此不影响模型看到的内容。

### 把属性提升为独立键

当某个工具期望直接读 `ctx.get("tenant")`，或需要把属性转成类型化对象时，注册 `RuntimeContextCustomizer` bean。`RuntimeContextCustomizer.flatten` 按显式白名单拷贝，并自动跳过框架保留键：

```java
@Bean
RuntimeContextCustomizer promoteTenantKeys() {
    return RuntimeContextCustomizer.flatten("tenant", "ticket_id");
}

@Bean
RuntimeContextCustomizer tenantContext(TenantService tenants) {
    return (request, builder) -> {
        String tenant = request.attributeString("tenant");
        if (tenant != null) {
            builder.put(TenantInfo.class, tenants.load(tenant));
        }
    };
}
```

所有 customizer bean 都会按 `@Order` 应用到每次运行，且在命名空间注入之后执行——后者覆盖前者。手写 customizer 属于可信扩展，可以写任意键，包括保留键。

### 从父 agent 发送属性

父 agent 调用远程子 agent 时有两个来源，二者合并且按调用传入的优先：

```java
// 静态：按子 agent 声明
SubagentDeclaration.builder()
        .name("researcher")
        .description("远程研究员")
        .url("http://remote:8080")
        .remoteContextAttributes(Map.of("region", "cn"))
        .build();

// 动态：按本次调用，挂在父 agent 的 RuntimeContext 上
RuntimeContext.builder()
        .sessionId("sess-1")
        .put(AgentSpawnTool.CTX_REMOTE_CONTEXT_ATTRIBUTES, Map.of("tenant", "acme"))
        .build();
```

值必须可 JSON 序列化。

## 端点

### 提交任务

`POST /tasks`

```json
{
  "task_id": "task_123",
  "agent_id": "researcher",
  "input": "Summarize the latest release notes",
  "context": {
    "user_id": "u-1",
    "parent_session_id": "sess-parent",
    "stream": true,
    "detail": "full",
    "deny_rules": [
      {
        "tool_name": "bash",
        "behavior": "DENY",
        "source": "parent"
      }
    ],
    "attributes": {
      "tenant": "acme",
      "ticket_id": "INC-1001"
    }
  }
}
```

可选 `context` 字段：

| 字段 | 说明 |
| --- | --- |
| `user_id` | 写入远程 agent 的 `RuntimeContext` |
| `parent_session_id` | 父 session 标识（用于关联 / 追踪） |
| `stream` | 调用方是否打算消费 SSE 事件 |
| `detail` | `status`（默认）、`full` 或 `verbose`——见[事件流详细度](#事件流详细度) |
| `deny_rules` | 父侧 DENY 权限规则，供远程侧执行 |
| `attributes` | 调用方自定义键值，用于路由以及本次运行的 `RuntimeContext`；见[上下文属性](#上下文属性contextattributes) |

成功响应：`{ "task_id", "status": "pending" }`。

### 轮询 / 等待 / 取消

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/tasks/{taskId}` | 快照（`status`、结果、错误、待确认项） |
| `GET` | `/tasks/{taskId}/wait?timeout_seconds=7200` | 阻塞直到终态（或 `awaiting_confirm`） |
| `POST` | `/tasks/{taskId}/cancel` | 请求取消 |

远程 agent 等待工具确认时，快照会报告 `status: awaiting_confirm`，但存储的 `TaskStatus` 仍为 `RUNNING`，因此父侧 barrier 会继续等待。

### 事件流（SSE）

`GET /tasks/{taskId}/events`

以 Server-Sent Events 推送 agent 进度。需要 `agentscope.agent-protocol.streaming-enabled=true`（默认开启）。

断线重连 / 续订：

- 查询参数 `from_seq` —— 从该序号之后开始
- 请求头 `Last-Event-ID` —— 未传 `from_seq` 时使用（含义相同）

每条 SSE 消息以事件序号为 `id`、远程事件类型为 `event`、JSON 正文为 `data`。

#### 事件流详细度

提交时的 `context.detail` 决定订阅方能收到多少事件，每一档都是前一档的超集：

| `detail` | 事件流中的类型 |
|----------|----------------|
| `status`（默认） | `RUN_STARTED`、`RUN_FINISHED`、`RUN_ERROR`、`TOOL_CALL_START`、`TOOL_CALL_END`、`TOOL_RESULT`、`REQUIRE_CONFIRM`、`STATUS` |
| `full` | 再加 `TEXT_DELTA`、`THINKING_DELTA` |
| `verbose` | 再加 `AGENT_EVENT`——其余所有 agent 事件，包括块边界、工具入参与工具输出增量、带 token usage 的模型调用、agent 结果、自定义事件 |

只有 `verbose` 能完整复现 agent 自身的事件流。无法识别的取值按 `status` 处理。

除各类型专属字段外，每个事件正文还带两个字段：

| 字段 | 含义 |
|------|------|
| `eventType` | 源 `AgentEventType` 的名字，如 `MODEL_CALL_END`。客户端可据此过滤或打日志，无需解析 `payload` |
| `payload` | 源 `AgentEvent` 的完整序列化。可还原出原始事件，id、时间戳与 metadata 都保留；也是 `AGENT_EVENT` 的唯一表示形式 |

两者都是增量字段：忽略它们的客户端照旧读扁平字段（`text`、`toolCallId`、`status` 等），早于 `AGENT_EVENT` 的客户端则直接跳过这类消息。

### HITL 恢复

`POST /tasks/{taskId}/resume`

```json
{
  "decisions": [
    { "toolCallId": "call-1", "approved": true },
    { "toolCallId": "call-2", "approved": false }
  ]
}
```

`tool_call_id` 也可作为 `toolCallId` 的别名。需要 `agentscope.agent-protocol.hitl-enabled=true`（默认开启）。成功响应：`{ "task_id", "status": "running" }`。

与调用方父 harness 的 HITL 交互见 [远程授权](../../docs/harness/subagent.md#远程授权)。

## 配置项

| `application.yml` 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `agentscope.agent-protocol.enabled` | boolean | `false` | 是否注册 `/tasks` REST 端点 |
| `agentscope.agent-protocol.streaming-enabled` | boolean | `true` | 是否暴露 SSE `GET /tasks/{id}/events` |
| `agentscope.agent-protocol.hitl-enabled` | boolean | `true` | 是否因工具确认暂停任务并接受 `/resume` |
| `agentscope.agent-protocol.sse-replay-buffer-size` | int | `256` | 每个任务的 SSE 回放缓冲区大小 |
| `agentscope.agent-protocol.sse-timeout-ms` | long | `10800000` | SSE 订阅最长持续时间（毫秒） |

示例：

```yaml
agentscope:
  agent-protocol:
    enabled: true
    streaming-enabled: true
    hitl-enabled: true
    sse-replay-buffer-size: 256
    sse-timeout-ms: 10800000
```

> 关闭 `enabled` 时（默认）即使引入依赖也不会暴露任何 REST 接口，可放心打包。

## 与 Workspace 配合

每个 task 都会拿到 `WorkspaceManager` 分配的隔离工作目录；任务结束后，工作区里的产物（文件、日志）会通过 Agent Protocol 的标准接口暴露出来，外部客户端可以直接拉取。
