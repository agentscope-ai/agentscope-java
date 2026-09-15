---
title: Nacos
---

`agentscope-extensions-nacos` uses [Nacos](https://nacos.io/) as AgentScope's unified control plane: register and discover A2A Agents, hot-load prompts, host skills, and discover MCP servers with load balancing. It contains four sub-modules — pick the ones you need.

| Sub-module | Problem it solves |
| --- | --- |
| `agentscope-extensions-nacos-a2a` | A2A AgentCard / instance registry and discovery |
| `agentscope-extensions-nacos-mcp` | Subscribe MCP servers from the Nacos MCP registry and load-balance across their instances |
| `agentscope-extensions-nacos-prompt` | Manage prompt templates in Nacos with hot updates |
| `agentscope-extensions-nacos-skill` | Load skill packages (ZIP) from the Nacos AI module |

## A2A registry & discovery

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Server side: register the AgentCard with Nacos

```java
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;

Properties props = new Properties();
props.setProperty("serverAddr", "127.0.0.1:8848");
NacosA2aRegistry registry = new NacosA2aRegistry(props);

NacosA2aRegistryProperties props2 = new NacosA2aRegistryProperties();
// props2.setNamespace(...) / setGroup(...) / etc.
registry.registerAgent(agentCard, props2);
```

After registration, the AgentCard and the service endpoint are written into the Nacos AI Service for consumers to discover.

### Client side: resolve a remote AgentCard via Nacos

```java
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;

NacosAgentCardResolver resolver = new NacosAgentCardResolver(props, "translator-agent");
A2aAgent remote = A2aAgent.builder()
    .name("translator")
    .agentCardResolver(resolver)
    .build();
```

## Prompt config center

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-prompt</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Usage

```java
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.prompt.NacosPromptListener;

NacosPromptListener prompts = new NacosPromptListener(aiService);

String tpl = prompts.getPrompt("system-prompt", Map.of(
    "userName", "Alice"
));
```

The listener maintains a local cache; when prompts change in Nacos, updates are pushed in. The next `getPrompt(...)` call returns the new version with no restart.

## Skill repository

`agentscope-extensions-nacos-skill` provides an `AgentSkillRepository` implementation that downloads and parses skill ZIP packages managed by the Nacos AI module.

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
// or SKILL_LABEL_PATH = "stable"

NacosSkillRepository repo = new NacosSkillRepository(aiService, "default-namespace", props);
AgentSkill skill = repo.getSkill("calculator");
```

Version/label resolution order: `Properties` provided to the constructor → JVM `-D` system properties → environment variables. When both version and label resolve, **version wins** and the label is not used for download.

## MCP server discovery

`agentscope-extensions-nacos-mcp` subscribes to an MCP server in the Nacos MCP registry and aggregates its backend instances into a single load-balanced MCP client. Scaling the server is picked up from Nacos push, with no Agent restart.

> Requires a Nacos 3.x server with the MCP registry capability enabled.

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-mcp</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Manual wiring

```java
import io.agentscope.core.nacos.mcp.discovery.NacosMcpDiscoveryClient;
import io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper;

Properties props = new Properties();
props.setProperty("serverAddr", "127.0.0.1:8848");

NacosLoadBalancedMcpClientWrapper client =
    NacosLoadBalancedMcpClientWrapper.builder("weather")
        .serverName("weather-mcp-server")
        .version("1.0.0")             // omit to subscribe the default version
        .discoveryClient(new NacosMcpDiscoveryClient(props))
        .build();

client.initialize().block();
toolkit.registerMcpClient(client).block();
```

Tool calls are dispatched to one of the MCP server's instances, decided by an `EndpointSelector`. Two are built in:

| Strategy | When to use |
| --- | --- |
| `RoundRobinEndpointSelector` | Default. Rotates calls across instances; requires stateless instances |
| `StickyEndpointSelector` | Prefers one instance; suits stateful servers |

### Spring Boot autoconfiguration

Add `agentscope-nacos-spring-boot-starter`; everything is wired automatically when `agentscope.nacos.mcp.enabled=true`. Each `connections` entry becomes one load-balanced MCP client:

```yaml
agentscope:
  nacos:
    server-addr: 127.0.0.1:8848     # MCP may target a separate Nacos cluster
    mcp:
      enabled: true
      load-balance: round-robin     # default, overridable per connection
      connections:
        weather:
          service-name: weather-mcp-server
          version: 1.0.0
        amap:
          service-name: amap-mcp-server
          load-balance: sticky
```

Register the assembled `NacosMcpClients` into the Agent's Toolkit in one call:

```java
@Bean
public HarnessAgent harnessAgent(NacosMcpClients nacosMcpClients) {
    HarnessAgent agent = HarnessAgent.builder()
        .name("Assistant")
        .model("dashscope:qwen-max")
        .build();
    nacosMcpClients.registerTo(agent.getToolkit());   // a failing connection does not block the others
    return agent;
}
```

`agentscope.nacos.mcp.*` inherits the connection settings of `agentscope.nacos.*` (`server-addr`, `namespace`, `username`, `password`, and more); fields left unset fall back to the latter.

## Pairs well with

- [A2A](/v2/en/integration/protocol/a2a): inject a Nacos-backed `AgentRegistry` into `AgentScopeA2aServer.builder().agentRegistry(...)` to publish AgentCards cluster-wide on startup.
- [Skill repositories](/v2/en/integration/skill/index): coexist with Git/MySQL `AgentSkillRepository` to assemble a Toolkit from multiple sources.
- Static MCP configuration: `mcpServers` in `tools.json` and MCP servers discovered from Nacos are independent sources and can be registered into the same Toolkit.
