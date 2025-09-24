<p align="center">
  <img
    src="https://img.alicdn.com/imgextra/i1/O1CN01nTg6w21NqT5qFKH1u_!!6000000001621-55-tps-550-550.svg"
    alt="AgentScope Logo"
    width="200"
  />
</p>

## AgentScope Implementation for Java
<font style="color:rgb(31, 35, 40);">This is the Java implementation of </font>[<font style="color:rgb(9, 105, 218);">AgentScope</font>](https://github.com/agentscope-ai/agentscope/)<font style="color:rgb(31, 35, 40);">. Please note that this project is still experimental and under active development.</font>


![](https://img.shields.io/badge/GUI-AgentScope_Studio-blue?logo=look&logoColor=green&color=dark-green)![](https://img.shields.io/badge/license-Apache--2.0-black)

## ✨ Why AgentScope?
Easy for beginners, powerful for experts.

+ **Transparent to Developers**: Transparent is our **FIRST principle**. Prompt engineering, API invocation, agent building, workflow orchestration, all are visible and controllable for developers. No deep encapsulation or implicit magic.
+ Realtime Steering: Native support for realtime interruption and customized handling.
+ **More Agentic**: Support agentic tools management, agentic long-term memory control and agentic RAG, etc.
+ **Model Agnostic**: Programming once, run with all models.
+ **LEGO-style Agent Building**: All components are **modular** and **independent**.
+ **Multi-Agent Oriented**: Designed for **multi-agent**, **explicit** message passing and workflow orchestration, NO deep encapsulation.
+ **Highly Customizable**: Tools, prompt, agent, workflow, third-party libs & visualization, customization is encouraged everywhere.

## 🚀 Quickstart
### Installation
AgentScope Java requires **jdk 17** or higher.

```bash
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Hello AgentScope!
Start with a basic ReActAgent that replies to user queries!

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
1. Define Tool

	Define a tool class with methods annotated with `@Tool`. Here's an example `SimpleTools` class with a time tool:

	```java
	public class SimpleTools {
		@Tool(name = "get_time", description = "Get current time string of a time zone")
		public String getTime(@ToolParam(description = "Time zone, e.g., Beijing") String zone) {
			LocalDateTime now = LocalDateTime.now();
			return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		}
	}
	```

2. Register Tool to ReActAgent

	Register the tool class through `Toolkit` using the `registerTool` method:

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
## <font style="color:rgb(31, 35, 40);">📖</font><font style="color:rgb(31, 35, 40);"> Documentation</font>
+ [Create Message](./docs/quickstart-message.md)
+ [Create ReAct Agent](./docs/quickstart-agent.md)
+ Model
+ Tool
+ Memory
+ Prompt Formatter

## <font style="color:rgb(31, 35, 40);">🏗️</font><font style="color:rgb(31, 35, 40);"> </font>Roadmap
In the upcoming versions, AgentScope Java version will focus on improving the following features.

+ Multi-modal
+ Multi-Agent
+ Tracing
+ AgentScope Studio

## ⚖️ License
AgentScope is released under Apache License 2.0.
