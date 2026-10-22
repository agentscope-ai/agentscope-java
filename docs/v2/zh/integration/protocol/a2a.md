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

// 关闭时：停止请求处理器的线程。正在运行的执行会被要求停止（中断），close() 不等待它们结束。
// 使用 Spring Boot Starter 时会自动调用。
server.close();
```

`AgentScopeA2aServer` 本身不监听端口、不暴露 endpoint，只负责构建组件、组装请求处理链；具体监听由你的 Web 框架完成。这样可以无缝接入 Spring Boot、Quarkus、Vert.x 等。

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
