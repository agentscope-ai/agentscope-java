# Gemini 完整请求流程

本文以一次启用流式输出的 Gemini 请求为例，展示从用户输入问题到最终回答返回的完整链路。图中的工具调用分支和历史消息回放分支属于同一个请求循环：如果模型请求工具，Agent 执行工具后会带着工具结果再次调用模型。

```{mermaid}
flowchart TD
    U(["用户输入问题"]) --> APP["应用层接收输入"]
    APP --> AGENT["ReActAgent.call(...) / streamEvents(...)"]
    AGENT --> HISTORY["读取会话历史、工具定义和 GenerateOptions"]
    HISTORY --> MODEL["ChatModelBase.stream(...)<br/>-> GeminiChatModel.doStream(...)"]

    subgraph REQUEST["请求准备"]
        MODEL --> BUILDER["创建 GenerateContentConfig.Builder"]
        BUILDER --> FORMAT["formatter.format(messages)"]
        FORMAT --> CONVERT["GeminiMessageConverter.convertMessages(...)"]
        CONVERT --> CONTENT["构造 Gemini Content / Part<br/>Text / Thinking / FunctionCall<br/>恢复 assistant Part 的 thoughtSignature"]
        CONTENT --> TOOLS["formatter.applyTools(...)<br/>必要时设置 ToolChoice"]
        TOOLS --> OPTIONS["GeminiChatFormatter.applyOptions(...)"]
        OPTIONS --> THINKING["按字段创建 ThinkingConfig<br/>thinkingBudget / thinkingLevel / includeThoughts"]
        THINKING --> CONFIG["configBuilder.build()<br/>得到 GenerateContentConfig"]
    end

    CONFIG --> SDK["Google GenAI Client<br/>generateContentStream(...)"]
    SDK --> API(["Gemini API"])

    subgraph RESPONSE["响应处理"]
        API --> STREAM["ResponseStream&lt;GenerateContentResponse&gt;"]
        STREAM --> PARSER["GeminiResponseParser.parseResponse(...)"]
        PARSER --> BLOCKS["ChatResponse 内容块<br/>ThinkingBlock / TextBlock / ToolUseBlock<br/>附带 thoughtSignature metadata"]
        BLOCKS --> ACC["ReasoningContext.processChunk(...)"]
        ACC --> LIVE["实时流式事件 / UI 输出"]
        ACC --> FINAL["ReasoningContext.buildFinalMessage()<br/>聚合 Thinking / Text / ToolUse"]
        FINAL --> STATE["将本轮 AssistantMessage 写入 AgentState / context"]
        STATE --> ROUND{"本轮消息是否包含 ToolUseBlock？"}
    end

    ROUND -->|是| EXEC["执行 ToolUseBlock 对应工具"]
    EXEC --> RESULT["生成 ToolResultBlock"]
    RESULT --> APPEND["将 ToolResultBlock 写入 AgentState / context"]
    APPEND -. 下一轮模型请求 .-> MODEL

    ROUND -->|否| RESULT_MSG["AgentResultEvent / 最终 Msg"]
    RESULT_MSG --> SAVE["doCall 完成后 saveStateToSession(...)"]
    SAVE --> PERSIST{"是否配置跨进程状态持久化？"}
    PERSIST -->|否| ANSWER(["返回最终回答给用户"])
    PERSIST -->|是| JSON["JSON 序列化保存 Msg<br/>byte[] thoughtSignature -> Base64 String"]
    JSON --> ANSWER
    JSON --> RESTORE["下一轮读取并反序列化 Msg"]
    RESTORE -. 下一轮请求 .-> MODEL

    classDef input fill:#e3f2fd,stroke:#1976d2,color:#0d47a1
    classDef core fill:#fff3e0,stroke:#ef6c00,color:#4e342e
    classDef gemini fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef state fill:#f3e5f5,stroke:#7b1fa2,color:#4a148c
    classDef decision fill:#fffde7,stroke:#f9a825,color:#5d4037
    classDef terminal fill:#e0f2f1,stroke:#00796b,color:#004d40

    class U input
    class ANSWER terminal
    class APP,AGENT,HISTORY,MODEL,BUILDER,FORMAT,CONVERT,TOOLS,OPTIONS,THINKING,CONFIG core
    class CONTENT,SDK,API,STREAM,PARSER,BLOCKS gemini
    class ACC,LIVE,FINAL,STATE,EXEC,RESULT,APPEND,RESULT_MSG,SAVE,JSON,RESTORE state
    class ROUND,PERSIST decision
```

图例：蓝色表示用户输入，青绿色表示最终输出，橙色表示 Agent 和请求准备，绿色表示 Gemini/SDK，紫色表示上下文状态，黄色表示分支判断。

## 关键节点与源码

| 流程节点 | 主要实现 | 作用 |
| --- | --- | --- |
| 模型调用入口 | `ChatModelBase.stream` -> `GeminiChatModel.doStream` | 公共 `stream` 入口进入 Gemini 的实际实现，创建请求配置、格式化消息并调用 Google GenAI SDK |
| 请求参数映射 | `GeminiChatFormatter.applyOptions` | 将 `GenerateOptions` 映射为 Gemini `GenerateContentConfig` |
| 消息转换 | `GeminiMessageConverter.convertMessages` | 将 `Msg` 转换为 Gemini `Content` 和 `Part` |
| 响应解析 | `GeminiResponseParser.parseResponse` | 将每个 Gemini 响应 chunk 转成 `ChatResponse` |
| 流式聚合 | `ReasoningContext.processChunk` | 聚合 text、thinking 和 tool call，并生成实时消息 |
| 签名恢复 | `GeminiThoughtSignatureUtils` | 在 `byte[]` 与 JSON 后的 Base64 字符串之间转换 signature |

## 三条结束路径

1. **普通回答**：没有 `ToolUseBlock` 时，Agent 聚合最终消息并把文本返回给用户。
2. **工具调用**：模型返回 `ToolUseBlock`，Agent 执行工具，将 `ToolResultBlock` 加入历史，再发起下一轮模型请求。
3. **跨轮次持久化**：最终 `Msg` 写入 JSON 后，Gemini signature 会变成 Base64 字符串；下一轮转换请求时会恢复成 SDK 要求的 `byte[]`。

## Thinking 与 signature 在图中的位置

- `thinkingBudget`、`thinkingLevel` 和 `includeThoughts` 在请求准备阶段进入 `ThinkingConfig`。
- Gemini 返回的 thinking Part 会解析为 `ThinkingBlock`，普通文本 Part 会解析为 `TextBlock`，function-call Part 会解析为 `ToolUseBlock`。
- `thoughtSignature` 是 Part 级别的 metadata，不只存在于 tool call；解析、流式聚合和 JSON 往返都必须保留它。
- 历史 assistant 的 `ThinkingBlock`、带 signature 的 `TextBlock` 和 `ToolUseBlock` 会在下一轮请求中重新生成对应的 Gemini Part。

多 Agent formatter 会将普通多 Agent 历史重写成带标签的 user 文本，因此不能把原始 signature 附加到被重写的文本上；精确的 tool sequence 仍走同一套 Part 回放逻辑。
