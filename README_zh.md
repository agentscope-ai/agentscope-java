[**English Homepage**](README.md)
<p align="center">
  <img
    src="https://img.alicdn.com/imgextra/i1/O1CN01nTg6w21NqT5qFKH1u_!!6000000001621-55-tps-550-550.svg"
    alt="AgentScope Logo"
    width="200"
  />
</p>

## AgentScope Java

面向 Java 开发者的智能体编程框架，用于构建大语言模型应用。

AgentScope 提供了完整的工具集，支持创建具备工具调用、记忆管理、多智能体协作等能力的智能体。

![](https://img.shields.io/badge/GUI-AgentScope_Studio-blue?logo=look&logoColor=green&color=dark-green)![](https://img.shields.io/badge/license-Apache--2.0-black)

## Why AgentScope?

浅显入门，精深致用。

- **对开发者透明**: 透明是 AgentScope 的**首要原则**。无论提示工程、API 调用、智能体构建还是工作流程编排，坚持对开发者可见可控。拒绝深度封装或隐式魔法。
- **实时介入**: 原生支持**实时**中断和**自定义**中断处理。
- **更智能化**: 支持智能体工具管理、智能体长期记忆控制和智能化 RAG 等。
- **模型无关**: 一次编程，适配所有模型（DashScope、OpenAI、Anthropic 等）。
- **"乐高式"智能体构建**: 所有组件保持**模块化**且**相互独立**。
- **面向多智能体**: 专为**多智能体**设计，**显式**的消息传递和工作流编排，支持 Pipeline 流水线。
- **响应式架构**: 基于 Project Reactor 构建，高效的非阻塞异步操作。
- **多模态支持**: 原生支持视觉、音频和视频内容处理。
- **高度可定制**: 工具、提示、智能体、工作流、钩子和可视化，AgentScope 支持并鼓励开发者进行定制。

## 快速开始
### 安装
AgentScope Java 需要 **JDK 17** 或更高版本。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Hello AgentScope!
从一个基本的 ReActAgent 开始，回复用户查询！

```java
public static void main(String[] args) {
    ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .sysPrompt("You are a helpful AI assistant.")
        .model(DashScopeChatModel.builder()
				.apiKey(System.getenv("DASHSCOPE_API_KEY"))
				.modelName("qwen-max")
				.build())
        .build();

    Msg userMessage = Msg.builder()
        .textContent("Hello, please introduce yourself.")
        .build();

    Msg response = agent.call(userMessage).block();
    System.out.println("Agent Response: " + response.getTextContent());
}
```

### 为 Agent 配备工具
1. 定义工具

	定义一个工具类，其中方法被 `@Tool` 注解。这里有一个 `SimpleTools` 类，其中有一个时间工具：

	```java
	public class SimpleTools {
	    @Tool(name = "get_time", description = "Get current time string of a time zone")
	    public String getTime(
	            @ToolParam(name = "zone", description = "Time zone, e.g., Beijing") String zone) {
	        LocalDateTime now = LocalDateTime.now();
	        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	    }
	}
	```

2. 注册工具到 ReActAgent

	通过 `Toolkit` 使用 `registerTool` 方法注册工具类：

	```java
	public static void main(String[] args) {
	    Model model = DashScopeChatModel.builder()
	        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
	        .modelName("qwen-max")
	        .build();

	    Toolkit toolkit = new Toolkit();
	    toolkit.registerTool(new SimpleTools());

	    ReActAgent agent = ReActAgent.builder()
	        .name("Assistant")
	        .sysPrompt("You are a helpful AI assistant.")
	        .model(model)
	        .toolkit(toolkit)
	        .build();

	    Msg userMessage = Msg.builder()
	        .role(MsgRole.USER)
	        .textContent("Please tell me the current time.")
	        .build();

	    Msg response = agent.call(userMessage).block();
	    System.out.println("Agent Response: " + response.getTextContent());
	}
	```

## 文档

### 快速入门
+ [安装指南](./docs/zh/quickstart/installation.md)
+ [核心概念](./docs/zh/quickstart/key-concepts.md)
+ [创建 ReAct Agent](./docs/zh/quickstart/agent.md)

### 核心功能
+ [模型集成](./docs/zh/task/model.md)
+ [工具系统](./docs/zh/task/tool.md)
+ [记忆管理](./docs/zh/task/memory.md)
+ [Hook 系统](./docs/zh/task/hook.md)

### 高级功能
+ [多智能体 Pipeline](./docs/zh/task/pipeline.md)
+ [状态与会话管理](./docs/zh/task/session.md)
+ [多模态 (视觉/音频)](./docs/zh/task/multimodal.md)
+ [结构化输出](./docs/zh/task/structured-output.md)
+ [MCP 集成](./docs/zh/task/mcp.md)
+ [RAG](./docs/zh/task/rag.md)
+ [计划管理](./docs/zh/task/plan.md)
+ [AgentScope Studio](./docs/zh/task/studio.md)

## Roadmap

在 12 月，我们将进一步推出基于上下文管理与基于 Trinity-RFT 的强化学习最佳实践。

在技术演进层面，我们正持续探索更高效、智能的上下文工程与多 Agent 协同范式，致力于支撑更强大的 AI 应用构建。

此外，针对 Agent 流量呈现的"二八定律"特征（头部 20% 的 Agent 承载了 80% 的流量），我们在架构上会全力推进 Serverless 化，通过实现毫秒级冷启动与混合部署，帮助开发者在应对高并发的同时，显著降低部署成本并提升效率。

## 许可
AgentScope 基于 Apache License 2.0 发布。
