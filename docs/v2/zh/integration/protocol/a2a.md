---
title: A2A
en_link: /v2/en/integration/protocol/a2a
---

`agentscope-extensions-a2a` 基于 A2A Java SDK 实现了 [A2A 协议](https://a2aproject.github.io/A2A/) 1.0 版本，包含两个子模块：

- `agentscope-extensions-a2a-client`：把一个远端 A2A Agent 包装成本地 `Agent`，可以直接 `agent.call(...)`。
- `agentscope-extensions-a2a-server`：把本地 `ReActAgent` 暴露成 A2A Server。

两个模块完全独立，可以单用其一。

不支持 A2A 0.3：客户端无法调用 A2A 0.3 的 Agent；A2A 0.3 客户端的请求不带 `A2A-Version` 请求头，服务端会以版本错误拒绝。服务端暂不支持 `ListTasks`，因为还不认证调用方。

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
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;

AgentCard card = AgentCard.builder()
    .name("remote-translator")
    // 第一个接口是首选接口
    .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://other-service:8080")))
    // ...
    .build();

A2aAgent remote = A2aAgent.builder()
    .name("remote-translator")
    .agentCard(card)
    .build();

Msg result = remote.call(new UserMessage("Translate to English: 你好")).block();
```

### 流式获取回复

`streamEvents` 按事件流式返回一次调用：远端流式返回的文本和思考分别是 `TextBlockDeltaEvent`、`ThinkingBlockDeltaEvent`，最后的 `AgentResultEvent` 带着和 `call()` 相同的结果消息。工具调用和工具结果只在结果消息里。

任务完成时，最终结果优先使用输出 artifacts。没有 artifacts 时，使用最终状态消息；通过完整任务或流式状态更新完成时均遵循此规则。

远端任务停下来等调用方（`TASK_STATE_INPUT_REQUIRED` 或 `TASK_STATE_AUTH_REQUIRED`）时，调用以任务的状态消息结束，通常就是远端提的问题。结果消息的 metadata 里，`MessageConstants.TASK_ID_METADATA_KEY` 是任务 id，`MessageConstants.TASK_STATE_METADATA_KEY` 是状态。远端的任务不会被关闭。

不走流式时（客户端配置或 AgentCard 不支持流式），如果返回的任务仍是 `SUBMITTED` 或 `WORKING`，调用会以 `IllegalStateException` 失败，因为 `A2aAgent` 不轮询任务。

取消一次调用时（比如取消订阅，或上游的 `timeout` 触发），如果已经拿到任务 id，会同时取消远端任务（`tasks/cancel`），并关闭默认 JSON-RPC 传输的事件流；取消之后再到的事件会被丢弃。通过 `clientTransports` 配置的传输使用自己的 HTTP 客户端，它们的事件流会一直开着，直到远端结束任务。

```java
remote.streamEvents(new UserMessage("Translate to English: 你好"))
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
    .blockLast();
```

### 会话

每个会话由 `RuntimeContext` 的用户 id 和会话 id 确定，各自在 `AgentState` 里保存历史，同一会话的调用按顺序执行；不带会话 id 的调用共用以 Agent 名字命名的默认会话。`interrupt(RuntimeContext)` 取消该会话正在进行的远端任务。

```java
RuntimeContext context = RuntimeContext.builder().userId("alice").sessionId("chat-1").build();
Msg reply = remote.call(List.of(new UserMessage("Translate to English: 你好")), context).block();
List<Msg> history = remote.getAgentState(context).getContext();
```

身份会随每次调用传到对端：会话 id 走 A2A 原生 `contextId` 字段，用户 id 走信封级 metadata 键 `_agentscope_user_id`——AgentScope 服务端会把两者还原进自己的 `RuntimeContext`。非 AgentScope 对端会忽略这个额外的 metadata 键；匿名调用（没有用户 id）则不会发送该键。

### Middleware

`A2aAgent` 在两个位置执行 `MiddlewareBase`：`onAgent` 包住整次调用，`onReasoning` 包住和远端 Agent 的交互。`onReasoning` 的输入消息就是发给远端的消息，事件是远端流式返回的文本和思考。其他扩展点不适用于远端 Agent。

```java
A2aAgent remote = A2aAgent.builder()
    .name("remote")
    .agentCardResolver(resolver)
    .middleware(new MiddlewareBase() {
        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent, RuntimeContext ctx, AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            long start = System.currentTimeMillis();
            return next.apply(input).doFinally(signal ->
                    log.info("remote call took {} ms", System.currentTimeMillis() - start));
        }
    })
    .build();
