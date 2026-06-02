---
title: "快速开始"
description: "快速上手 AgentScope Java 2.0"
---

## 安装

AgentScope Java 需要 JDK 17 及以上版本，构建工具推荐 Maven 3.9+。

### Maven 依赖

在你的 `pom.xml` 中加入 `agentscope-core` 依赖（替换 `${agentscope.version}` 为最新发布版本号）：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

需要使用内置示例工具（DashScope formatter、SSE/HTTP MCP 客户端等）时，按需追加：

```xml
<!-- DashScope / OpenAI / Anthropic / Gemini / Ollama formatter 与 chat model 已包含在 core 中 -->
<!-- MCP 集成示例需要 MCP SDK 依赖，详见 agentscope-examples/documentation/pom.xml -->
```

## 第一个智能体

下面的示例构建了一个最简智能体：通过 `ModelRegistry` 以字符串 `dashscope:qwen-plus` 解析得到的 DashScope chat model、一个空工具集，以及一个 `ReActAgent` 实例。智能体提供两个调用入口 —— `call` 返回最终消息，`streamEvents` 则以流式方式逐步产出事件，适合展示推理和工具调用的中间过程。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class FirstAgent {
    public static void main(String[] args) {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Friday")
                        .sysPrompt("You are a helpful assistant named Friday.")
                        // 通过 ModelRegistry 解析的字符串形式 —— 自动读取环境变量
                        // DASHSCOPE_API_KEY。切换其他厂商时改用 "openai:gpt-5.5"、
                        // "anthropic:claude-sonnet-4-5"、"gemini:gemini-2.0-flash"
                        // 或 "ollama:llama3" 即可。
                        .model("dashscope:qwen-plus")
                        .toolkit(new Toolkit())
                        .build();

        UserMessage userMsg = new UserMessage("user", "Hello, who are you?");

        // 方式一：等待最终的助手消息。
        Msg replyMsg = agent.call(List.of(userMsg), RuntimeContext.empty()).block();
        // `replyMsg.getContent()` 是一组内容块，可按需检查文本块、工具调用等。
        System.out.println(replyMsg.getTextContent());

        // 方式二：流式获取增量事件（文本片段、工具调用等）。
        agent.streamEvents(new UserMessage("Tell me a fun fact."))
                .doOnNext(event -> {
                    // 根据 `event.getType()` 分发处理 —— 每个分支对应一种事件类型。
                    if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                        // 模型返回的流式文本片段 —— 追加到界面或标准输出。
                        System.out.print(((TextBlockDeltaEvent) event).getDelta());
                    } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                        // 智能体即将调用工具 —— 展示调用信息。
                        ToolCallStartEvent start = (ToolCallStartEvent) event;
                        System.out.println("\n[tool] " + start.getToolName());
                    }
                    // 其他事件：思考块、工具结果、回复结束等。
                })
                .blockLast();
    }
}
```

:::{tip}
运行程序前，需要在环境变量中设置 `DASHSCOPE_API_KEY`。如需切换到其他模型提供商，只需改写模型 id 字符串 —— `openai:gpt-5.5`、`anthropic:claude-sonnet-4-5`、`gemini:gemini-2.0-flash`、`ollama:llama3` —— 并配置对应的环境变量（`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、`GEMINI_API_KEY`）。若需要更精细地控制超时、自定义 endpoint 等参数，仍可通过 `XxxChatModel.builder()` 构造实例并传给 `.model(Model)`。
:::
