<p align="center">
  <img
    src="https://img.alicdn.com/imgextra/i1/O1CN01nTg6w21NqT5qFKH1u_!!6000000001621-55-tps-550-550.svg"
    alt="AgentScope Logo"
    width="200"
  />
</p>

<h3 align="center">用 Java 构建生产级 AI 智能体</h3>

<p align="center">
  <a href="https://java.agentscope.io/zh/intro.html">📖 文档</a>
  &nbsp;|&nbsp;
  <a href="README.md">English</a>
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

AgentScope Java 是一个面向智能体的编程框架，用于构建基于大语言模型的应用。它提供了构建智能体所需的一切：ReAct 推理、工具调用、记忆管理、多智能体协作等。

## 核心亮点

### 🎯 自主且可控

AgentScope 采用 ReAct（推理-行动）范式，使智能体能够自主规划和执行复杂任务。与僵化的工作流方式不同，ReAct 智能体可以动态决定使用哪些工具以及何时使用，实时适应不断变化的需求。

然而，在生产环境中，没有控制的自主性是一种隐患。AgentScope 提供了完整的运行时介入机制：

- **安全中断** - 在任意时刻暂停智能体执行，同时完整保留上下文和工具状态，支持无损恢复
- **优雅取消** - 终止长时间运行或无响应的工具调用，不会破坏智能体状态，支持立即恢复和重定向
- **人机协同** - 通过 Hook 系统在任意推理步骤注入修正、补充上下文或指导，保持人类对关键决策的监督

### 🛠️ 内置工具

AgentScope 内置了生产就绪的工具，解决智能体开发中的常见挑战：

- **PlanNotebook** - 结构化的任务管理系统，将复杂目标分解为有序、可追踪的步骤。智能体可以创建、修改、暂停和恢复多个并发计划，确保多步骤工作流的系统化执行。

- **结构化输出** - 自纠错的输出解析器，保证类型安全的响应。当 LLM 输出偏离预期格式时，系统自动检测错误并引导模型产生有效输出，直接映射到 Java POJO，无需手动解析。

- **长期记忆** - 跨会话的持久化记忆存储，具备语义搜索能力。支持自动管理、智能体主动记录或混合模式。支持多租户隔离，满足企业级部署中智能体独立服务多用户的需求。

- **RAG（检索增强生成）** - 与企业知识库无缝集成。支持自建的基于 Embedding 的检索服务，也支持阿里云百炼等托管服务，让智能体的回答基于权威数据源。

### 🔌 无缝集成

AgentScope 设计上能够与现有企业基础设施集成，无需大规模改造：

- **MCP 协议** - 集成任意 MCP 兼容的服务，即刻扩展智能体能力。连接日益丰富的 MCP 工具和服务生态——从文件系统、数据库到浏览器、代码解释器——无需编写自定义集成代码。

- **A2A 协议** - 通过标准服务发现实现分布式多智能体协作。将智能体能力注册到 Nacos 或类似注册中心，使智能体之间的相互发现和调用如同调用微服务一样自然。

### 🚀 生产就绪

为企业级部署需求而构建：

- **高性能** - 基于 Project Reactor 的响应式架构确保非阻塞执行。GraalVM 原生镜像编译实现 200ms 冷启动，使 AgentScope 适用于 Serverless 和弹性伸缩环境。

- **安全沙箱** - AgentScope Runtime 为不可信的工具代码提供隔离执行环境。内置 GUI 自动化、文件系统操作和移动设备交互的预置沙箱，防止未授权访问系统资源。

- **可观测性** - 原生集成 OpenTelemetry，实现智能体执行全链路的分布式追踪。AgentScope Studio 提供可视化调试、实时监控和完整日志，支持开发和生产环境。

## 快速开始

**环境要求：** JDK 17+

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.6</version>
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

更多示例请参阅[文档](https://java.agentscope.io/zh/intro.html)。

## 贡献

欢迎贡献！请参阅 [CONTRIBUTING_zh.md](./CONTRIBUTING_zh.md) 了解详情。

## 社区

| [Discord](https://discord.gg/eYMpfnkG8h)                      | DingTalk | WeChat |
|---------------------------------------------------------------|----------| ---------|
| <img src="./docs/imgs/discord.png" width="100" height="100"/> | <img src="./docs/imgs/dingtalk_qr_code.jpg" width="100" height="100"> | <img src="./docs/imgs/wechat.png" width="100" height="100"> |

## 许可

Apache License 2.0 - 详见 [LICENSE](./LICENSE)。

## 论文

如果 AgentScope 对您有帮助，请引用我们的论文：

- [AgentScope 1.0: A Developer-Centric Framework for Building Agentic Applications](https://arxiv.org/abs/2508.16279)
- [AgentScope: A Flexible yet Robust Multi-Agent Platform](https://arxiv.org/abs/2402.14034)

## 贡献者

<a href="https://github.com/agentscope-ai/agentscope-java/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=agentscope-ai/agentscope-java&max=999&columns=12&anon=1" />
</a>