```

### 用 well-known 自动获取 AgentCard

```java
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

WellKnownAgentCardResolver resolver = new WellKnownAgentCardResolver(
    "http://127.0.0.1:8080",
    "/.well-known/agent-card.json",
    Map.of()
);

A2aAgent remote = A2aAgent.builder()
    .name("remote")
    .agentCardResolver(resolver)
    .build();
```

`A2aAgent` 是 `AgentBase` 的子类，可以像普通 Agent 一样组合到 Pipeline、MsgHub、Subagent 中。

## 服务端：把 ReActAgent 暴露成 A2A Server

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-a2a-server</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 构建 server

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportProperties;

ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .name("backend-agent")
    .model(model);

AgentScopeA2aServer server = AgentScopeA2aServer.builder()
    .agentBuilder(agentBuilder)
    .transportProperties(new JsonRpcTransportProperties())
    // .agentCard(customCard)
    // .agentRegistry(myRegistry)
    .build();

// 在你的 Web 框架里把请求委托给 transportWrapper
TransportWrapper wrapper = server.getTransportWrapper("JSONRPC");
// ... Spring/Quarkus controller 调用 wrapper.handleRequest(body, headers, metadata)：
// 返回 JSON 字符串；流式请求返回 Flux<String>，每个元素作为一条 SSE 事件写出。
// AgentCard 端点直接返回 server.getAgentCardJson()。

server.postEndpointReady();   // Web 服务监听端口后再调用，触发注册等动作

// 关闭时：停止请求处理器的线程和 server 自己创建的线程池。正在运行的执行会被要求停止（中断），
// close() 不等待它们结束；通过 executor(...) 传入的执行器由你自己管理。使用 Spring Boot Starter 时会自动调用。
server.close();
```

`AgentScopeA2aServer` 本身不监听端口、不暴露 endpoint，只负责构建组件、组装请求处理链；具体监听由你的 Web 框架完成。这样可以无缝接入 Spring Boot、Quarkus、Vert.x 等。

对于流式请求及 `returnImmediately=true` 的请求，自定义 `AgentRunner` 可以只返回一个 `AgentResultEvent`。如果此前没有发送任何 artifact，服务端会在事件流正常完成时，将非空的最终消息作为一个完整 artifact 发出；如果已经发送过 artifact，则不会再次追加最终快照。设置 `completeWithMessage=true` 时，这种只有最终结果的消息也会包含在任务完成状态中。

### 请求的身份与会话标识

服务端使用 SDK 解析后的 A2A `contextId` 填充传给 `AgentRunner` 的 `AgentRequestOptions.sessionId`。客户端可以直接使用标准协议字段，无需额外添加自定义 metadata。客户端未传 `contextId` 时，SDK 会自动生成一个。

为兼容已有客户端，非 null 且非空白的 `message.metadata["sessionId"]` 优先于该映射，即使它与 `contextId` 不同。这样，SDK 自动生成新的 context ID 时，已有会话键仍会保留。metadata 值为 null、空串或仅含空白时，回退到 `contextId`。协议消息本身的 `contextId` 不会被修改。

`AgentRequestOptions.userId` 从信封级 metadata 键 `_agentscope_user_id` 读取，AgentScope 客户端会把自己的 `RuntimeContext` 用户 id 写入该键。扁平的 `message.metadata["userId"]` 键作为已废弃的兜底仍然有效，供旧客户端使用；两者都没有时用户 id 保持为空。

内置的 `BaseReActAgentRunner` 实现会把这两个 id 传入 Agent 的 `RuntimeContext`，会话状态、会话日志和调用级中间件因此能看到调用者的 `(userId, sessionId)` 槽位。自定义 `AgentRunner` 通过 `AgentRequestOptions` 拿到 id，自行决定如何使用。

信封用户 id 本质是委托声明——它只是调用方声称代表谁，任何客户端都可以设置。它会作为按用户区分的 Agent 状态、会话日志和工具可见身份的键，因此没有 resolver 时应将其视为不可信：调用方声称了别人的 id，就会落入那个用户的 `(userId, sessionId)` 槽位。当传输层已经验证了调用方身份（mTLS、OAuth）时，给服务端配置 resolver，让已验证身份优先——验证失败时应直接抛出异常以拒绝该调用：

```java
AgentScopeA2aServer server = AgentScopeA2aServer.builder()
    .agentBuilder(agentBuilder)
    .contextResolver(context -> {
        String verified = verifiedUserIdOf(context.getCallContext()); // mTLS 证书 / OAuth token
        return verified == null ? null
            : RuntimeContext.builder().userId(verified).build();
    })
    .build();
```

