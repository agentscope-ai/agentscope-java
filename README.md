<p align="center">
  <img
    src="https://img.alicdn.com/imgextra/i1/O1CN01nTg6w21NqT5qFKH1u_!!6000000001621-55-tps-550-550.svg"
    alt="AgentScope Logo"
    width="200"
  />
</p>

<h3 align="center">Build Production-Ready AI Agents in Java</h3>

<p align="center">
  <a href="https://java.agentscope.io/">📖 Documentation</a>
  &nbsp;|&nbsp;
  <a href="README_zh.md">中文</a>
  &nbsp;|&nbsp;
  <a href="https://discord.gg/eYMpfnkG8h">Discord</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License" />
  <img src="https://img.shields.io/badge/JDK-17%2B-orange" alt="JDK 17+" />
  <img src="https://img.shields.io/maven-central/v/io.agentscope/agentscope?color=green" alt="Maven Central" />
  <a href="https://deepwiki.com/agentscope-ai/agentscope-java"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
</p>

---

AgentScope Java is an agent-oriented programming framework for building LLM-powered applications. It provides everything you need to create intelligent agents: ReAct reasoning, tool calling, memory management, multi-agent collaboration, and more.

## Highlights

### 🎯 Smart Agents, Full Control

AgentScope adopts the ReAct (Reasoning-Acting) paradigm, enabling agents to autonomously plan and execute complex tasks. Unlike rigid workflow-based approaches, ReAct agents dynamically decide which tools to use and when, adapting to changing requirements in real-time.

However, autonomy without control is a liability in production. AgentScope provides comprehensive runtime intervention mechanisms:

- **Safe Interruption** - Pause agent execution at any point while preserving full context and tool state, enabling seamless resumption without data loss
- **Graceful Cancellation** - Terminate long-running or unresponsive tool calls without corrupting agent state, allowing immediate recovery and redirection
- **Human-in-the-Loop** - Inject corrections, additional context, or guidance at any reasoning step through the Hook system, maintaining human oversight over critical decisions

### 🛠️ Built-in Tools

AgentScope includes production-ready tools that address common challenges in agent development:

- **PlanNotebook** - A structured task management system that decomposes complex objectives into ordered, trackable steps. Agents can create, modify, pause, and resume multiple concurrent plans, ensuring systematic execution of multi-step workflows.

- **Structured Output** - A self-correcting output parser that guarantees type-safe responses. When LLM output deviates from the expected format, the system automatically detects errors and guides the model to produce valid output, mapping results directly to Java POJOs without manual parsing.

- **Long-term Memory** - Persistent memory storage with semantic search capabilities across sessions. Supports automatic management, agent-controlled recording, or hybrid modes. Enables multi-tenant isolation for enterprise deployments where agents serve multiple users independently.

- **RAG (Retrieval-Augmented Generation)** - Seamless integration with enterprise knowledge bases. Supports both self-hosted embedding-based retrieval and managed services like Alibaba Cloud Bailian, grounding agent responses in authoritative data sources.

### 🔌 Seamless Integration

AgentScope is designed to integrate with existing enterprise infrastructure without requiring extensive modifications:

- **MCP Protocol** - Integrate with any MCP-compatible server to instantly extend agent capabilities. Connect to the growing ecosystem of MCP tools and services—from file systems and databases to web browsers and code interpreters—without writing custom integration code.

- **A2A Protocol** - Enable distributed multi-agent collaboration through standard service discovery. Register agent capabilities to Nacos or similar registries, allowing agents to discover and invoke each other as naturally as calling microservices.

### 🚀 Production Grade

Built for enterprise deployment requirements:

- **High Performance** - Reactive architecture based on Project Reactor ensures non-blocking execution. GraalVM native image compilation achieves 200ms cold start times, making AgentScope suitable for serverless and auto-scaling environments.

- **Security Sandbox** - AgentScope Runtime provides isolated execution environments for untrusted tool code. Includes pre-built sandboxes for GUI automation, file system operations, and mobile device interaction, preventing unauthorized access to system resources.

- **Observability** - Native integration with OpenTelemetry for distributed tracing across the entire agent execution pipeline. AgentScope Studio provides visual debugging, real-time monitoring, and comprehensive logging for development and production environments.

## Quick Start

**Requirements:** JDK 17+

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.7</version>
</dependency>
```

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful AI assistant.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-max")
        .build())
    .build();

Msg response = agent.call(Msg.builder()
        .textContent("Hello!")
        .build()).block();
System.out.println(response.getTextContent());
```

For more examples, see the [documentation](https://java.agentscope.io/).

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](./CONTRIBUTING.md) for guidelines.

## Community

| [Discord](https://discord.gg/eYMpfnkG8h)                     | DingTalk | WeChat |
|--------------------------------------------------------------|----------| ---------|
| <img src="./docs/imgs/discord.png" width="100" height="100"> | <img src="./docs/imgs/dingtalk_qr_code.jpg" width="100" height="100"> | <img src="./docs/imgs/wechat.png" width="100" height="100"> |

## License

Apache License 2.0 - see [LICENSE](./LICENSE) for details.

## Publications

If you find AgentScope helpful, please cite our papers:

- [AgentScope 1.0: A Developer-Centric Framework for Building Agentic Applications](https://arxiv.org/abs/2508.16279)
- [AgentScope: A Flexible yet Robust Multi-Agent Platform](https://arxiv.org/abs/2402.14034)

## Contributors

<a href="https://github.com/agentscope-ai/agentscope-java/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=agentscope-ai/agentscope-java&max=999&columns=12&anon=1" />
</a>
