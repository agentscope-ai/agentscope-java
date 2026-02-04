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

## Human-in-the-Loop (HITL) Support

Sub-agents support Human-in-the-Loop (HITL), allowing sub-agents to pass suspend state to the parent agent and user when encountering operations requiring human confirmation, and resume execution after user confirmation. Currently, only ReactAgent is supported as a sub-Agent.

### Enabling HITL

```java
import io.agentscope.core.tool.subagent.SubAgentConfig;

// Configure sub-agent tool with HITL enabled
toolkit.registration()
        .subAgent(() -> ReActAgent.builder()
                .name("DataAnalyst")
                .sysPrompt("You are a data analysis expert.")
                .model(model)
                .build())
        .config(SubAgentConfig.builder()
                .enableHITL(true)  // Enable human-in-the-loop
                .build())
        .apply();

// Create main agent with HITL support enabled
ReActAgent mainAgent = ReActAgent.builder()
        .name("Coordinator")
        .sysPrompt("You are a coordinator responsible for calling the data analyst.")
        .model(model)
        .toolkit(toolkit)
        .enableSubAgentHITL(true)  // Main agent also needs HITL support enabled
        .build();
```

### Handling Suspend and Resume

When a sub-agent is suspended, the returned message contains the pending tool information. Display it to the user and decide next steps based on their choice:

```java
import io.agentscope.core.tool.subagent.SubAgentContext;

Msg response = mainAgent.call(userMsg).block();

// Check if sub-agent is suspended
while (response.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
    List<ToolResultBlock> toolResults = response.getContentBlocks(ToolResultBlock.class);
    
    for (ToolResultBlock resultBlock : toolResults) {
        if (!SubAgentContext.isSubAgentResult(resultBlock)) {
            continue;
        }
        
        // Get the blocked tool calls from sub-agent
        List<ToolUseBlock> pendingTools = resultBlock.getOutput().stream()
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .toList();
        
        if (!userConfirms(pendingTools)) {
            // User declined, submit cancellation results
            List<ToolResultBlock> cancelResults = pendingTools.stream()
                    .map(t -> ToolResultBlock.of(t.getId(), t.getName(),
                            TextBlock.builder().text("Operation cancelled").build()))
                    .toList();
            mainAgent.submitSubAgentResults(resultBlock.getId(), cancelResults);
        }
        response = mainAgent.call().block();
    }
}

// Final response
System.out.println(response.getTextContent());
```

## Quick Reference

**Configuration methods**:
- `SubAgentConfig.enableHITL(boolean)` — Enable/disable sub-agent HITL support
- `ReActAgent.enableSubAgentHITL(boolean)` — Enable/disable main agent HITL support
- `ReActAgent.isEnableSubAgentHITL()` — Whether the main agent supports sub-agent HITL

**Detection methods**:
- `SubAgentContext.isSubAgentResult(ToolResultBlock)` — Check if result is from sub-agent
- `SubAgentContext.getSubAgentGenerateReason(ToolResultBlock)` — Get generate reason of sub-agent
- `SubAgentContext.extractSessionId(ToolResultBlock)` — Extract session ID

**Resume methods**:
- `mainAgent.call()` — Continue executing pending tools
- `mainAgent.submitSubAgentResult(String, ToolResultBlock)` — Submit single tool result
- `mainAgent.submitSubAgentResults(String, List<ToolResultBlock>)` — Submit multiple tool results