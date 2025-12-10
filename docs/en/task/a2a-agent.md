# A2A (Agent2Agent) Agent

## Overview

A2A (Agent2Agent) Agent is an extension module in the AgentScope framework designed to communicate with remote agents that follow the [A2A specification](https://a2a-protocol.org/latest/specification/). It implements the standard [Agent](../../../agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java) interface, allowing unified interaction with remote agents.

A2A Agent enables you to use remote agents as local agents, transparently handling complex processes such as underlying network communication, message serialization/deserialization, and more.

## Core Concepts

### AgentCard

[AgentCard](https://a2a-protocol.org/latest/specification/#441-agentcard) is one of the core concepts in the A2A protocol. It is a metadata descriptor file for remote agents containing the following key information:
- Agent name
- Description
- Supported functionalities
- Communication protocols and address information

### AgentCardResolver

`AgentCardResolver` is a resolver interface in A2A Agent used to obtain the `AgentCard` of remote agents. By implementing this interface, you can flexibly obtain AgentCard information from various sources.

AgentScope provides two built-in resolver implementations:

1. `FixedAgentCardResolver` - Directly uses a fixed AgentCard instance
2. `WellKnownAgentCardResolver` - Retrieves AgentCard from the `.well-known/agent-card.json` path of a remote address, allowing customization of the remote address path.

In addition to the built-in resolver implementations, you can also implement your own resolver to obtain `AgentCard` from custom logic or an A2A registry.

## Quick Start

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.a2a.spec.AgentCard;

// Create A2A Agent
AgentCardResolver resolver = new WellKnownAgentCardResolver(
        "http://127.0.0.1:8080",     // Address of the server to get the remote AgentCard
        "/.well-known/agent-card.json",
        Map.of());
A2aAgent a2aAgent = A2aAgent.builder()
        .name("remote-agent")
        .agentCardResolver(resolver)
        .build();

// Send a message and get a response
Msg userMsg = Msg.builder()
    .textContent("Hello, what can you help me with?")
    .build();

Msg response = a2aAgent.call(userMsg).block();
System.out.println("Response: " + response.getTextContent());
```

## Configuration and Usage

### Basic Configuration

To create an A2A Agent, you need to use the `A2aAgent.Builder` to construct an instance:

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

// Method 1: Provide AgentCard directly
AgentCard agentCard = generateAgentCard(); // Manually constructed AgentCard
A2aAgent a2aAgent = A2aAgent.builder()
    .name("remote-agent")
    .agentCard(agentCard)
    .build();

// Method 2: Automatically obtain AgentCard through AgentCardResolver
AgentCardResolver resolver = new WellKnownAgentCardResolver(
    "http://127.0.0.1:8080", 
    "/.well-known/agent-card.json", 
    Map.of());
A2aAgent a2aAgent = A2aAgent.builder()
    .name("remote-agent")
    .agentCardResolver(resolver)
    .build();
```

### Builder Parameter Details

The A2A Agent builder supports the following configuration parameters:

| Parameter           | Type                    | Description                                  |
|---------------------|-------------------------|----------------------------------------------|
| `name`              | String                  | Agent name                                   |
| `agentCard`         | AgentCard               | Provide AgentCard directly                   |
| `agentCardResolver` | AgentCardResolver       | Obtain AgentCard through resolver            |
| `memory`            | Memory                  | Memory component, defaults to InMemoryMemory |
| `checkRunning`      | boolean                 | Check running status, defaults to true       |
| `hook` / `hooks`    | Hook / List&lt;Hook&gt; | Add hook functions                           |
| `a2aAgentConfig`    | A2aAgentConfig          | A2A specific configuration                   |

### A2aAgentConfig Configuration

A2aAgentConfig is used to configure underlying transport layers and other client settings:

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

## More Usage Examples

### Configuration with Memory and Hooks

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.hook.Hook;

A2aAgent agent = A2aAgent.builder()
    .name("remote-assistant")
    .agentCard(agentCard)
    .memory(new InMemoryMemory()) // Use memory to store conversation history
    .hook(new LoggingHook()) // Add custom logging hook
    .checkRunning(true) // Check running status to prevent concurrent calls
    .build();
```

### Custom AgentCardResolver

```java
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.a2a.spec.AgentCard;

AgentCardResolver customAgentCardResolver = new AgentCardResolver() {
    
    @Override
    public AgentCard getAgentCard(String agentName) {
        // Custom logic to obtain or generate AgentCard, such as getting the AgentCard named `agentName` from a specified A2A registry.
        return customGetAgentCard(agentName);
    }
};

A2aAgent a2aAgent = A2aAgent.builder()
        .name("remote-agent")
        .agentCardResolver(customAgentCardResolver)
        .build();
```

## Error Handling and Interruption

### Interruption Mechanism

A2A Agent supports interrupting running tasks:

```java
// Interrupt the current task
agent.interrupt();

// Interrupt with a message
agent.interrupt(Msg.builder()
    .textContent("User cancelled the operation")
    .build());
```

When interrupting a task, the A2A Agent sends a cancelTask A2A request to the corresponding remote agent. If the remote agent does not support task interruption, the task interruption may fail.

### Error Handling

Errors can be captured and handled through the Hook mechanism:

```java
public class ErrorHandlingHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof ErrorEvent errorEvent) {
            // Handle error
            System.err.println("An error occurred: " + errorEvent.getError().getMessage());
        }
        return Mono.just(event);
    }
}
```