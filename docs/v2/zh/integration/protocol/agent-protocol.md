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

1. 注入一个 `HarnessAgent` Bean 和一个 `WorkspaceManager` Bean。
2. 在 `application.yml` 里启用：

```yaml
agentscope:
  agent-protocol:
    enabled: true
```

随后会自动注册 `/tasks` 系列 REST 接口。

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
    ]
  }
}
```

可选 `context` 字段：

| 字段 | 说明 |
| --- | --- |
| `user_id` | 写入远程 agent 的 `RuntimeContext` |
| `parent_session_id` | 父 session 标识（用于关联 / 追踪） |
| `stream` | 调用方是否打算消费 SSE 事件 |
| `detail` | `status`（默认）或 `full`——`full` 会在事件流中包含文本 / 思考增量 |
| `deny_rules` | 父侧 DENY 权限规则，供远程侧执行 |

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
