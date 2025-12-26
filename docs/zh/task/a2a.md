# A2A (Agent2Agent)

A2A 是 AgentScope 对 [A2A 协议](https://a2a-protocol.org/latest/specification/) 的支持，包括客户端（调用远程 Agent）和服务端（暴露本地 Agent）两部分。

---

## 客户端：A2aAgent

将远程 A2A 服务作为本地 Agent 使用。

### 快速开始

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

// 创建 A2A Agent
A2aAgent agent = A2aAgent.builder()
    .name("remote-agent")
    .agentCardResolver(new WellKnownAgentCardResolver(
        "http://127.0.0.1:8080",
        "/.well-known/agent-card.json",
        Map.of()))
    .build();

// 调用远程 Agent
Msg response = agent.call(userMsg).block();
```

### 配置选项

| 参数 | 类型 | 描述 |
|-----|------|-----|
| `agentCard` | AgentCard | 直接提供 AgentCard |
| `agentCardResolver` | AgentCardResolver | 通过解析器获取 AgentCard |
| `memory` | Memory | 记忆组件 |
| `hook` / `hooks` | Hook | 钩子函数 |

### AgentCard 获取方式

```java
// 方式 1：直接提供
A2aAgent.builder()
    .agentCard(agentCard)
    .build();

// 方式 2：从 well-known 路径获取
A2aAgent.builder()
    .agentCardResolver(new WellKnownAgentCardResolver(url, path, headers))
    .build();

// 方式 3：从 Nacos 中发现
A2aAgent.builder()
    .agentCardResolver(new NacosAgentCardResolver(nacosClient))
    .build();

// 方式 4：自定义解析器
A2aAgent.builder()
    .agentCardResolver(agentName -> customGetAgentCard(agentName))
    .build();
```

> 更多从 Nacos 中发现 A2A 服务查看 [Nacos 注册中心集成](./nacos.md#从-nacos-中自动发现-a2a-服务).

---

## 服务端：A2A Server

将本地 Agent 暴露为 A2A 服务。

### Spring Boot 方式（推荐）

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
# application.yml
agentscope:
  dashscope:
    api-key: your-api-key
  agent:
    name: my-assistant
  a2a:
    server:
      enabled: true
      card:
        name: My Assistant
        description: 基于 AgentScope 的智能助手
```

```java
@SpringBootApplication
public class A2aServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(A2aServerApplication.class, args);
    }
}
```

### 手动创建方式

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;

// 创建 A2A Server
AgentScopeA2aServer server = AgentScopeA2aServer.builder(
        ReActAgent.builder()
            .name("my-assistant")
            .sysPrompt("你是一个有用的助手"))
    .deploymentProperties(DeploymentProperties.builder()
        .host("localhost")
        .port(8080)
        .build())
    .build();

// 获取传输处理器用于 Web 框架
JsonRpcTransportWrapper transport =
    server.getTransportWrapper("JSON-RPC", JsonRpcTransportWrapper.class);

// Web 服务就绪后调用
server.postEndpointReady();
```

### 配置 AgentCard

```java
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;

ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
    .name("My Assistant")
    .description("智能助手")
    .version("1.0.0")
    .skills(List.of(
        new AgentSkill("text-generation", "文本生成"),
        new AgentSkill("question-answering", "问答")))
    .build();

AgentScopeA2aServer.builder(agentBuilder)
    .agentCard(agentCard)
    .build();
```

### 自动注册 A2A 服务到 A2A 注册中心

- 自动注册到 **Nacos** 注册中心：[Nacos 注册中心集成](./nacos.md#向-nacos-中自动注册-a2a-服务) 。

---

## 中断任务

```java
// 客户端中断
agent.interrupt();

// 带消息的中断
agent.interrupt(Msg.builder()
    .textContent("用户取消了操作")
    .build());
```

---

## 更多资源

- **A2A 协议规范**: https://a2a-protocol.org/latest/specification/
- **Agent 接口**: [Agent.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java)
