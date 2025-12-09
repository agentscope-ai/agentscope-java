# AgentScope Java

**An agent-oriented programming framework for building LLM applications**

---

## What is AgentScope Java?

AgentScope Java is a powerful framework that enables developers to build LLM-powered applications using agent-oriented programming paradigms. It provides a comprehensive toolkit for creating intelligent agents with tool calling, memory management, multi-agent collaboration, and more.

## Key Features

- **Multi-Model Support**: DashScope (Qwen), OpenAI, and more LLM providers
- **Tool System**: Annotation-based tool registration and execution with automatic schema generation
- **Reactive Architecture**: Built on Project Reactor for efficient non-blocking operations
- **Memory Management**: Short-term memory and long-term memory with external backends (Mem0)
- **Multi-Agent Pipelines**: Sequential and parallel agent workflows for complex tasks
- **State Management**: Session-based persistence and recovery with JSON storage
- **Hook System**: Extensible event-driven customization for monitoring and control
- **MCP Support**: Model Context Protocol integration for enhanced tool capabilities

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
var agent = ReActAgent.builder()
    .name("Assistant")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build())
    .sysPrompt("You are a helpful assistant.")
    .build();

// Call the agent
Msg userMsg = Msg.builder()
    .textContent("Hello!")
    .build();

Msg response = agent.call(userMsg).block();
System.out.println(response.getTextContent());
```

## Advanced Topics

Once you're familiar with the basics, explore these advanced features:

### Model Integration
- **[Model Integration](task/model.md)** - Configure different LLM providers

### Tools & Knowledge
- **[Tool System](task/tool.md)** - Create and use tools with annotation-based registration
- **[MCP](task/mcp.md)** - Model Context Protocol support for advanced tool integration
- **[RAG](task/rag.md)** - Retrieval-Augmented Generation for knowledge-enhanced responses

### Agent Customization
- **[Hook System](task/hook.md)** - Monitor and customize agent behavior with event hooks
- **[Memory Management](task/memory.md)** - Manage conversation history and long-term memory
- **[Planning](task/plan.md)** - Plan management for complex multi-step tasks

### Multi-Agent Systems
- **[Pipeline](task/pipeline.md)** - Build multi-agent workflows with sequential and parallel execution
- **[State Management](task/state.md)** - Persist and restore agent state across sessions

## Community

- **GitHub**: [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)

| [Discord](https://discord.gg/eYMpfnkG8h) | DingTalk                                 |
|------------------------------------------|------------------------------------------|
| ![QR Code](https://gw.alicdn.com/imgextra/i1/O1CN01hhD1mu1Dd3BWVUvxN_!!6000000000238-2-tps-400-400.png)                             | ![QR Code](../imgs/dingtalk_qr_code.png) |

## License

AgentScope Java is released under the Apache License 2.0.

---

**Ready to build intelligent agents? Start with the [Installation Guide](quickstart/installation.md)!**
