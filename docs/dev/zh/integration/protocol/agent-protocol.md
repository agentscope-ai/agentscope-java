---
title: Agent Protocol
en_link: /dev/en/integration/protocol/agent-protocol
---

`agentscope-extensions-agent-protocol` 把 AgentScope 的 [Harness Agent](/dev/zh/docs/harness/architecture) 暴露为 [Agent Protocol](https://agentprotocol.ai/) 标准 HTTP 接口，让外部系统（CI、其他 Agent 平台、自动化任务）可以用统一的方式提交"任务"，无需关心你的 Agent 实现细节。

## 何时使用

- 想让 Agent 像云函数一样被远程调度。
- 已有团队在用 Agent Protocol 客户端，想直接接进去。
- 把 AgentScope Harness Agent 嵌进 Spring Boot 服务，自动暴露 `/tasks` REST 端点。
- 作为 [远程子 agent](/dev/zh/docs/harness/subagent#远程子-agent) 的托管端，供另一个 Harness 父代理通过 HTTP 调用。

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

此适配器默认由 Spring 持有 `HarnessAgent` Bean，生命周期覆盖协议任务执行和恢复，在应用退出时关闭。每个任务通过 `RuntimeContext` 传入用户和会话身份。普通 Web 请求采用共享 Builder、每请求创建实例的方式，见[实例生命周期](/dev/zh/docs/building-blocks/agent#实例生命周期)；不要在协议任务刚提交时关闭适配器持有的 Agent：

```java
@Bean
public HarnessAgent harnessAgent() {
    return HarnessAgent.builder()
            .name("protocol-agent")
            .model("dashscope:qwen-plus")
            .build();
}
```

同一 session 的并发请求会自动串行化；不同 session 完全并行。若提交的目标会话已有运行中的轮次，
服务端返回 HTTP 409，响应体带上被占用的 `session_id`，调用方应等待那一轮结束而不是另起一个写入者。

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
| `taskId()` | 本轮的任务标识；对话本身是 `sessionId()` |
| `agentId()` | 提交时请求的 `agent_id` |
| `input()` | 用户输入；resume 运行时为空 |
| `userId()` / `parentSessionId()` | 解析自 `context.user_id` / `context.parent_session_id` |
| `sessionId()` | 本次运行所在的会话：传了 `context.session_id` 就用它，否则回落到 `taskId()` |
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
| `parent_agent_id` | 发起方 agent 的 id——取调用方的 journal 命名空间（`HarnessAgent` 下为 `agentId ?? name`），因此按 name 配置的 agent 上报的就是该 name；开启 `agentscope.agent-protocol.use-caller-parent-identity` 时写入任务记录 |
| `session_id` | 本次运行所在的会话；后续每一轮复用同一个值——见[继续既有会话](#继续既有会话) |
| `stream` | 调用方是否打算消费 SSE 事件 |
| `detail` | `status`（默认）、`full` 或 `verbose`——见[事件流详细度](#事件流详细度) |
| `deny_rules` | 父侧 DENY 权限规则，供远程侧执行 |
| `attributes` | 调用方自定义键值，用于路由以及本次运行的 `RuntimeContext`；见[上下文属性](#上下文属性contextattributes) |

成功响应：`{ "task_id", "status": "pending" }`。

### 继续既有会话

当调用方传入 `context.session_id` 时，本次提交就在该会话中运行，因此调用方可以在"独立任务"与
"同一会话的下一轮"之间选择：

| 目标 | 提交方式 | agent session |
| --- | --- | --- |
| 派发一个无关任务 | 新的 `task_id`，不带 `session_id` | 新 session，以新 `task_id` 命名 |
| 继续一段会话 | 新的 `task_id` + 同一个 `"session_id": "sub-7f3a"` | `sub-7f3a` |

```json
{
  "task_id": "task_456",
  "agent_id": "researcher",
  "input": "现在把安全章节展开",
  "context": {
    "session_id": "sub-7f3a"
  }
}
```

新任务仍拥有独立的记录、结果与事件流——`/tasks/task_456` 只报告本轮回复——而远程 agent 会加载已
存储的对话，并把 `input` 当作下一轮用户消息。会话由调用方命名、不再由任务推导，所以续接时无需记住
上一轮的 task id。

四条规则：

- **首次使用即登记归属。** 会话第一次被使用时记录 `(user_id, agent_id)`，此后每次校验；用不同的
  `user_id` 或 `agent_id` 指名已存在的会话返回 HTTP 400，而不是在别处静默新开空对话。因此
  `user_id` 一经登记即不可变。这一对值就是唯一的归属校验；`created_by`（提交方 agent id）仅用于排查，
  不是安全边界。多租户部署下，`user_id` 必须由接入层的可信认证上下文提供——登记表信任收到的值。
- **一个会话同时只有一个运行中的轮次。** 会话已有运行时返回 HTTP 409，响应体带上 `session_id`。
  守卫按会话生效，因为多个 task id 可以共享同一会话。
- **task id 仍是每轮一个。** 复用 `task_id` 就复用了它的记录，因此每次提交的 id 必须唯一。
- **可达性。** 会话经配置的 state store 落盘，因此只有该存储与会话登记表都共享时，续接请求才能由
  另一个实例处理。

不带 `session_id` 时会话就是本次提交的 `task_id`，每个提交彼此隔离。

harness 侧，`agent_spawn` 只生成一次子会话并写入子 agent 条目，因此用该子 agent 的 `agent_key` 或
`label` 调用 `agent_send` 时会复用同一个 `session_id`，本地与远程一致。`agent_send` 不提供按
`task_id` 寻址的入口：子 agent 的创建只发生在 `agent_spawn`。

### 轮询 / 等待 / 取消

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/tasks/{taskId}` | 快照（`task_id`、`session_id`、`status`、结果、错误、待确认项 `pending_confirms`） |
| `GET` | `/tasks/{taskId}/wait?timeout_seconds=7200` | 阻塞直到终态（或 `awaiting_confirm`） |
| `POST` | `/tasks/{taskId}/cancel` | 请求取消 |

远程 agent 等待工具确认时，快照会报告 `status: awaiting_confirm`，并带上 `confirm_expires_at`（确认截止时间）；存储的
`TaskStatus` 仍为 `RUNNING`，因此父侧 barrier 会继续等待。

**暂停期间该会话被这一轮占用。** 确认决策是针对暂停时的状态产生的，若允许新轮次先推进会话，决策就会作用在已经变化
的状态上。因此指名同一 `session_id` 的新提交返回 `409`，响应体为：

```json
{
  "error": "session conv-1 has a turn awaiting confirmation (task t-1); resume or cancel it before submitting again",
  "status": "awaiting_confirm",
  "session_id": "conv-1",
  "pending_task_id": "t-1",
  "confirm_expires_at": "2026-11-20T18:00:00Z"
}
```

处理方式是 `/resume` 或 `/cancel` 那个待确认任务；两条路径都会释放会话。其他会话不受影响。

`409` 响应体里的 `status` 区分两类冲突，两者需要的动作不同：

| `status` | 含义 | 字段 | 调用方应做 |
| --- | --- | --- | --- |
| `awaiting_confirm` | 上一轮暂停等待工具确认，不会自行结束 | `session_id`、`pending_task_id`、`confirm_expires_at` | 用确认决策 `/resume` 该任务，或 `/cancel` 它；用 `task_output` 等待无效 |
| `session_busy` | 上一轮仍在运行 | `session_id` | 用 `task_output` / `wait_async_results` 等它结束，再重新提交 |

不带 `status` 的 `409`（例如旧版服务端，或通用任务冲突）按会话忙处理。harness 侧 `agent_send` 分别报告为 `status: awaiting_confirm` 与 `status: still_running`；两者都不会在结果文本里回显子会话。

暂停是「先写记录、再释放租约」，因此新提交在任意时刻都只有一个答案：租约还在时返回 `session_busy`，租约释放后返回
`awaiting_confirm`。相应地，`/resume` 若在记录已可见、上一轮还在收尾的瞬间到达，会做有界等待（上限由
`resume-lease-wait-ms` 配置，默认 2 秒）而不是直接报错。

**确认有上限。** 超过 `confirm-timeout-ms`（默认 1 天，可配任意时长——框架不设上限，只把小于 1 秒的值抬到 1 秒）仍未恢复的任务会被结算为 `FAILED`，错误为
`confirm_timeout: no /resume within <n> ms`，同时关闭事件流并释放会话。结算发生在读取快照/等待或下一次提交时，
不需要后台任务。同一套读侧修复也会结算「实例宕机后仍为 `running` / `pending` 且无活租约」的任务。因此
无人查询的任务会一直保持非终态，直到被读取；若部署要求每个任务自行收敛到终态，需要由外部巡检 GET
未完成的任务列表来触发结算。

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
    { "toolCallId": "call-2", "approved": false, "reason": "not allowed in production" }
  ]
}
```

`tool_call_id` 也可作为 `toolCallId` 的别名。需要 `agentscope.agent-protocol.hitl-enabled=true`（默认开启）。成功响应：`{ "task_id", "status": "running" }`。

与调用方父 harness 的 HITL 交互见 [远程授权](/dev/zh/docs/harness/subagent#远程授权)。

## 配置项

| `application.yml` 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `agentscope.agent-protocol.enabled` | boolean | `false` | 是否注册 `/tasks` REST 端点 |
| `agentscope.agent-protocol.streaming-enabled` | boolean | `true` | 是否暴露 SSE `GET /tasks/{id}/events` |
| `agentscope.agent-protocol.hitl-enabled` | boolean | `true` | 是否因工具确认暂停任务并接受 `/resume` |
| `agentscope.agent-protocol.confirm-timeout-ms` | long | `86400000` | 暂停等待确认的时长，默认 1 天；可配任意值（无上限，仅下限 1 秒）；超时后该轮结算为失败并释放会话 |
| `agentscope.agent-protocol.sse-replay-buffer-size` | int | `256` | 每个任务的 SSE 回放缓冲区大小 |
| `agentscope.agent-protocol.sse-timeout-ms` | long | `10800000` | SSE 订阅最长持续时间（毫秒） |
| `agentscope.agent-protocol.task-store-path` | String | `${user.dir}/.agentscope/agent-protocol` | 任务记录的控制面目录，与任何执行 workspace 无关 |
| `agentscope.agent-protocol.run-lease-ttl-ms` | long | `60000` | 运行租约的 TTL；租约过期且无人接管的记录会被收敛为失败 |
| `agentscope.agent-protocol.run-heartbeat-interval-ms` | long | `20000` | 租约续期周期，最多被钳制到 TTL 的一半 |
| `agentscope.agent-protocol.cancel-check-interval-ms` | long | `500` | 运行中任务检查取消意图的间隔；共享存储部署下应调大（每个运行中任务每个间隔一次读） |
| `agentscope.agent-protocol.resume-lease-wait-ms` | long | `2000` | `/resume` 等待暂停轮次交还租约的时长，钳制到 100–30000 ms；共享注册表导致交还较慢时应调大 |
| `agentscope.agent-protocol.use-caller-parent-identity` | boolean | `false` | 记录调用方的 `parent_agent_id` / `parent_session_id`，而非控制面命名空间 |

示例：

```yaml
agentscope:
  agent-protocol:
    enabled: true
    streaming-enabled: true
    hitl-enabled: true
    sse-replay-buffer-size: 256
    sse-timeout-ms: 10800000
    run-lease-ttl-ms: 60000
    run-heartbeat-interval-ms: 20000
    cancel-check-interval-ms: 500
    resume-lease-wait-ms: 2000
    confirm-timeout-ms: 86400000
    use-caller-parent-identity: false
```

> 关闭 `enabled` 时（默认）即使引入依赖也不会暴露任何 REST 接口，可放心打包。

## 分布式部署

提交、`/resume`、`/cancel` 以及 `/wait` 轮询可能落到不同实例。以下三个协作者默认是进程内实现，跨实例
部署时必须替换为共享实现：

| 协作者 | 为什么必须共享 |
| --- | --- |
| `ProtocolTaskRunRegistry` | 承载运行租约（提交幂等、owner 丢失收敛）与会话的单写入者守卫 |
| `ProtocolTaskContextStore` | `/resume` 会重新读取的提交元数据，而该请求可能由另一实例处理 |
| `ProtocolSessionRegistry` | 每次指名 `session_id` 的提交都要校验的会话归属 |

共享的 `ProtocolTaskRepository` 与 `AgentProtocolEventBus` 负责记录与事件的可见性。

漏配不会导致启动失败：单实例行为被静默保留，症状是在加入第二个实例后出现重复运行，或会话归属校验
失效。

### 用一份共享存储同时接入

已有支持原子 compare-and-set 的 `BaseStore` 实现（Redis、JDBC、MySQL、PostgreSQL、MongoDB）时，
声明一个 bean 即可把上表三个协作者一起切过去：

```java
@Bean
ProtocolStores protocolStores(BaseStore protocolStore) {
    return ProtocolStores.on(protocolStore);
}
```

租约里会记录持有者 `ownerId`，格式为 `<host>:<pid>:<随机>`，由实现自身派生，不需要配置：实例身份不是部署
决策，且每个实例必须不同。它只用于排查——归属判定依赖每次获取唯一的 `leaseId`——但从共享存储里读到一条租约
时，能直接看出是哪台机器持有。

取消是"写意图 + 轮询"而不是广播：任意实例收到 `/cancel` 后写一条意图，持有者按
`cancel-check-interval-ms` 读取。接共享存储后每个运行中任务每个间隔会产生一次读，默认 500ms 相当于每个运行中
任务 2 QPS；实例多、并发高时应调大该值（例如 2–5 秒）。

各协作者在 `agent-protocol` 下使用独立 namespace，互不干扰：

| 数据 | namespace |
| --- | --- |
| 运行租约 | `agent-protocol/task-run-registry-leases` |
| 取消意图 | `agent-protocol/task-run-registry-cancels` |
| 提交上下文 | `agent-protocol/task-context-store` |
| 会话归属 | `agent-protocol/session-registry` |

三个前提：

- **实例时钟保持同步。** 租约里存的是绝对 `expires_at`（epoch 毫秒），由获取租约的实例用自己的
  `Clock.systemUTC()` 生成，之后每个实例都用<b>自己的</b>时钟去比较。共享存储只是保存了这个数值，
  并不参与过期判定，`BaseStore` 也没有 TTL 或服务端时间原语。因此时钟偏快的实例会把时钟偏慢实例
  仍在续约的租约判为过期并接管。请让时钟偏差远小于租约 TTL 的一半（默认
  `run-lease-ttl-ms=60000`、`run-heartbeat-interval-ms=20000`，即 10 秒）；常规漂移用 NTP 即可覆盖。
- **传协议控制面自己的 store 实例。** 按 agent 分片的实现在唯一键里带上 agent 标识，用执行 agent 的
  实例会把控制面数据记到它名下；请用专用标识（如 `_agentscope_protocol`）构造一个。
- **store 必须支持原子 compare-and-set。** `ProtocolStores.on(store)` 会检查
  `BaseStore.supportsAtomicJournalStorage()`，返回 `false` 时直接抛 `IllegalStateException` 快速失败。
  对象存储实现（OSS、COS）的 `putIfVersion` 是「先读后写」序列，无法为租约获取加栅栏，接入后会让两个
  实例同时持有同一任务；请使用 Redis、JDBC、MySQL、PostgreSQL 或 MongoDB。单实例部署无需声明该 bean：
  不声明时自动装配提供进程内的内存协作者。

声明了具体协作者 bean（`ProtocolTaskRunRegistry` 等）时，该 bean 优先于 `ProtocolStores`。

## 与 Workspace 配合

每个 task 都会拿到 `WorkspaceManager` 分配的隔离工作目录；任务结束后，工作区里的产物（文件、日志）会通过 Agent Protocol 的标准接口暴露出来，外部客户端可以直接拉取。
