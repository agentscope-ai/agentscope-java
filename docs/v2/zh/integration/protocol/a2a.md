# A2A（Agent-to-Agent）

AgentScope 通过 `org.a2aproject.sdk:*:1.0.0.Final` Java SDK 集成 A2A 1.0。服务端把细粒度 AgentScope `AgentEvent` 流映射为 A2A artifact 和任务状态更新；客户端则把远端 A2A Agent 暴露为普通 AgentScope `Agent`。

## 模块

| 用途 | Artifact |
| --- | --- |
| 远端 Agent 客户端 | `io.agentscope:agentscope-extensions-a2a-client` |
| 与 Web 框架无关的服务端 | `io.agentscope:agentscope-extensions-a2a-server` |
| Spring Boot 客户端/服务端集成 | `io.agentscope:agentscope-a2a-spring-boot-starter` |
| 可选的 Redis durable HITL 控制面 | `io.agentscope:agentscope-extensions-a2a-hitl-redis` |

客户端和服务端模块相互独立，可以只使用其中一个。Redis 模块是可选项，仅当选择 Redis 作为 durable 人在回路（HITL）控制面时才需要。

## 客户端：调用远端 A2A Agent

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-client</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 直接传入 AgentCard

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import org.a2aproject.sdk.spec.AgentCard;

AgentCard card = AgentCard.builder()
        .name("remote-translator")
        .url("http://other-service:8080")
        // Configure the remaining required AgentCard fields.
        .build();

A2aAgent remote = A2aAgent.builder()
        .name("remote-translator")
        .agentCard(card)
        .build();

Msg result = remote.call(Msg.builder()
        .textContent("Translate to English: 你好")
        .build()).block();
```

### 通过 well-known URL 发现 AgentCard

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import java.util.Map;

WellKnownAgentCardResolver resolver = WellKnownAgentCardResolver.builder()
        .baseUrl("http://127.0.0.1:8080")
        .relativeCardPath("/.well-known/agent-card.json")
        .authHeaders(Map.of())
        .build();

A2aAgent remote = A2aAgent.builder()
        .name("remote")
        .agentCardResolver(resolver)
        .build();
```

`A2aAgent` 是 `AgentBase`，可以组合到 Pipeline、MsgHub、Subagent 等普通 AgentScope 流程中。

### 请求上下文、metadata 和 header

远端 Agent 持久化状态时，请使用稳定的 `RuntimeContext.userId` 和 `RuntimeContext.sessionId`。在 HITL 流程中，后续恢复也依靠这两个值定位 Agent 状态。

```java
import io.agentscope.core.a2a.agent.A2aRequestOptions;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;

RuntimeContext runtimeContext = RuntimeContext.builder()
        .userId("user-42")
        .sessionId("session-42")
        .build();

A2aRequestOptions options = A2aRequestOptions.builder()
        .runtimeContext(runtimeContext)
        .headers(Map.of("Authorization", "Bearer ..."))
        .metadata(Map.of("requestSource", "example"))
        .build();

Msg result = remote.call(
        List.of(Msg.builder().textContent("Hello").build()),
        options).block();
```

