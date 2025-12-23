# AgentScope Java

**Build Production-Ready AI Agents in Java**

---

## What is AgentScope Java?

AgentScope Java is an agent-oriented programming framework for building LLM-powered applications. It provides everything you need to create intelligent agents: ReAct reasoning, tool calling, memory management, multi-agent collaboration, and more.

## Highlights

### Smart Agents, Full Control

AgentScope adopts the ReAct (Reasoning-Acting) paradigm, enabling agents to autonomously plan and execute complex tasks. Unlike rigid workflow-based approaches, ReAct agents dynamically decide which tools to use and when, adapting to changing requirements in real-time.

However, autonomy without control is a liability in production. AgentScope provides comprehensive runtime intervention mechanisms:

- **Safe Interruption** - Pause agent execution at any point while preserving full context and tool state, enabling seamless resumption without data loss
- **Graceful Cancellation** - Terminate long-running or unresponsive tool calls without corrupting agent state, allowing immediate recovery and redirection
- **Human-in-the-Loop** - Inject corrections, additional context, or guidance at any reasoning step through the Hook system, maintaining human oversight over critical decisions

### Built-in Tools

AgentScope includes production-ready tools that address common challenges in agent development:

- **PlanNotebook** - A structured task management system that decomposes complex objectives into ordered, trackable steps. Agents can create, modify, pause, and resume multiple concurrent plans, ensuring systematic execution of multi-step workflows.
- **Structured Output** - A self-correcting output parser that guarantees type-safe responses. When LLM output deviates from the expected format, the system automatically detects errors and guides the model to produce valid output, mapping results directly to Java POJOs without manual parsing.
- **Long-term Memory** - Persistent memory storage with semantic search capabilities across sessions. Supports automatic management, agent-controlled recording, or hybrid modes. Enables multi-tenant isolation for enterprise deployments where agents serve multiple users independently.
- **RAG (Retrieval-Augmented Generation)** - Seamless integration with enterprise knowledge bases. Supports both self-hosted embedding-based retrieval and managed services like Alibaba Cloud Bailian, grounding agent responses in authoritative data sources.

### Seamless Integration

AgentScope is designed to integrate with existing enterprise infrastructure without requiring extensive modifications:

- **MCP Protocol** - Integrate with any MCP-compatible server to instantly extend agent capabilities. Connect to the growing ecosystem of MCP tools and services—from file systems and databases to web browsers and code interpreters—without writing custom integration code.
- **A2A Protocol** - Enable distributed multi-agent collaboration through standard service discovery. Register agent capabilities to Nacos or similar registries, allowing agents to discover and invoke each other as naturally as calling microservices.

### Production Grade

Built for enterprise deployment requirements:

- **High Performance** - Reactive architecture based on Project Reactor ensures non-blocking execution. GraalVM native image compilation achieves 200ms cold start times, making AgentScope suitable for serverless and auto-scaling environments.
- **Security Sandbox** - AgentScope Runtime provides isolated execution environments for untrusted tool code. Includes pre-built sandboxes for GUI automation, file system operations, and mobile device interaction, preventing unauthorized access to system resources.
- **Observability** - Native integration with OpenTelemetry for distributed tracing across the entire agent execution pipeline. AgentScope Studio provides visual debugging, real-time monitoring, and comprehensive logging for development and production environments.

## Requirements

- **JDK 17 or higher**
- Maven or Gradle

## Quick Start

Follow these steps to get started with AgentScope Java:

1. **[Installation](quickstart/installation.md)** - Set up AgentScope Java in your project
2. **[Key Concepts](quickstart/key-concepts.md)** - Understand core concepts and architecture
3. **[Build Your First Agent](quickstart/agent.md)** - Create a working agent

## Quick Example

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.message.Msg;

// Create an agent with inline model configuration
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful AI assistant.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build())
    .build();

Msg response = agent.call(Msg.builder()
        .textContent("Hello!")
        .build()).block();
System.out.println(response.getTextContent());
```

## Features

Once you're familiar with the basics, explore these features:

### Model Integration
- **[Model Integration](task/model.md)** - Configure different LLM providers
- **[Multimodal](task/multimodal.md)** - Vision and multimodal capabilities

### Tools & Knowledge
- **[Tool System](task/tool.md)** - Create and use tools with annotation-based registration
- **[MCP](task/mcp.md)** - Model Context Protocol support for advanced tool integration
- **[RAG](task/rag.md)** - Retrieval-Augmented Generation for knowledge-enhanced responses
- **[Structured Output](task/structured-output.md)** - Type-safe output parsing with automatic correction

### Behavior Control
- **[Hook System](task/hook.md)** - Monitor and customize agent behavior with event hooks
- **[Memory Management](task/memory.md)** - Manage conversation history and long-term memory
- **[Planning](task/plan.md)** - Plan management for complex multi-step tasks
- **[Agent Configuration](task/agent-config.md)** - Advanced agent configuration options

### Multi-Agent Systems
- **[Pipeline](multi-agent/pipeline.md)** - Build multi-agent workflows with sequential and parallel execution
- **[MsgHub](multi-agent/msghub.md)** - Message hub for multi-agent communication
- **[A2A Server](task/a2a-server.md)** - Expose agents as A2A-compatible services
- **[A2A Agent](task/a2a-agent.md)** - Invoke remote agents via A2A protocol
- **[State Management](task/state.md)** - Persist and restore agent state across sessions

### Observability & Debugging
- **[AgentScope Studio](task/studio.md)** - Visual debugging and monitoring

### Internals
- **[ReAct Loop](internals/react-loop.md)** - Complete breakdown of agent execution flow
- **[Message System](internals/message-system.md)** - Message structure and content block types
- **[Tool Execution](internals/tool-execution.md)** - Tool registration, parameter injection, and scheduling
- **[Hook Event System](internals/hook-event-system.md)** - Event types and lifecycle
- **[Memory Architecture](internals/memory-architecture.md)** - Short-term and long-term memory collaboration
- **[Formatter Internals](internals/formatter-internals.md)** - Message format conversion and multi-agent handling

## AI-Powered Development

AgentScope provides an LLM-friendly guide for AI code assistants like Cursor, Windsurf, and GitHub Copilot. This guide helps AI assistants understand AgentScope Java's APIs, patterns, and best practices to generate accurate code.

**Quick Setup for Cursor:**

1. Open Cursor Settings -> Features -> Docs
2. Click "+ Add new Doc"
3. Add URL: `https://raw.githubusercontent.com/agentscope-ai/agentscope-java/main/docs/llm/agentscope-llm-guide.md`

For detailed setup instructions for other AI IDEs and best practices, see [Using AgentScope Java with AI Code Assistants](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/llm/README.md).

## Community

- **GitHub**: [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)

| [Discord](https://discord.gg/eYMpfnkG8h) | DingTalk                                 |    WeChat    |
|------------------------------------------|------------------------------------------|------------------------------------------|
| ![QR Code](../imgs/discord.png)                             |   ![QR Code](../imgs/dingtalk_qr_code.jpg)   |  ![QR Code](../imgs/wechat.png)   |