优先级：resolver（传输层已验证）&gt; 信封声明的用户 id &gt; 无。resolver 返回 `null` 或不带用户 id 的上下文时，维持信封声明；两者不一致时服务端记录警告并采用已验证身份。

这项映射只填充 Runner 的请求参数，不会自动启用会话持久化。自定义 Runner 可以使用这些标识配置运行上下文或状态存储。

### 工具确认（`input-required`）

Agent 停下来等用户确认工具调用时（见[用户确认](/v2/zh/docs/building-blocks/agent#用户确认)），任务进入 `TASK_STATE_INPUT_REQUIRED`，不再被标记为完成。状态消息里每个待确认的工具调用是一个 data part：data 是工具入参，part 的 metadata 带 `_agentscope_tool_call_id` 和 `_agentscope_tool_name`。阻塞请求直接返回这个任务；流式请求发出状态更新，由于任务尚未结束，流会保持打开，直到客户端自己断开。

继续执行时，用同一个 `taskId` 和 `contextId` 再发一条消息，把确认结果放在 AgentScope 消息 metadata 的 `agentscope_confirm_results` 下：

```java
Message confirmation = Message.builder()
    .role(Message.Role.ROLE_USER)
    .taskId(pausedTaskId)
    .contextId(contextId)
    .parts(new TextPart("approved", Map.of("_agentscope_msg_id", "m1")))
    .metadata(Map.of("m1", Map.of("agentscope_confirm_results", List.of(
        Map.of(
            "confirmed", true,   // false 表示拒绝
            "toolCall", Map.of(
                "type", "tool_use",
                "id", toolCallId,
                "name", toolName,
                "input", toolInput))))))
    .build();
```

服务端每个请求都新建一个 Agent，暂停的那次运行靠 Agent 的状态存储按 `(userId, sessionId)` 恢复。需要在 agent builder 上设置 `stateStore(...)`（见 [AgentStateStore](/v2/zh/docs/building-blocks/agent#配置状态持久化agentstatestore)）；没有配置时，内置 Runner 会在运行 Agent 之前让任务失败。自定义 `AgentRunner` 需要自己恢复暂停的运行。

不带确认结果的后续消息，或者确认的不是待确认工具调用的消息，都不会运行 Agent：任务保持 `input-required`，并重新发出待确认的请求。续接运行时出现的其他错误会让任务失败。

请在暂停的那个任务上发送确认。在同一 context 里以新任务发送确认也能续接运行，但原来暂停的任务会一直保持 `input-required`。取消暂停的任务不会清除状态存储里暂停的运行。因外部执行工具而停下的运行，目前还不会报告为 `input-required`。

### 可选组件

- `TaskStore` / `QueueManager`：任务和事件队列存储，默认是内存实现，生产可换成持久化版本。
- `PushNotificationConfigStore` / `PushNotificationSender`：推送通知。目前还不支持推送：AgentCard 声明不支持，消息里附带的推送配置会被忽略。
- `AgentRegistry`：把 `AgentCard` 注册到外部注册中心（如 Nacos，见 [Nacos](/v2/zh/integration/infrastructure/nacos)）。

## Spring Boot Starter

如果你使用 Spring Boot，建议直接引入 `agentscope-spring-boot-starter-a2a-server`，自动装配上述 server 和控制器，详见[快速开始](/v2/zh/docs/quickstart)。

AgentCard 的内容可以在 `agentscope.a2a.server.card` 下配置，字段名与 A2A AgentCard 一致，例如声明认证方式：

```yaml
agentscope:
  a2a:
    server:
      card:
        provider:
          organization: My Company
          url: https://example.com
        security-schemes:
          bearerAuth:
            type: http          # 可选 apiKey、http、oauth2、openIdConnect、mutualTLS
            scheme: bearer
            bearer-format: JWT
        security:
          - bearerAuth: []
```

OAuth 2.0 方案支持 `authorization-code`（可设 `pkce-required`）、`client-credentials` 和 `device-code` 三种流程。A2A 1.0 没有 `implicit` 和 `password` 流程，配置了会启动失败。

Map 的键含特殊字符时（例如 OAuth scope 名 `read:user` 或 URL），请用引号加方括号写成 `"[read:user]": 读取用户信息`。不加方括号时，`:`、`/` 等字符会被去掉；`security-schemes` 的名称如果含 `.`，会被当作嵌套路径，该方案不会生效。

Starter 返回的 JSON 由 A2A SDK 生成，不受应用自身 Jackson 配置（如 `spring.jackson.property-naming-strategy`）影响。
