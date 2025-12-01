[**English Homepage**](README.md)
<p align="center">
  <img
    src="https://img.alicdn.com/imgextra/i1/O1CN01nTg6w21NqT5qFKH1u_!!6000000001621-55-tps-550-550.svg"
    alt="AgentScope Logo"
    width="200"
  />
</p>

## AgentScope 的Java实现
<font style="color:rgb(31, 35, 40);">这是 </font>[<font style="color:rgb(9, 105, 218);">AgentScope</font>](https://github.com/agentscope-ai/agentscope/)


![](https://img.shields.io/badge/GUI-AgentScope_Studio-blue?logo=look&logoColor=green&color=dark-green)![](https://img.shields.io/badge/license-Apache--2.0-black)

## ✨ Why AgentScope？

浅显入门，精深致用。

- **对开发者透明**: 透明是 AgentScope 的**首要原则**。无论提示工程、API调用、智能体构建还是工作流程编排，坚持对开发者可见&可控。拒绝深度封装或隐式魔法。
- **实时介入**: 原生支持**实时**中断和**自定义**中断处理。
- **更智能化**: 支持智能体工具管理、智能体长期记忆控制和智能化RAG等。
- **模型无关**: 一次编程，适配所有模型。
- **“乐高式”智能体构建**: 所有组件保持**模块化**且**相互独立**。
- **面向多智能体**：专为**多智能体**设计，**显式**的消息传递和工作流编排，拒绝深度封装。
- **高度可定制**: 工具、提示、智能体、工作流、第三方库和可视化，AgentScope 支持&鼓励开发者进行定制。

## 🚀 快速开始
### 安装
AgentScope Java 需要 **jdk 17** 或更高版本。

```bash
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
    Model model = DashScopeChatModel.builder()
		.apiKey(System.getenv("DASHSCOPE_API_KEY"))
		.modelName("qwen-max")
		.build();

    ReActAgent agent = ReActAgent.builder()
    .name("hello-world-agent")
    .sysPrompt("You are a helpful AI assistant. Be concise and friendly. " +
               "When thinking through problems, use <thinking>...</thinking> tags to show your reasoning.")
    .model(model)
    .memory(new InMemoryMemory())
    .formatter(new DashScopeChatFormatter())
    .build();

    Msg userMessage = Msg.builder()
        .role(MsgRole.USER)
        .textContent("Hello, please introduce yourself.")
        .build();
    Msg response = agent.reply(userMessage).block();

    System.out.println("Agent Response: " + response.getTextContent());
}
```

### Equip Agent with Tools
1. 定义工具

	定义一个工具类，其中方法被 `@Tool` 注解。这里有一个 `SimpleTools` 类，其中有一个时间工具：

	```java
	public class SimpleTools {
		@Tool(name = "get_time", description = "Get current time string of a time zone")
		public String getTime(@ToolParam(description = "Time zone, e.g., Beijing") String zone) {
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
			.name("hello-world-agent")
			.sysPrompt("You are a helpful AI assistant.")
			.model(model)
			.toolkit(toolkit)
			.memory(new InMemoryMemory())
			.formatter(new DashScopeChatFormatter())
			.build();

		Msg userMessage = Msg.builder()
				.role(MsgRole.USER)
				.textContent("Please tell me the current time.")
				.build();

		Msg response = agent.reply(userMessage).block();
		System.out.println("Agent Response: " + response.getTextContent());
	}
	```
## <font style="color:rgb(31, 35, 40);">📖</font><font style="color:rgb(31, 35, 40);"> 文档</font>
+ [创建消息](./docs/zh_CN/quickstart/message.md)
+ [创建 ReAct Agent](./docs/zh_CN/quickstart/agent.md)
+ [模型](./docs/zh_CN/task/model.md)
+ [工具](./docs/zh_CN/task/tool.md)
+ [MCP](./docs/zh_CN/task/mcp.md)
+ [RAG](./docs/zh_CN/task/rag.md)
+ [记忆 (Memory)](./docs/zh_CN/task/memory.md)
+ 提示格式化器 (Prompt Formatter)

## <font style="color:rgb(31, 35, 40);">🏗️</font><font style="color:rgb(31, 35, 40);">Roadmap </font>
在接下来的版本中，AgentScope Java 版本将专注于改进以下功能。

+ 多模型 (Multi-model)
+ 多智能体 (Multi-Agent)
+ 追踪 (Tracing)
+ AgentScope Studio (图形化界面)

## ⚖️ 许可
AgentScope 基于 Apache License 2.0 发布。
