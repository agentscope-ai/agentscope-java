# A2A (Agent2Agent) Agent

## 概述

A2A (Agent2Agent) Agent 是 AgentScope 框架中的一个扩展模块，用于与其他遵循 [A2A 规范](https://a2a-protocol.org/latest/specification/) 的远程 Agent 进行通信。它实现了标准的 [Agent](../../../agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java) 接口，可以通过统一的方式与远程 Agent 交互。

A2A Agent 允许您将远程 Agent 作为本地 Agent 使用，透明地处理底层的网络通信、消息序列化/反序列化等复杂过程。

## 核心概念

### AgentCard

[AgentCard](https://a2a-protocol.org/latest/specification/#441-agentcard) 是 A2A 协议中的核心概念之一，它是远程 Agent 的元数据描述文件，包含以下关键信息：
- Agent 名称
- 描述
- 支持的功能
- 通信协议和地址信息

### AgentCardResolver

`AgentCardResolver` 是 A2A Agent 中用于获取远程 Agent 的 `AgentCard` 的解析器接口。通过实现这个接口，可以灵活地从不同来源获取 AgentCard 信息。

AgentScope 提供了两种内置的解析器实现：

1. `FixedAgentCardResolver` - 直接使用固定的 AgentCard 实例
2. `WellKnownAgentCardResolver` - 从远程地址的 `.well-known/agent-card.json` 路径获取 AgentCard，允许自定义远程地址的路径。

除了内置的解析器实现，您也可以实现自己的解析器，从自定义的逻辑或A2A注册中心中获取`AgentCard`。

## 快速开始

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.a2a.spec.AgentCard;

// 创建 A2A Agent
AgentCardResolver resolver = new WellKnownAgentCardResolver(
        "http://127.0.0.1:8080",     // 获取远程 AgentCard 的服务器的地址
        "/.well-known/agent-card.json",
        Map.of());
A2aAgent a2aAgent = A2aAgent.builder()
        .name("remote-agent")
        .agentCardResolver(resolver)
        .build();

// 发送消息并获取响应
Msg userMsg = Msg.builder()
    .textContent("你好，你能帮我做什么？")
    .build();

Msg response = agent.call(userMsg).block();
System.out.println("Response: " + response.getTextContent());
```

## 配置和使用

### 基本配置

要创建一个 A2A Agent，您需要使用 `A2aAgent.Builder` 来构建实例：

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

// 方式一：直接提供 AgentCard
AgentCard agentCard = generateAgentCard(); // 自行构建 的 AgentCard
A2aAgent a2aAgent = A2aAgent.builder()
    .name("remote-agent")
    .agentCard(agentCard)
    .build();

// 方式二：通过 AgentCardResolver 自动获取 AgentCard
AgentCardResolver resolver = new WellKnownAgentCardResolver(
    "http://127.0.0.1:8080", 
    "/.well-known/agent-card.json", 
    Map.of());
A2aAgent a2aAgent = A2aAgent.builder()
    .name("remote-agent")
    .agentCardResolver(resolver)
    .build();
```

### 构建器参数详解

A2A Agent 的构建器支持以下配置参数：

| 参数                  | 类型                 | 描述                      |
|---------------------|--------------------|-------------------------|
| `name`              | String             | Agent 的名称               |
| `agentCard`         | AgentCard          | 直接提供 AgentCard          |
| `agentCardResolver` | AgentCardResolver  | 通过解析器获取 AgentCard       |
| `memory`            | Memory             | 记忆组件，默认为 InMemoryMemory |
| `checkRunning`      | boolean            | 是否检查运行状态，默认为 true       |
| `hook` / `hooks`    | Hook / List\<Hook> | 添加钩子函数                  |
| `a2aAgentConfig`    | A2aAgentConfig     | A2A 特定配置                |

### A2aAgentConfig 配置

A2aAgentConfig 用于配置底层的传输层和其他客户端设置：

```java
import io.agentscope.core.a2a.agent.A2aAgentConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;

A2aAgentConfig config = A2aAgentConfig.builder()
    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
    .clientConfig(ClientConfig.builder().setStreaming(false).build())
    .build();

A2aAgent agent = A2aAgent.builder()
    .name("remote-agent")
    .agentCard(agentCard)
    .a2aAgentConfig(config)
    .build();
```

## 更多使用示例

### 带记忆和钩子的配置

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.hook.Hook;

A2aAgent agent = A2aAgent.builder()
    .name("remote-assistant")
    .agentCard(agentCard)
    .memory(new InMemoryMemory()) // 使用记忆存储对话历史
    .hook(new LoggingHook()) // 添加自定义日志钩子
    .checkRunning(true) // 检查运行状态，防止并发调用
    .build();
```

### 自定义AgentCardResolver

```java
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.a2a.spec.AgentCard;

AgentCardResolver customAgentCardResolver = new AgentCardResolver() {
    
    @Override
    public AgentCard getAgentCard(String agentName) {
        // 自定义的获取或生成 AgentCard 的逻辑，比如到指定的A2A 注册中心获取名称为`agentName`的 AgentCard.
        return customGetAgentCard(agentName);
    }
};

A2aAgent a2aAgent = A2aAgent.builder()
        .name("remote-agent")
        .agentCardResolver(resolver)
        .build();
```

## 错误处理和中断

### 中断机制

A2A Agent 支持中断正在运行的任务：

```java
// 中断当前任务
agent.interrupt();

// 带消息的中断
agent.interrupt(Msg.builder()
    .textContent("用户取消了操作")
    .build());
```

当中断任务时，A2A Agent会向对应的远端Agent发起一个cancelTask的A2A请求，若远端Agent不支持中断任务，则任务中断可能会失败。

### 错误处理

通过 Hook 机制可以捕获和处理错误：

```java
public class ErrorHandlingHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof ErrorEvent errorEvent) {
            // 处理错误
            System.err.println("发生错误: " + errorEvent.getError().getMessage());
        }
        return Mono.just(event);
    }
}
```
