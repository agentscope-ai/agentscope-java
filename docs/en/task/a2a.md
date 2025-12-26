# A2A (Agent2Agent)

A2A is AgentScope's support for the [A2A protocol](https://a2a-protocol.org/latest/specification/), including client (calling remote Agents) and server (exposing local Agents) components.

---

## Client: A2aAgent

Use remote A2A services as local Agents.

### Quick Start

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

// Create A2A Agent
A2aAgent agent = A2aAgent.builder()
    .name("remote-agent")
    .agentCardResolver(new WellKnownAgentCardResolver(
        "http://127.0.0.1:8080",
        "/.well-known/agent-card.json",
        Map.of()))
    .build();

// Call remote Agent
Msg response = agent.call(userMsg).block();
```

### Configuration Options

| Parameter | Type | Description |
|-----------|------|-------------|
| `agentCard` | AgentCard | Provide AgentCard directly |
| `agentCardResolver` | AgentCardResolver | Obtain AgentCard through resolver |
| `memory` | Memory | Memory component |
| `hook` / `hooks` | Hook | Hook functions |

### AgentCard Resolution

```java
// Option 1: Provide directly
A2aAgent.builder()
    .agentCard(agentCard)
    .build();

// Option 2: From well-known path
A2aAgent.builder()
    .agentCardResolver(new WellKnownAgentCardResolver(url, path, headers))
    .build();

// Option 3: Custom resolver
A2aAgent.builder()
    .agentCardResolver(agentName -> customGetAgentCard(agentName))
    .build();
```

---

## Server: A2A Server

Expose local Agents as A2A services.

### Spring Boot (Recommended)

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
        description: An intelligent assistant based on AgentScope
```

```java
@SpringBootApplication
public class A2aServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(A2aServerApplication.class, args);
    }
}
```

### Manual Setup

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;

// Create A2A Server
AgentScopeA2aServer server = AgentScopeA2aServer.builder(
        ReActAgent.builder()
            .name("my-assistant")
            .sysPrompt("You are a helpful assistant"))
    .deploymentProperties(DeploymentProperties.builder()
        .host("localhost")
        .port(8080)
        .build())
    .build();

// Get transport handler for web framework
JsonRpcTransportWrapper transport =
    server.getTransportWrapper("JSON-RPC", JsonRpcTransportWrapper.class);

// Call when web service is ready
server.postEndpointReady();
```

### Configure AgentCard

```java
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;

ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
    .name("My Assistant")
    .description("Intelligent assistant")
    .version("1.0.0")
    .skills(List.of(
        new AgentSkill("text-generation", "Text Generation"),
        new AgentSkill("question-answering", "Q&A")))
    .build();

AgentScopeA2aServer.builder(agentBuilder)
    .agentCard(agentCard)
    .build();
```

---

## Task Interruption

```java
// Client interruption
agent.interrupt();

// Interrupt with message
agent.interrupt(Msg.builder()
    .textContent("User cancelled the operation")
    .build());
```

---

## More Resources

- **A2A Protocol Specification**: https://a2a-protocol.org/latest/specification/
- **Agent Interface**: [Agent.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java)
