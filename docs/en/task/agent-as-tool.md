# Agent as Tool

```{admonition} Experimental Feature
:class: warning

This feature is currently experimental and the API may change. If you encounter any issues, please provide feedback via [GitHub Issues](https://github.com/agentscope-ai/agentscope-java/issues).
```

## Overview

Agent as Tool allows registering an agent as a tool that can be called by other agents. This pattern is useful for building hierarchical or collaborative multi-agent systems:

- **Expert Specialization**: Main agent calls different expert agents based on task type
- **Task Delegation**: Delegate complex subtasks to specialized agents
- **Multi-turn Conversation**: Sub-agents can maintain conversation state for continuous interaction

## How It Works

When a parent agent calls a sub-agent tool, the system:

1. **Creates Sub-agent Instance**: Creates a new agent instance via the Provider factory
2. **Restores Conversation State**: If `session_id` is provided, restores previous state from Session
3. **Executes Conversation**: Sub-agent processes the message and generates a response
4. **Saves State**: Saves sub-agent state to Session for future calls
5. **Returns Result**: Returns the response and `session_id` to the parent agent

```
Parent Agent ──call──→ SubAgentTool ──create──→ Sub-agent
                           │                       │
                           │←──── return result ───┘
                           │
                        Session (state persistence)
```

## Quick Start

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.model.DashScopeChatModel;

// Create model
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build();

// Create sub-agent Provider (factory)
// Note: Must use lambda to ensure new instance is created for each call
Toolkit toolkit = new Toolkit();
toolkit.registration()
        .subAgent(() -> ReActAgent.builder()
                .name("Expert")
                .sysPrompt("You are a domain expert responsible for answering professional questions.")
                .model(model)
                .build())
        .apply();

// Create main agent with toolkit
ReActAgent mainAgent = ReActAgent.builder()
        .name("Coordinator")
        .sysPrompt("You are a coordinator. When facing professional questions, call the call_expert tool to consult the expert.")
        .model(model)
        .toolkit(toolkit)
        .build();

// Main agent will automatically call expert agent when needed
Msg response = mainAgent.call(userMsg).block();
```

## Configuration Options

Customize sub-agent tool behavior with `SubAgentConfig`:

```java
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.session.JsonSession;
import java.nio.file.Path;

SubAgentConfig config = SubAgentConfig.builder()
        .toolName("ask_expert")                    // Custom tool name
        .description("Consult the expert")         // Custom description
        .forwardEvents(true)                       // Forward sub-agent events
        .session(new JsonSession(Path.of("sessions")))  // Persistent session
        .build();

toolkit.registration()
        .subAgent(() -> createExpertAgent(), config)
        .apply();
```

| Option | Description | Default |
|--------|-------------|---------|
| `toolName` | Tool name | Generated from agent name, e.g., `call_expert` |
| `description` | Tool description | Uses agent's description |
| `forwardEvents` | Whether to forward sub-agent streaming events | `true` |
| `session` | Session storage implementation | `InMemorySession` (in-memory) |

## Multi-turn Conversation

Sub-agents support multi-turn conversations, maintaining state via the `session_id` parameter:

```java
// First call: omit session_id to start a new session
// Tool returns:
// session_id: abc-123-def
//
// Expert response content...

// Subsequent calls: provide session_id to continue the conversation
// Parent agent automatically extracts session_id from previous response
```

The sub-agent tool exposes two parameters:
- `message` (required): Message to send to the sub-agent
- `session_id` (optional): Session ID. Omit to start new session, provide to continue existing one

## Persistent Sessions

By default, `InMemorySession` is used and state is lost on process restart. Use `JsonSession` to persist state to files:

```java
import io.agentscope.core.session.JsonSession;
import java.nio.file.Path;

SubAgentConfig config = SubAgentConfig.builder()
        .session(new JsonSession(Path.of("./agent-sessions")))
        .build();

toolkit.registration()
        .subAgent(() -> createAgent(), config)
        .apply();

// State will be saved to ./agent-sessions/{session_id}.json
```

## Tool Group Support

Sub-agent tools can be added to tool groups like regular tools:

```java
toolkit.createToolGroup("experts", "Expert Agents", true);

toolkit.registration()
        .subAgent(() -> createLegalExpert())
        .group("experts")
        .apply();

toolkit.registration()
        .subAgent(() -> createTechExpert())
        .group("experts")
        .apply();
```
