---
title: A2A
en_link: /v2/en/integration/protocol/a2a
---

`agentscope-extensions-a2a` 实现了 [A2A 协议](https://a2aproject.github.io/A2A/)，包含两个子模块：

- `agentscope-extensions-a2a-client`：把一个远端 A2A Agent 包装成本地 `Agent`，可以直接 `agent.call(...)`。
- `agentscope-extensions-a2a-server`：把本地 `ReActAgent` 暴露成 A2A Server。

两个模块完全独立，可以单用其一。

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
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.A2aAgent;

AgentCard card = AgentCard.builder()
    .name("remote-translator")
    .url("http://other-service:8080")
    // ...
    .build();

A2aAgent remote = A2aAgent.builder()
    .name("remote-translator")
    .agentCard(card)
    .build();

Msg result = remote.call(new UserMessage("Translate to English: 你好")).block();
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
```

`AgentScopeA2aServer` 本身不监听端口、不暴露 endpoint，只负责构建组件、组装请求处理链；具体监听由你的 Web 框架完成。这样可以无缝接入 Spring Boot、Quarkus、Vert.x 等。

### 可选组件

- `TaskStore` / `QueueManager`：任务和事件队列存储，默认是内存实现，生产可换成持久化版本。
- `PushNotificationConfigStore` / `PushNotificationSender`：推送通知。
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

Map 的键含特殊字符时（例如 OAuth scope 名 `read:user` 或 URL），请用引号加方括号写成 `"[read:user]": 读取用户信息`。不加方括号时，`:`、`/` 等字符会被去掉；`security-schemes` 的名称如果含 `.`，会被当作嵌套路径，该方案不会生效。

Starter 返回的 JSON 由 A2A SDK 生成，不受应用自身 Jackson 配置（如 `spring.jackson.property-naming-strategy`）影响。
