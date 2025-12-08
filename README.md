<p align="center">
  <img
    src="https://img.alicdn.com/imgextra/i1/O1CN01nTg6w21NqT5qFKH1u_!!6000000001621-55-tps-550-550.svg"
    alt="AgentScope Logo"
    width="200"
  />
</p>

<p align="center">
  <a href="https://java.agentscope.io/">📖 Official Website</a>
  &nbsp;|&nbsp;
  <a href="README_ZH.md">中文主页</a>
</p>

## AgentScope Java

An agent-oriented programming framework for building LLM applications in Java.

AgentScope provides a comprehensive toolkit for creating intelligent agents with tool calling, memory management, multi-agent collaboration, and more.

![](https://img.shields.io/badge/GUI-AgentScope_Studio-blue?logo=look&logoColor=green&color=dark-green)![](https://img.shields.io/badge/license-Apache--2.0-black)

## Why AgentScope?
Easy for beginners, powerful for experts.

+ **Transparent to Developers**: Transparency is our **FIRST principle**. Prompt engineering, API invocation, agent building, workflow orchestration - all visible and controllable. No deep encapsulation or implicit magic.
+ **Realtime Steering**: Native support for realtime interruption and customized handling.
+ **More Agentic**: Support agentic tools management, agentic long-term memory control and agentic RAG, etc.
+ **Model Agnostic**: Programming once, run with all models (DashScope, OpenAI, Anthropic, and more).
+ **LEGO-style Agent Building**: All components are **modular** and **independent**.
+ **Multi-Agent Oriented**: Designed for **multi-agent**, **explicit** message passing and workflow orchestration with Pipeline support.
+ **Reactive Architecture**: Built on Project Reactor for efficient non-blocking async operations.
+ **Multimodal Support**: Native support for vision, audio, and video content processing.
+ **Highly Customizable**: Tools, prompt, agent, workflow, hooks, and visualization - customization is encouraged everywhere.

## 💬 Contact

Welcome to join our community on

| [Discord](https://discord.gg/eYMpfnkG8h)                                                                                         | DingTalk                                                                                                                          |
|----------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| <img src="https://gw.alicdn.com/imgextra/i1/O1CN01hhD1mu1Dd3BWVUvxN_!!6000000000238-2-tps-400-400.png" width="100" height="100"> | <img src="https://img.alicdn.com/imgextra/i1/O1CN01LxzZha1thpIN2cc2E_!!6000000005934-2-tps-497-477.png" width="100" height="100"> |

## Quickstart
### Installation
AgentScope Java requires **JDK 17** or higher.

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.1</version>
</dependency>
```

### Hello AgentScope!
Start with a basic ReActAgent that replies to user queries!

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
```

### Equip Agent with Tools
1. Define Tool

	Define a tool class with methods annotated with `@Tool`. Here's an example `SimpleTools` class with a time tool:

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

## Documentation

### Getting Started
+ [Installation](./docs/en/quickstart/installation.md)
+ [Key Concepts](./docs/en/quickstart/key-concepts.md)
+ [Create ReAct Agent](./docs/en/quickstart/agent.md)

### Core Features
+ [Model Integration](./docs/en/task/model.md)
+ [Tool System](./docs/en/task/tool.md)
+ [Memory Management](./docs/en/task/memory.md)
+ [Hook System](./docs/en/task/hook.md)

### Advanced Features
+ [Multi-Agent Pipeline](./docs/en/task/pipeline.md)
+ [State & Session Management](./docs/en/task/state.md)
+ [Multimodal (Vision/Audio)](./docs/en/task/multimodal.md)
+ [Structured Output](./docs/en/task/structured-output.md)
+ [MCP Integration](./docs/en/task/mcp.md)
+ [RAG](./docs/en/task/rag.md)
+ [Planning](./docs/en/task/plan.md)
+ [AgentScope Studio](./docs/en/task/studio.md)

## Roadmap

In December, we will further release best practices for context management and reinforcement learning based on Trinity-RFT.

On the technical evolution front, we are continuously exploring more efficient and intelligent context engineering and multi-Agent collaboration paradigms, committed to supporting the construction of more powerful AI applications.

Additionally, addressing the "80/20 rule" characteristic of Agent traffic (where the top 20% of Agents handle 80% of traffic), we will fully advance Serverless architecture, achieving millisecond-level cold starts and hybrid deployment to help developers handle high concurrency while significantly reducing deployment costs and improving efficiency.

## 🤝 Contributing

We welcome contributions from the community! Please refer to our [CONTRIBUTING.md](./CONTRIBUTING.md) for guidelines
on how to contribute.

## ⚖️ License

AgentScope is released under Apache License 2.0.

## 📚 Publications

If you find our work helpful for your research or application, please cite our papers.

- [AgentScope 1.0: A Developer-Centric Framework for Building Agentic Applications](https://arxiv.org/abs/2508.16279)

- [AgentScope: A Flexible yet Robust Multi-Agent Platform](https://arxiv.org/abs/2402.14034)

```
@article{agentscope_v1,
    author  = {
        Dawei Gao,
        Zitao Li,
        Yuexiang Xie,
        Weirui Kuang,
        Liuyi Yao,
        Bingchen Qian,
        Zhijian Ma,
        Yue Cui,
        Haohao Luo,
        Shen Li,
        Lu Yi,
        Yi Yu,
        Shiqi He,
        Zhiling Luo,
        Wenmeng Zhou,
        Zhicheng Zhang,
        Xuguang He,
        Ziqian Chen,
        Weikai Liao,
        Farruh Isakulovich Kushnazarov,
        Yaliang Li,
        Bolin Ding,
        Jingren Zhou}
    title   = {AgentScope 1.0: A Developer-Centric Framework for Building Agentic Applications},
    journal = {CoRR},
    volume  = {abs/2508.16279},
    year    = {2025},
}

@article{agentscope,
    author  = {
        Dawei Gao,
        Zitao Li,
        Xuchen Pan,
        Weirui Kuang,
        Zhijian Ma,
        Bingchen Qian,
        Fei Wei,
        Wenhao Zhang,
        Yuexiang Xie,
        Daoyuan Chen,
        Liuyi Yao,
        Hongyi Peng,
        Zeyu Zhang,
        Lin Zhu,
        Chen Cheng,
        Hongzhu Shi,
        Yaliang Li,
        Bolin Ding,
        Jingren Zhou}
    title   = {AgentScope: A Flexible yet Robust Multi-Agent Platform},
    journal = {CoRR},
    volume  = {abs/2402.14034},
    year    = {2024},
}
```

## ✨ Contributors

All thanks to our contributors:

<a href="https://github.com/agentscope-ai/agentscope-java/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=agentscope-ai/agentscope-java&max=999&columns=12&anon=1" />
</a>
