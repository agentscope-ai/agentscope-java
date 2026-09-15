---
title: Nacos
---

`agentscope-extensions-nacos` 把 [Nacos](https://nacos.io/) 用作 AgentScope 的统一控制面：注册发现 A2A Agent、动态加载 Prompt、托管 Skill、发现 MCP Server 并做负载均衡。包含四个子模块，按需组合使用。

| 子模块 | 解决的问题 |
| --- | --- |
| `agentscope-extensions-nacos-a2a` | A2A AgentCard 与服务实例的注册/发现 |
| `agentscope-extensions-nacos-mcp` | 从 Nacos MCP Registry 订阅 MCP Server，在多个后端实例间负载均衡 |
| `agentscope-extensions-nacos-prompt` | 把 Prompt 模板放到 Nacos，热更新到运行中的 Agent |
| `agentscope-extensions-nacos-skill` | 从 Nacos AI 模块加载 Skill 包（ZIP） |

## A2A 注册发现

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 服务端：把 AgentCard 注册到 Nacos

```java
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;

Properties props = new Properties();
props.setProperty("serverAddr", "127.0.0.1:8848");
NacosA2aRegistry registry = new NacosA2aRegistry(props);

NacosA2aRegistryProperties props2 = new NacosA2aRegistryProperties();
// props2.setNamespace(...) / setGroup(...) 等
registry.registerAgent(agentCard, props2);
```

注册后，AgentCard 与服务端点会写入 Nacos 的 AI Service，供消费者发现。

### 客户端：通过 Nacos 拿到远端 AgentCard

```java
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;

NacosAgentCardResolver resolver = new NacosAgentCardResolver(props, "translator-agent");
A2aAgent remote = A2aAgent.builder()
    .name("translator")
    .agentCardResolver(resolver)
    .build();
```

## Prompt 配置中心

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-prompt</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 用法

```java
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.prompt.NacosPromptListener;

NacosPromptListener prompts = new NacosPromptListener(aiService);

String tpl = prompts.getPrompt("system-prompt", Map.of(
    "userName", "Alice"
));
```

监听器内部维护本地缓存，Nacos 上 prompt 更新时会自动推送进来，下一次 `getPrompt(...)` 立即拿到新版本，无需重启。

## Skill 仓库

`agentscope-extensions-nacos-skill` 提供一个 `AgentSkillRepository` 实现，把 Nacos AI 模块管理的技能 ZIP 包下载下来解析。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-skill</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
import io.agentscope.core.nacos.skill.NacosSkillRepository;

Properties props = new Properties();
props.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "1.2.0");
// 或 SKILL_LABEL_PATH = "stable"

NacosSkillRepository repo = new NacosSkillRepository(aiService, "default-namespace", props);
AgentSkill skill = repo.getSkill("calculator");
```

版本/标签的解析顺序是：构造时传入的 `Properties` → JVM `-D` 系统属性 → 环境变量。同时设置版本和标签时，**版本优先**，标签不会用于下载。

## MCP 服务发现

`agentscope-extensions-nacos-mcp` 从 Nacos MCP Registry 订阅一个 MCP Server，把它注册的多个后端实例聚合成一个带负载均衡的 MCP 客户端。实例扩缩容由 Nacos 推送，无需重启 Agent。

> 需要 Nacos 3.x 并启用 MCP Registry 能力。

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-mcp</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 手动组装

```java
import io.agentscope.core.nacos.mcp.discovery.NacosMcpDiscoveryClient;
import io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper;

Properties props = new Properties();
props.setProperty("serverAddr", "127.0.0.1:8848");

NacosLoadBalancedMcpClientWrapper client =
    NacosLoadBalancedMcpClientWrapper.builder("weather")
        .serverName("weather-mcp-server")
        .version("1.0.0")             // 省略则订阅默认版本
        .discoveryClient(new NacosMcpDiscoveryClient(props))
        .build();

client.initialize().block();
toolkit.registerMcpClient(client).block();
```

工具调用会分发到该 MCP Server 的某个实例上，具体由 `EndpointSelector` 决定，内置两种：

| 策略 | 适用场景 |
| --- | --- |
| `RoundRobinEndpointSelector` | 默认。调用轮流打到各实例，要求实例无状态 |
| `StickyEndpointSelector` | 尽量固定一个实例，适合有状态的服务 |

### Spring Boot 自动配置

引入 `agentscope-nacos-spring-boot-starter`，当 `agentscope.nacos.mcp.enabled=true` 时自动装配。每个 `connections` 条目对应一个负载均衡 MCP 客户端：

```yaml
agentscope:
  nacos:
    server-addr: 127.0.0.1:8848     # MCP 可以单独指定一套 Nacos 集群
    mcp:
      enabled: true
      load-balance: round-robin     # 默认策略，可被单个连接覆盖
      connections:
        weather:
          service-name: weather-mcp-server
          version: 1.0.0
        amap:
          service-name: amap-mcp-server
          load-balance: sticky
```

装配好的 `NacosMcpClients` 一次性注册进 Agent 的 Toolkit：

```java
@Bean
public HarnessAgent harnessAgent(NacosMcpClients nacosMcpClients) {
    HarnessAgent agent = HarnessAgent.builder()
        .name("Assistant")
        .model("dashscope:qwen-max")
        .build();
    nacosMcpClients.registerTo(agent.getToolkit());   // 单个连接失败不影响其他连接
    return agent;
}
```

`agentscope.nacos.mcp.*` 继承 `agentscope.nacos.*` 的连接配置（`server-addr`、`namespace`、`username`、`password` 等），未显式配置的字段回退到后者。

## 与其他扩展配合

- 结合 [A2A](/v2/zh/integration/protocol/a2a)：`AgentScopeA2aServer.builder().agentRegistry(...)` 可以注入一个把 AgentCard 推到 Nacos 的注册器，启动后自动暴露给整个集群。
- 结合 [Skill 仓库](/v2/zh/integration/skill/index)：可以与 Git/MySQL 的 `AgentSkillRepository` 并存，把同一个 Toolkit 用多个数据源拼起来。
- 与静态 MCP 配置共存：`tools.json` 里的 `mcpServers` 和 Nacos 发现的 MCP Server 是两个独立来源，可以同时注册进同一个 Toolkit。