这里的 header 是传输层 HTTP header，并不提供任务内认证恢复契约，参见[兼容性与限制](#兼容性与限制)。

## 服务端：把 Agent 暴露成 A2A Server

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-server</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 构建与 Web 框架无关的 Server

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.TransportWrapper;

ReActAgent.Builder agentBuilder = ReActAgent.builder()
        .name("backend-agent")
        .model(model);

AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
        .deploymentProperties(8080)
        .build();

TransportWrapper<?, ?> wrapper = server.getTransportWrapper("JSONRPC");
// Forward requests from the chosen web framework to the wrapper.

server.postEndpointReady();
```

已提供 deployment properties 且没有显式配置 transport 时，默认使用 JSON-RPC。可以通过 `withTransport(TransportProperties)` 添加自定义 transport。

`AgentScopeA2aServer` 只组装请求链，本身不会监听网络端口。请把选定的 `TransportWrapper` 接到 Spring Boot、Quarkus、Vert.x 或其他 Web 框架，并在 endpoint 可访问后调用 `postEndpointReady()`。

### 自定义 AgentRunner

请使用 `AgentEvent`，不要使用已废弃的 `Event` 流接口：

```java
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import reactor.core.publisher.Flux;

final class MyRunner implements AgentRunner {
    private final ReActAgent agent;
    private final ConcurrentMap<String, RuntimeContext> running = new ConcurrentHashMap<>();

    MyRunner(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public Flux<AgentEvent> streamEvents(
            List<Msg> requestMessages,
            AgentRequestOptions options) {
        RuntimeContext context = RuntimeContext.builder()
                .sessionId(options.getSessionId())
                .userId(options.getUserId())
                .build();
        if (running.putIfAbsent(options.getTaskId(), context) != null) {
            return Flux.error(new IllegalStateException("duplicate taskId"));
        }
        return agent.streamEvents(requestMessages, context)
                .doFinally(signal -> running.remove(options.getTaskId(), context));
    }

    @Override
    public void stop(String taskId) {
        RuntimeContext context = running.get(taskId);
        if (context != null) {
            // Interrupt with the exact RuntimeContext used by streamEvents.
            agent.interrupt(context);
        }
    }

    @Override
    public String getAgentName() { return agent.getName(); }

    @Override
    public String getAgentDescription() { return agent.getDescription(); }
}
```

支持 durable HITL 的 runner 还必须从 `hitlDurabilityCapability()` 返回 `HitlDurabilityCapability.DURABLE`，并通过 `actualAgentStateStore()` 暴露它实际使用的共享 `AgentStateStore`。

### 可选组件

- `TaskStore` 持久化 A2A Task，默认实现位于内存中。
- `QueueManager` 管理实时事件队列，它不是持久事件重放日志。
- `A2aHitlDurabilityBinding` 是 durable HITL 控制面的组合 SPI。
- `PushNotificationConfigStore` 和 `PushNotificationSender` 提供出站通知。
- `AgentRegistry` 把 `AgentCard` 发布到 [Nacos](../infrastructure/nacos.md) 等注册中心。

## Spring Boot Starter

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

普通 A2A 只需启用服务端并保持 HITL 关闭：

```yaml
agentscope:
  a2a:
    server:
      enabled: true
      hitl:
        enabled: false
```

HITL 默认为 `false`。普通 A2A 不需要 HITL binding、Redis 依赖或特殊的 `AgentStateStore` 接线。其他服务端与 AgentCard 属性见[快速开始](../../docs/quickstart.md)。

## 人在回路暂停与恢复

AgentScope HITL 把暂停前后视为独立的 A2A turn：

```text
call -> input-required + A2aHandoff -> current stream ends
resume or cancel -> new A2A request on the same task/context -> new terminal result
```

原 SSE 连接不会跨越暂停、重启或副本切换继续存活。

### 检测 handoff

```java
import io.agentscope.core.a2a.agent.hitl.A2aHandoff;
import io.agentscope.core.message.Msg;
import java.util.Optional;

Msg paused = remote.call(Msg.builder()
        .textContent("Run the guarded operation")
        .build()).block();

Optional<A2aHandoff> maybeHandoff = A2aHandoff.tryFrom(paused);
if (maybeHandoff.isEmpty()) {
    // The call completed normally, or the peer returned only standard A2A fields.
    return;
}

A2aHandoff handoff = maybeHandoff.orElseThrow();
```

`tryFrom(Msg)` 只识别本地 `A2aAgent` 附加的 typed handoff 增强，不会根据任意 peer metadata 猜测 HITL 能力。`A2aHandoff` 支持 Jackson 序列化，因此可以稍后交给一个全新的 client 恢复。

`resumeToken` 是机密的 bearer capability。`A2aHandoff.toString()` 会隐藏它，但序列化 JSON 仍包含明文 token，不能写入日志。

### 批准或拒绝工具

```java
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;

A2aPendingTool pending = handoff.pendingTools().get(0);

A2aUserConfirmation approval = new A2aUserConfirmation(
        pending.toolCallId(),
        true,
        Map.of("city", "Shanghai"), // null keeps the original input
        List.of());                  // optional PermissionRule values

Msg completed = remote.resume(handoff, List.of(approval)).block();
```

把 `approved` 设为 `false` 即可拒绝工具。`modifiedInput` 只能替换 pending tool 的输入，服务端会保留原始 tool ID 和名称。

下面是 typed response 面向应用持久化或 API 边界的 JSON；它不是原始 A2A wire JSON：

```json
{
  "responseType": "user-confirmation",
  "toolCallId": "call-1",
  "approved": true,
  "modifiedInput": {
    "city": "Shanghai"
  },
  "permissionRules": []
}
```

### 提供外部执行的工具结果

```java
import io.agentscope.core.a2a.agent.hitl.A2aExternalToolResponse;
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.List;
import java.util.Map;

A2aPendingTool pending = handoff.pendingTools().get(0);

A2aExternalToolResponse externalResult = new A2aExternalToolResponse(
        pending.toolCallId(),
        ToolResultState.SUCCESS,
        List.of(TextBlock.builder().text("external result").build()),
        Map.of("provider", "example"));

Msg completed = remote.resume(handoff, List.of(externalResult)).block();
```

文本、数据和错误结果都可以使用普通 AgentScope `ContentBlock` 值和 `ToolResultState` 表达。external-result metadata 中的敏感 HITL key 会被移除。

```json
{
  "responseType": "external-tool-response",
  "toolCallId": "call-1",
  "state": "success",
  "outputBlocks": [
    {
      "type": "text",
      "text": "external result"
    }
  ],
  "metadata": {
    "provider": "example"
  }
}
```

### 持久化和取消 handoff

可序列化的 handoff 形状如下：

```json
{
  "taskId": "task-123",
  "contextId": "session-42",
  "handoffId": "handoff-123",
  "type": "USER_CONFIRM",
  "expiresAt": "2030-01-01T00:00:00Z",
  "pendingTools": [
    {
      "toolCallId": "call-1",
      "toolName": "lookup",
      "originalInput": {"city": "Beijing"},
      "prompt": "Allow this lookup?"
    }
  ],
  "resumeToken": "<secret; do not log>"
}
```

使用显式 request options 取消一个 open handoff（没有不传 options 的重载）：

```java
import io.agentscope.core.a2a.agent.A2aRequestOptions;
import io.agentscope.core.message.Msg;

Msg canceled = remote.cancelHandoff(
        handoff,
        A2aRequestOptions.empty()).block();
```

cancel 只能原子消费 `OPEN` handoff，它和中断一个已经在执行的普通 A2A turn 不是同一操作。

### 全集校验与可安全重试的错误

- 一次 resume 必须对当前每个 pending `toolCallId` 恰好回答一次。
- 缺失、额外、重复或类型错误的 response 会在 Agent 执行前被拒绝。
- task、context、handoff、token 或 response 集合错误，capability 过期，或者 lease 竞争都会在请求 admission 阶段被拒绝。公开 A2A 错误是 `InvalidParamsError`，文案为 `A2A HITL resume request was rejected`。
- claim 前的非过期拒绝会保持 Task 为 `input-required`、handoff 为 `OPEN`，因此原始有效 capability 仍可重试。过期 handoff 会变为 `EXPIRED`，不能再恢复。
- 对终态 Task 发起请求可能返回 `UnsupportedOperationError`。
- 成功 claim 后，setup 或 Agent 执行失败属于真实 Task 失败，handoff 可能停在 `RECOVERY_REQUIRED`，不能安全自动重放。

内部拒绝原因以及 token/digest 材料不会返回给调用方。

## HITL 存储模式

### Local 模式

```yaml
agentscope:
  a2a:
    server:
      hitl:
        enabled: true
        durability: local
        task-ttl: 30d
        handoff-ttl: 7d
        execution-lease-ttl: 1m
```

Local 模式提供进程内 Task 存储、handoff coordinator 和 session lease。它只适用于开发或单进程：不能跨重启保存，也不能在其他副本恢复。

与 Web 框架无关的等价配置为：

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.hitl.HitlServerProperties;
import java.time.Duration;

HitlServerProperties hitl = HitlServerProperties.builder()
        .enabled(true)
        .durability(HitlServerProperties.Durability.LOCAL)
        .taskTtl(Duration.ofDays(30))
        .handoffTtl(Duration.ofDays(7))
        .executionLeaseTtl(Duration.ofMinutes(1))
        .build();

AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
        .deploymentProperties(8080)
        .hitlServerProperties(hitl)
        .build();
```

### Durable 模式与状态归属

```yaml
agentscope:
  a2a:
    server:
      hitl:
        enabled: true
        durability: durable
        coordination-provider: redis
        task-ttl: 30d
        handoff-ttl: 7d
        execution-lease-ttl: 1m
```

Durable HITL 有两个相互独立的状态权威来源：

| 状态 | 归属 |
| --- | --- |
| A2A Task/history、handoff、token digest、admission claim、session lease、TTL | `A2aHitlDurabilityBinding` |
| Agent context、memory、pending tool、reasoning checkpoint | 应用自己的 `AgentStateStore` |

Starter 通过 binding 验证 A2A 控制面。它会拒绝明显的本地 Agent-state store（`InMemoryAgentStateStore` 和 `JsonFileAgentStateStore`），但不会证明数据库或自定义 `AgentStateStore` 能从每个副本访问。

应用必须保证：

- 每个副本使用同一个共享 Agent-state backend；
- `RuntimeContext.userId` 和 `RuntimeContext.sessionId` 在每个副本上都解析到同一份状态；
- 自定义 runner 声明 `DURABLE` 并暴露实际使用的 store；
- 真实部署测试在副本 A 暂停，停止 A，再在副本 B 恢复。

Agent state 不强制使用 Redis。下面的可选 Redis provider 只存储 A2A 控制面；应用共享 Agent state 可以使用其他实现。

### 可选 Redis 控制面 Provider

为清晰起见，显式声明主 Starter、provider 和兼容的 Redisson client。请使用应用或平台
dependency management 管理的 Redisson 版本：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-hitl-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>${redisson.version}</version>
</dependency>
```

Provider 的 Redisson 依赖是 optional，不会传递给应用。应用也可以使用已有的兼容 Redisson
Spring 集成来代替上面的直接依赖。无论哪种方式，应用都必须提供恰好一个
`RedissonClient`。Provider 不选择、不创建也不关闭它，同时绝不检查或替换应用的
`AgentStateStore`。

```java
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;

@Bean
RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer().setAddress("redis://127.0.0.1:6379");
    return Redisson.create(config);
}
```

```yaml
agentscope:
  a2a:
    server:
      hitl:
        enabled: true
        durability: durable
        coordination-provider: redis
        task-ttl: 30d
        handoff-ttl: 7d
        execution-lease-ttl: 1m
        redis:
          namespace: my-service:a2a:hitl:
          claim-recovery-timeout: 2m
          reconciler-interval: 30s
```

Redis 自动配置仅在以下条件同时满足时生效：HITL 已启用、durability 为 `durable`、`coordination-provider` 为 `redis`、容器中恰好存在一个 `RedissonClient`，且应用没有提供自己的 `A2aHitlDurabilityBinding`。请为每个服务和环境设置显式、隔离的 namespace。

Provider 会验证 TaskStore 的 write/read/delete 和 TTL、handoff claim/replay、仅存 digest 的 token、lease owner 以及一致的 Redis namespace。Claim recovery 会把被遗弃的 claimed handoff 转为 `RECOVERY_REQUIRED`，但不会重放外部副作用。

### 自定义 Durable Binding

非 Redis provider 实现组合 SPI，并只暴露一个 A2A component bean。由 provider-owned
backend/factory 创建三个组件，避免它们跨 backend 混搭或成为独立 Spring bean：

```java
import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityVerification;
import io.agentscope.core.a2a.server.hitl.HitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.HitlSessionLease;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MyDurableHitlConfiguration {
    @Bean
    A2aHitlDurabilityBinding durableHitlBinding(MyControlPlaneBackend backend) {
        TaskStore taskStore = backend.createTaskStore();
        HitlResumeCoordinator coordinator = backend.createResumeCoordinator();
        HitlSessionLease lease = backend.createSessionLease();
        return new MyDurableHitlBinding(taskStore, coordinator, lease, backend);
    }
}

interface MyControlPlaneBackend {
    TaskStore createTaskStore();
    HitlResumeCoordinator createResumeCoordinator();
    HitlSessionLease createSessionLease();

    HitlDurabilityVerification verifyControlPlane(
            TaskStore taskStore,
            HitlResumeCoordinator coordinator,
            HitlSessionLease lease);

    default void start() {}
    default void close() {}
}

final class MyDurableHitlBinding implements A2aHitlDurabilityBinding {
    private final TaskStore taskStore;
    private final HitlResumeCoordinator coordinator;
    private final HitlSessionLease lease;
    private final MyControlPlaneBackend backend;

    MyDurableHitlBinding(
            TaskStore taskStore,
            HitlResumeCoordinator coordinator,
            HitlSessionLease lease,
            MyControlPlaneBackend backend) {
        this.taskStore = taskStore;
        this.coordinator = coordinator;
        this.lease = lease;
        this.backend = backend;
    }

    public TaskStore taskStore() { return taskStore; }
    public HitlResumeCoordinator resumeCoordinator() { return coordinator; }
    public HitlSessionLease sessionLease() { return lease; }
    public HitlDurabilityVerification verify() {
        return backend.verifyControlPlane(taskStore, coordinator, lease);
    }
    public void start() { backend.start(); }
    public void close() { backend.close(); }
}
```

`MyControlPlaneBackend` 代表 provider-owned 连接/工厂代码，并不是 AgentScope API。它的
`verifyControlPlane` 方法必须执行真实、同步的校验，验证 TaskStore 的
write/read/delete 和 TTL、coordinator claim/replay、lease ownership 和 expiry、共享
backend/namespace 拓扑以及 backend 可达性，任一检查失败都必须抛出异常。
`HitlDurabilityVerification` record 是完成这些校验后的安全结果，不能替代校验本身。

Durable 模式要求恰好一个组合 binding，并拒绝同时存在的独立 `TaskStore`、
`HitlResumeCoordinator` 或 `HitlSessionLease` bean。只有组合 binding 会注册为 A2A Spring
component。Provider 的契约测试应调用
`A2aHitlDurabilityBindingContract.verify(binding)`；它会执行通用检查，并调用 binding 自己的
provider-specific `verify()` 实现。

## 安全与运维边界

- Resume token 是高熵、一次性的 bearer capability，不是业务认证或鉴权策略。
- 服务端只存储绑定 Task、context、handoff、execution key 和完整 pending-tool 集合的 digest，从不存储明文 token。客户端只在 hook、trace 和 memory 处理后把明文附加到调用方本地的 `A2aHandoff`。
- 绝不能把序列化 `A2aHandoff` JSON 写入日志、普通 `call(...)` 消息、trace 或 Task metadata。它只能放入访问受控的 secret store，并按 `expiresAt` 过期。
- 应用代码不能修改 handoff 坐标或 pending tool。Resume 和 cancel 会校验完整的持久化 binding。
- Admission 只有一个胜者，但未知崩溃点发生在 claim 后时，不能保证外部副作用 exactly-once。
- Durable 控制面不会持久化或重放实时 SSE 流；resume 是新请求。

默认一张 AgentCard 对应一个逻辑 Agent。当前 HITL execution key 使用 runner 名作为 `logicalAgentId`。如果一张 Card 路由到多个 Agent，应用必须保证 logical identity 不碰撞，并且所有副本使用相同的 Agent-state 恢复策略；无法保证时，请拆分为不同 Card。

## 兼容性与限制

- 本集成使用 A2A Java SDK `1.0.0.Final`（`org.a2aproject.sdk.*`），不再使用旧的 `io.a2a.*` / `io.github.a2asdk` 包。
- 与普通 A2A 1.0 peer 的标准 text、artifact、status 互操作为 best-effort。Typed `A2aHandoff` 依赖 AgentScope handoff metadata 契约；无法构建该本地增强时，`tryFrom` 返回 empty。
- Typed resume 仅支持 `USER_CONFIRM` 和 `EXTERNAL_EXECUTION`。
- AgentScope 没有对应的恢复契约，因此不支持任务内 `auth-required` resume。Peer 返回 `TASK_STATE_AUTH_REQUIRED` 时，`A2aAgent` 会异常完成，不会合成 `Msg` 或发布 post hook；传输层 header 仍可通过 `A2aRequestOptions` 设置。
- 一个 `A2aAgent` 实例同时只允许一个已订阅的 active call。并发时请串行调用或使用不同实例。
- Local 模式仅支持单进程。Durable 模式会强验证 A2A 控制面，但 Agent state 的跨副本可达性仍由应用负责。
- Durable 模式提供单次 resume admission，不提供持久事件流或通用的外部副作用 exactly-once 保证。
