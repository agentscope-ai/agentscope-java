# AgentScope Java → DashScope API 数据转换详解

> 本文档详细描述了 `DashScopeChatModel.stream()` 调用过程中，AgentScope 的 `List<Msg>` 与 DashScope API 原始 JSON 之间的双向转换映射，覆盖所有 role 类型。

---

## 目录

1. [架构概览](#1-架构概览)
2. [请求转换：Msg → JSON](#2-请求转换msg--json)
   - [2.1 SYSTEM role](#21-system-role)
   - [2.2 USER role (文本)](#22-user-role-文本)
   - [2.3 USER role (多模态)](#23-user-role-多模态)
   - [2.4 ASSISTANT role (纯文本)](#24-assistant-role-纯文本)
   - [2.5 ASSISTANT role (带 tool_calls)](#25-assistant-role-带-tool_calls)
   - [2.6 TOOL role](#26-tool-role)
3. [响应转换：JSON → ChatResponse](#3-响应转换json--chatresponse)
   - [3.1 纯文本响应](#31-纯文本响应)
   - [3.2 带 thinking 的响应](#32-带-thinking-的响应)
   - [3.3 Tool Call 响应](#33-tool-call-响应)
   - [3.4 Tool Call + Thinking 响应](#34-tool-call--thinking-响应)
   - [3.5 流式增量响应](#35-流式增量响应)
   - [3.6 错误响应](#36-错误响应)
4. [完整转换链路（源码追踪）](#4-完整转换链路源码追踪)
5. [DTO 类结构速查](#5-dto-类结构速查)

---

## 1. 架构概览

整个转换链路的**顶级 orchestrator**是 `DashScopeChatModel.streamWithHttpClient()`
（`DashScopeChatModel.java:222`）。它负责编排消息格式化、请求构建、HTTP 调用和响
应解析的完整流程。

```
DashScopeChatModel.streamWithHttpClient(messages, tools, options)
│
│  ┌──────────────────────────────────────────────────────────────┐
│  │ ① 消息格式化 (两个分支)                                        │
│  │                                                              │
│  │  useMultimodal ?                                             │
│  │    │                                                         │
│  │    ├─ YES (VL 模型: qvq*, *-vl*, 或 endpointType=MULTIMODAL) │
│  │    │   └─ formatter.formatMultiModal(messages)               │
│  │    │       └─ DashScopeChatFormatter.formatMultiModal()      │
│  │    │           └─ 每条消息直接 convertToMessage(msg, true)    │
│  │    │              (ALL messages use multimodal content format)│
│  │    │                                                         │
│  │    └─ NO  (纯文本模型)                                        │
│  │        └─ formatter.format(messages)                         │
│  │            └─ AbstractBaseFormatter.format()                 │
│  │                └─ doFormat()  ← tracer 包装后调用             │
│  │                    └─ 逐条 hasMediaContent(msg) 判断          │
│  │                        ├─ true  → convertToMessage(msg,true) │
│  │                        └─ false → convertToMessage(msg,false)│
│  └──────────────────────────────────────────────────────────────┘
│    │
│    ▼  List<DashScopeMessage>
│
│  ┌──────────────────────────────────────────────────────────────┐
│  │ ② 请求构建                                                    │
│  │                                                              │
│  │  chatFormatter.buildRequest(model, msgs, stream,             │
│  │      options, defaultOptions, tools, toolChoice)             │
│  │    │                                                         │
│  │    ├─ buildRequest(model, msgs, stream)                      │
│  │    │   └─ DashScopeRequest {                                 │
│  │    │        model: "qwen-plus",                              │
│  │    │        input:  { messages: [...] },                     │
│  │    │        parameters: {                                    │
│  │    │          result_format: "message",                      │
│  │    │          incremental_output: stream                     │
│  │    │        }                                                │
│  │    │      }                                                  │
│  │    │                                                         │
│  │    ├─ applyOptions(request, options, defaultOptions)         │
│  │    │   └─ 注入 temperature, top_p, max_tokens 等             │
│  │    │                                                         │
│  │    ├─ applyTools(request, tools)                             │
│  │    │   └─ ToolSchema 列表 → DashScopeTool 列表               │
│  │    │                                                         │
│  │    └─ applyToolChoice(request, toolChoice)                   │
│  │        └─ Auto → "auto", None → "none", Specific → {type,fn} │
│  └──────────────────────────────────────────────────────────────┘
│    │
│  ┌──────────────────────────────────────────────────────────────┐
│  │ ③ 可选参数注入 (applyThinkingMode + applyCacheControl)        │
│  │                                                              │
│  │  enableThinking? → params.enable_thinking = true             │
│  │  cache_control?  → system msgs + last msg 加 cache_control   │
│  └──────────────────────────────────────────────────────────────┘
│    │
│    ▼  DashScopeRequest (完整)
│
│  ┌──────────────────────────────────────────────────────────────┐
│  │ ④ HTTP 调用                                                  │
│  │                                                              │
│  │  stream=true                                                 │
│  │    └─ httpClient.stream(request) → Flux<DashScopeResponse>   │
│  │         ↓ 每行 SSE data 由 Jackson 反序列化                   │
│  │  stream=false                                                │
│  │    └─ httpClient.call(request) → DashScopeResponse           │
│  └──────────────────────────────────────────────────────────────┘
│    │
│    ▼  Flux<DashScopeResponse>
│
│  ┌──────────────────────────────────────────────────────────────┐
│  │ ⑤ 响应解析                                                    │
│  │                                                              │
│  │  .map(response -> formatter.parseResponse(response, start))   │
│  │    └─ DashScopeResponseParser.parseResponse()                │
│  │        ├─ output.choices[0].message.reasoning_content        │
│  │        │   → ThinkingBlock                                   │
│  │        ├─ output.choices[0].message.content                  │
│  │        │   → TextBlock                                       │
│  │        ├─ output.choices[0].message.tool_calls               │
│  │        │   → ToolUseBlock (每个)                              │
│  │        └─ usage → ChatUsage                                  │
│  └──────────────────────────────────────────────────────────────┘
│    │
│    ▼  Flux<ChatResponse>
│    │
│    ▼  ReActAgent 消费 → 累积 ContentBlock → 构建 Msg(ASSISTANT)
```

**核心组件职责：**

| 组件 | 位置 | 职责 |
|------|------|------|
| `DashScopeChatModel` | `model/DashScopeChatModel.java` | **orchestrator**：编排整个 `streamWithHttpClient()` 流程 |
| `AbstractBaseFormatter` | `formatter/AbstractBaseFormatter.java` | `format()` 入口（含 tracer 包装），`hasMediaContent()`，`extractTextContent()` |
| `DashScopeChatFormatter` | `formatter/dashscope/DashScopeChatFormatter.java` | `doFormat()` 逐条转换，`formatMultiModal()` 全量多元转换，`buildRequest()` |
| `DashScopeMultiAgentFormatter` | `formatter/dashscope/DashScopeMultiAgentFormatter.java` | 多 Agent 场景：会话历史合并为单条 user 消息 |
| `DashScopeMessageConverter` | `formatter/dashscope/DashScopeMessageConverter.java` | **单条** `Msg` → `DashScopeMessage`（核心转换逻辑） |
| `DashScopeMediaConverter` | `formatter/dashscope/DashScopeMediaConverter.java` | `ImageBlock/VideoBlock/AudioBlock` → URL 字符串 |
| `DashScopeToolsHelper` | `formatter/dashscope/DashScopeToolsHelper.java` | Tool schema/tool_calls/tool_choice 转换 |
| `DashScopeResponseParser` | `formatter/dashscope/DashScopeResponseParser.java` | `DashScopeResponse` → `ChatResponse`（ContentBlock 拼装） |
| `DashScopeHttpClient` | `model/DashScopeHttpClient.java` | HTTP 调用：SSE 流解析 or 单次请求 |

**两条消息格式化路径的对比：**

| 路径 | 入口 | content 格式 | 何时使用 |
|------|------|-------------|---------|
| `format()` → `doFormat()` | `AbstractBaseFormatter.format()` | 逐条判断：`hasMedia=true` → multimodal / `false` → simple | 纯文本模型 |
| `formatMultiModal()` | `DashScopeChatFormatter.formatMultiModal()` | 全部强制 multimodal (`content` = `List<DashScopeContentPart>`) | VL 模型（`qvq*`, `*-vl*`，或 `EndpointType.MULTIMODAL`） |

**注意：** `DashScopeChatFormatter` 同时支持两种 formatter：单 Agent 用
`DashScopeChatFormatter`，多 Agent 用 `DashScopeMultiAgentFormatter`（后者会将多个
Agent 的对话历史合并压缩成单条 user 消息）。单 Agent 场景（绝大多数情况）下 flow 如
上图所示。

---

## 2. 请求转换：Msg → JSON

### Msg 到 DashScopeMessage 的 role 映射

| MsgRole | DashScopeMessage.role |
|---------|----------------------|
| `SYSTEM` | `"system"` |
| `USER` | `"user"` |
| `ASSISTANT` | `"assistant"` |
| `TOOL` | `"tool"` |

---
### 2.1 SYSTEM role

**AgentScope Msg：**

```java
Msg msg = Msg.builder()
    .role(MsgRole.SYSTEM)
    .content(TextBlock.builder().text("你是一个helpful assistant").build())
    .build();
```

**转换逻辑 `convertToSimpleContent()`** (`DashScopeMessageConverter.java:202-236`)：

```java
// 非 ASSISTANT 角色走 else 分支
builder.role(msg.getRole().name().toLowerCase());  // → "system"
builder.content(extractTextContent(msg));           // → "你是一个helpful assistant"
```

其中 `extractTextContent()` 将所有 `TextBlock` 用 `\n` 拼接：

```java
private String extractTextContent(Msg msg) {
    return msg.getContent().stream()
            .filter(block -> block instanceof TextBlock)
            .map(block -> ((TextBlock) block).getText())
            .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
}
```

**最终 JSON（simple 模式）：**

```json
{
  "role": "system",
  "content": "你是一个helpful assistant"
}
```

如果启用了 `cache_control`（`DashScopeChatFormatter.applyCacheControl()`），所有 system 消息会被自动加上 `cache_control`：

```json
{
  "role": "system",
  "content": "你是一个helpful assistant",
  "cache_control": {"type": "ephemeral"}
}
```

**SYSTEM role 的特殊情况：** 如果 SYSTEM 消息中包含 `ToolResultBlock`（极少见），在 simple 模式下会被当作 TOOL role 处理：

```java
// DashScopeMessageConverter.convertToSimpleContent() line 204-213
ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
if (toolResult != null && (msg.getRole() == MsgRole.TOOL || msg.getRole() == MsgRole.SYSTEM)) {
    return DashScopeMessage.builder()
            .role("tool")
            .toolCallId(toolResult.getId())
            .name(toolResult.getName())
            .content(toolResultConverter.apply(toolResult.getOutput()))
            .build();
}
```

---
### 2.2 USER role (文本)

**AgentScope Msg：**

```java
Msg msg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("你好，今天天气怎么样？").build())
    .build();
```

**转换后 JSON（simple 模式）：**

```json
{
  "role": "user",
  "content": "你好，今天天气怎么样？"
}
```

**多条 TextBlock 的情况：**

```java
Msg msg = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("第一段文字").build(),
        TextBlock.builder().text("第二段文字").build()
    ))
    .build();
```

转换逻辑将两个 TextBlock 用 `\n` 连接：

```json
{
  "role": "user",
  "content": "第一段文字\n第二段文字"
}
```

---
### 2.3 USER role (多模态)

当消息包含 `ImageBlock`、`VideoBlock` 或 `AudioBlock` 时，`hasMediaContent(msg)` 返回 `true`，走 `convertToMultimodalContent()` 路径。

**AgentScope Msg：**

```java
Msg msg = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("这张图片里有什么？").build(),
        ImageBlock.builder()
            .source(URLSource.builder().url("https://example.com/cat.jpg").build())
            .build()
    ))
    .build();
```

**转换流程：**

```
1. TextBlock("这张图片里有什么？")
   → DashScopeContentPart.text("这张图片里有什么？")
   → {"text": "这张图片里有什么？"}

2. ImageBlock(url="https://example.com/cat.jpg")
   → mediaConverter.convertImageBlockToContentPart(imageBlock)
   → DashScopeContentPart.builder().image("https://example.com/cat.jpg").build()
   → {"image": "https://example.com/cat.jpg"}
```

**最终 JSON（multimodal 模式）：**

```json
{
  "role": "user",
  "content": [
    {"text": "这张图片里有什么？"},
    {"image": "https://example.com/cat.jpg"}
  ]
}
```

**ImageBlock 的三种 Source 类型转换：**

| Source 类型 | 转换结果 | JSON 示例 |
|------------|---------|----------|
| `URLSource(url="https://...")` | `file://` 协议转换或直接 URL | `{"image": "file:///abs/path/img.png"}` 或直传 |
| `Base64Source(mediaType, data)` | data URI | `{"image": "data:image/png;base64,iVBOR..."}` |
| 其他 | 抛出 `IllegalArgumentException` | — |

**VideoBlock 完整转换：**

```java
// AgentScope
VideoBlock video = VideoBlock.builder()
    .source(URLSource.builder().url("https://example.com/demo.mp4").build())
    .fps(2.0f)
    .maxFrames(10)
    .minPixels(256)
    .maxPixels(2048)
    .totalPixels(4096)
    .build();
```

```json
{
  "role": "user",
  "content": [
    {"text": "分析这个视频"},
    {
      "video": "https://example.com/demo.mp4",
      "fps": 2.0,
      "max_frames": 10,
      "min_pixels": 256,
      "max_pixels": 2048,
      "total_pixels": 4096
    }
  ]
}
```

**AudioBlock 转换：**

```java
AudioBlock audio = AudioBlock.builder()
    .source(URLSource.builder().url("https://example.com/speech.wav").build())
    .build();
```

```json
{
  "role": "user",
  "content": [
    {"text": "转写这段音频"},
    {"audio": "https://example.com/speech.wav"}
  ]
}
```

**转换中 ContentBlock 的处理规则（multimodal 模式）：**

| ContentBlock 类型 | 处理方式 | 来源方法 |
|------------------|---------|---------|
| `TextBlock` | → `DashScopeContentPart.text(text)` | `convertToMultimodalContent()` |
| `ImageBlock` | → `DashScopeContentPart` (image + minPixels + maxPixels) | `DashScopeMediaConverter.convertImageBlockToContentPart()` |
| `VideoBlock` | → `DashScopeContentPart` (video + fps + maxFrames + minPixels + maxPixels + totalPixels) | `DashScopeMediaConverter.convertVideoBlockToContentPart()` |
| `AudioBlock` | → `DashScopeContentPart` (audio) | `DashScopeMediaConverter.convertAudioBlockToContentPart()` |
| `ThinkingBlock` | **跳过**（日志 debug） | — |
| `ToolResultBlock` | → text 内容（通过 `toolResultConverter`） | — |

---
### 2.4 ASSISTANT role (纯文本)

**AgentScope Msg：**

```java
Msg msg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(TextBlock.builder().text("今天天气很好，适合出门。").build())
    .build();
```

**转换逻辑 `convertToSimpleContent()`：**

```java
if (msg.getRole() == MsgRole.ASSISTANT) {
    List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
    if (!toolBlocks.isEmpty()) {
        // Assistant with tool calls（见 2.5 节）
    } else {
        builder.content(extractTextContent(msg));  // → "今天天气很好，适合出门。"
    }
}
```

**最终 JSON：**

```json
{
  "role": "assistant",
  "content": "今天天气很好，适合出门。"
}
```

**关于 ThinkingBlock：** 在请求转换中，`ThinkingBlock` **总是被跳过**的。ASSISTANT 消息中的 `reasoning_content` 是 DashScope API 的响应字段，在下一轮作为上下文回传时，`ThinkingBlock` 不参与请求构建。

关键代码在 `convertToSimpleContent()` 中，`extractTextContent()` 只提取 `TextBlock`，天然过滤了 `ThinkingBlock`。

---
### 2.5 ASSISTANT role (带 tool_calls)

这是 ReAct Agent 循环中最关键的转换。

**AgentScope Msg：**

```java
Msg msg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(List.of(
        TextBlock.builder().text("我来帮你查天气。").build(),
        ToolUseBlock.builder()
            .id("call_abc123")
            .name("get_weather")
            .input(Map.of("location", "Beijing"))
            .build()
    ))
    .build();
```

**转换逻辑：**

```java
if (msg.getRole() == MsgRole.ASSISTANT) {
    List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
    if (!toolBlocks.isEmpty()) {
        // 1. tool_calls 字段
        builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
        // 2. content 字段
        String textContent = extractTextContent(msg);
        builder.content(textContent.isEmpty() ? "" : textContent);
    }
}
```

**`convertToolCalls()` 逻辑** (`DashScopeToolsHelper.java:233-259`)：

```java
for (ToolUseBlock toolUse : toolBlocks) {
    String argsJson = JsonUtils.resolveToolCallArgsJson(toolUse);
    DashScopeFunction function = DashScopeFunction.of(toolUse.getName(), argsJson);
    DashScopeToolCall toolCall = DashScopeToolCall.builder()
            .id(toolUse.getId())
            .type("function")
            .function(function)
            .build();
    result.add(toolCall);
}
```

**最终 JSON：**

```json
{
  "role": "assistant",
  "content": "我来帮你查天气。",
  "tool_calls": [
    {
      "id": "call_abc123",
      "type": "function",
      "function": {
        "name": "get_weather",
        "arguments": "{\"location\":\"Beijing\"}"
      }
    }
  ]
}
```

**特殊情况：只有 tool_calls 没有文本（Qwen3 thinking 模式）：**

```
content = ""  （空字符串而非省略！DashScope API 要求 content 字段必须存在）
tool_calls = [...]  （正常）
```

注意代码中这行注释和逻辑：
```java
// Qwen3 and similar models in thinking mode may produce assistant
// messages with reasoning_content + tool_calls but null content.
// DashScope API requires the content field to be present.
builder.content(textContent.isEmpty() ? "" : textContent);
```

---
### 2.6 TOOL role

**AgentScope Msg：**

```java
Msg msg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(ToolResultBlock.builder()
        .id("call_abc123")
        .name("get_weather")
        .output(List.of(
            TextBlock.builder().text("北京今天晴天，25°C").build()
        ))
        .build())
    .build();
```

**转换逻辑 `convertToolRoleMessage()`** (multimodal) 或 `convertToSimpleContent()` (simple)：

```java
// simple 模式
ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
if (toolResult != null) {
    return DashScopeMessage.builder()
            .role("tool")
            .toolCallId(toolResult.getId())   // → "call_abc123"
            .name(toolResult.getName())        // → "get_weather"
            .content(toolResultConverter.apply(toolResult.getOutput()))
            .build();
}
```

**最终 JSON：**

```json
{
  "role": "tool",
  "content": "北京今天晴天，25°C",
  "tool_call_id": "call_abc123",
  "name": "get_weather"
}
```

**TOOL role 的四个字段说明：**

| 字段 | 来源 | 必填 | 说明 |
|------|------|------|------|
| `role` | 固定值 `"tool"` | 是 | — |
| `content` | `toolResultConverter(output)` | 是 | 工具执行结果文本 |
| `tool_call_id` | `ToolResultBlock.id` | 是 | 与对应的 `ToolUseBlock.id` 一致 |
| `name` | `ToolResultBlock.name` | 否 | 工具名称 |

**TOOL 消息的 output 中有多媒体内容的场景：**

如果 tool 返回了图片/音频，`convertToolRoleMessage()` 会递归转换 output 里的 content blocks：

```java
List<DashScopeContentPart> content =
    hasMediaContent(toolResult.getOutput())
        ? convertContentBlocks(toolResult.getOutput())   // 转成 multimodal 格式
        : List.of(DashScopeContentPart.text(toolResultText));
```

---

## 3. 响应转换：JSON → ChatResponse

DashScope API 返回的 JSON 首先被 Jackson 反序列化为 `DashScopeResponse`，然后由 `DashScopeResponseParser.parseResponse()` 转换为 AgentScope 的 `ChatResponse`。

**ContentBlock 组装顺序（固定不变）：**

```
1. ThinkingBlock (reasoning_content)  — 思考内容最先
2. TextBlock (content)                — 文本居中
3. ToolUseBlock (tool_calls)          — 工具调用最后
```

源码位置 `DashScopeResponseParser.java:70-87`：

```java
// Order matters! Follow this processing order:
// 1. ThinkingBlock first (reasoning_content)
// 2. Then TextBlock (content)
// 3. Finally ToolUseBlock (tool_calls)

String reasoningContent = message.getReasoningContent();
if (reasoningContent != null && !reasoningContent.isEmpty()) {
    blocks.add(ThinkingBlock.builder().thinking(reasoningContent).build());
}

String content = message.getContentAsString();
if (content != null && !content.isEmpty()) {
    blocks.add(TextBlock.builder().text(content).build());
}

addToolCallsFromMessage(message, blocks);
```

---
### 3.1 纯文本响应

**DashScope API 返回 JSON：**

```json
{
  "request_id": "req-xxx-xxx",
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "你好！有什么我可以帮助你的吗？"
        },
        "finish_reason": "stop"
      }
    ]
  },
  "usage": {
    "input_tokens": 25,
    "output_tokens": 15
  }
}
```

**解析后 ChatResponse：**

```
ChatResponse {
  id: "req-xxx-xxx"
  content: [
    TextBlock { text: "你好！有什么我可以帮助你的吗？" }
  ]
  finishReason: "stop"
  usage: ChatUsage { inputTokens: 25, outputTokens: 15, time: 1.23 }
}
```

**ReActAgent 构建的 Msg：**

```java
Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(List.of(TextBlock.builder().text("你好！有什么我可以帮助你的吗？").build()))
    .build();
```

---
### 3.2 带 thinking 的响应

当启用了 thinking 模式（`enable_thinking: true`），模型返回 `reasoning_content` 字段。

**DashScope API 返回 JSON：**

```json
{
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "这个问题的答案是42。",
          "reasoning_content": "用户问了一个经典问题，我需要思考...\n最终答案是42。"
        },
        "finish_reason": "stop"
      }
    ]
  },
  "usage": {
    "input_tokens": 10,
    "output_tokens": 50
  }
}
```

**解析后 ChatResponse：**

```
ChatResponse {
  content: [
    ThinkingBlock { thinking: "用户问了一个经典问题，我需要思考...\n最终答案是42。" },
    TextBlock    { text: "这个问题的答案是42。" }
  ]
  finishReason: "stop"
}
```

**对应 MSSAGE hook 事件中的 Msg：**

```java
Msg {
  role: ASSISTANT
  content: [
    ThinkingBlock { thinking: "用户问了一个经典问题..." },
    TextBlock    { text: "这个问题的答案是42。" }
  ]
}
```

**DashScopeMessage 中 reasoning_content 的 Jackson 映射：**

```java
@JsonProperty("reasoning_content")
@JsonAlias("reasoning")        // 兼容字段名
private String reasoningContent;
```

注意：响应中 `reasoning_content` 和 `content` 是**并列字段**，均在 message 对象下：

```json
{
  "message": {
    "role": "assistant",
    "reasoning_content": "思考过程...",
    "content": "最终回答..."
  }
}
```

---
### 3.3 Tool Call 响应

**DashScope API 返回 JSON：**

```json
{
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "",
          "tool_calls": [
            {
              "id": "call_abc123",
              "type": "function",
              "function": {
                "name": "get_weather",
                "arguments": "{\"location\":\"Beijing\"}"
              }
            }
          ]
        },
        "finish_reason": "tool_calls"
      }
    ]
  }
}
```

**`addToolCallsFromMessage()` 转换逻辑：**

```java
for (DashScopeToolCall toolCall : toolCalls) {
    String id = toolCall.getId();
    DashScopeFunction function = toolCall.getFunction();
    String name = function.getName();
    String argsJson = function.getArguments();

    if (name != null && !name.trim().isEmpty()) {
        // 完整工具调用块
        blocks.add(ToolUseBlock.builder()
                .id(id != null ? id : "tool_call_" + timestamp + "_" + idx)
                .name(name)
                .input(Map.of())     // ← input 总是空 Map
                .content(argsJson)   // ← JSON 参数存入 content 字段
                .build());
    }
}
```

**解析后 ChatResponse：**

```
ChatResponse {
  content: [
    TextBlock   { text: "" },                                      // ← 空字符串也会生成（如果 content 非空）
    ToolUseBlock { id: "call_abc123", name: "get_weather",
                   input: {}, content: "{\"location\":\"Beijing\"}" }
  ]
  finishReason: "tool_calls"
}
```

**关键点：**
- `ToolUseBlock.input` 总是空 `Map.of()` — DashScope 格式中参数是 JSON 字符串，由上游 `ModelUtils.convertResponseToMsg()` 或 ReActAgent 负责解析填入 `input`
- `ToolUseBlock.content` 存放原始 arguments JSON 字符串
- `finish_reason: "tool_calls"` 表示模型在等待工具执行结果

---
### 3.4 Tool Call + Thinking 响应

当 thinking 模式下模型执行工具调用：

**DashScope API 返回 JSON：**

```json
{
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "我来查询天气。",
          "reasoning_content": "用户想知道天气，我需要调用get_weather工具。",
          "tool_calls": [
            {
              "id": "call_xyz",
              "type": "function",
              "function": {
                "name": "get_weather",
                "arguments": "{\"location\":\"Beijing\"}"
              }
            }
          ]
        },
        "finish_reason": "tool_calls"
      }
    ]
  }
}
```

**解析后 ChatResponse（三个 ContentBlock 按顺序排列）：**

```
ChatResponse {
  content: [
    ThinkingBlock { thinking: "用户想知道天气，我需要调用get_weather工具。" },
    TextBlock     { text: "我来查询天气。" },
    ToolUseBlock  { id: "call_xyz", name: "get_weather",
                    input: {}, content: "{\"location\":\"Beijing\"}" }
  ]
  finishReason: "tool_calls"
}
```

---
### 3.5 流式增量响应

在 SSE 流模式下（`incremental_output: true`，来自千问 DashScope API），服务端逐块返回增量
数据。每个 chunk 是一段独立的 JSON，`DashScopeResponseParser` **对每个 chunk 独立解析**，
不依赖 chunk 之间的顺序关系：

```java
// DashScopeResponseParser.parseResponse() — 每个 chunk 独立执行
String reasoningContent = message.getReasoningContent();  // 有 → ThinkingBlock
String content = message.getContentAsString();            // 有 → TextBlock
addToolCallsFromMessage(message, blocks);                 // 有 → ToolUseBlock
```

**每个 chunk 三个字段各自独立判断，有值就添加对应的 Block，无值就跳过：**

| chunk 场景 | `reasoning_content` | `content` | `tool_calls` |
|-----------|--------------------|-----------|-------------|
| thinking 中 | 增量文本 | null/"" | null |
| 文本输出中 | null/"" | 增量文本 | null |
| 文本+tools | null/"" | "我来查天气" | [...] |
| 完成信号 | null/"" | "" | null（带 finish_reason） |

**典型 thinking 模式 chunk 示例：**

```json
{
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "reasoning_content": "让"
        },
        "finish_reason": null
      }
    ]
  }
}
```

**典型文本增量 chunk 示例：**

```json
{
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "今天天气"
        },
        "finish_reason": null
      }
    ]
  }
}
```

**Tool call 第一个 chunk（带 name 和 id）：**

```json
{
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "",
          "tool_calls": [
            {
              "id": "call_abc123",
              "type": "function",
              "function": {
                "name": "get_weather",
                "arguments": "{\"location\":"
              }
            }
          ]
        },
        "finish_reason": null
      }
    ]
  }
}
```

**Tool call 后续 chunk（只有 arguments fragment）：**

```json
{
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "tool_calls": [
            {
              "type": "function",
              "function": {
                "arguments": "\"Beijing\"}"
              }
            }
          ]
        },
        "finish_reason": null
      }
    ]
  }
}
```

**`addToolCallsFromMessage()` 对流式 ToolCall 的处理：**

```java
// 第一个 chunk (有 name)
if (name != null && !name.trim().isEmpty()) {
    blocks.add(ToolUseBlock.builder()
            .id(callId).name(name).input(Map.of()).content(argsJson).build());
}
// 后续 chunk (只有 arguments, 没有 name)
else if (argsJson != null) {
    blocks.add(ToolUseBlock.builder()
            .id(callId)
            .name("__fragment__")      // ← 标记为片段
            .input(Map.of())
            .content(argsJson)
            .build());
}
```

**最后一个 chunk（完成信号）：**

```json
{
  "output": {
    "choices": [
      {
        "finish_reason": "stop"
      }
    ]
  }
}
```

**ReActAgent 中的处理：** ReActAgent 在收到流式 hook 事件后，通过 `HookEvent.getMessage()` 获取每个增量块对应的 `Msg`，然后在 `reasoning()` 方法中**累积拼接**所有 `ThinkingBlock` 和 `TextBlock` 和 `ToolUseBlock`，形成完整的最终响应。

---
### 3.6 错误响应

**DashScope API 错误 JSON：**

```json
{
  "request_id": "req-xxx",
  "code": "InvalidParameter",
  "message": "Model not found: qwen-nonexistent"
}
```

**DashScopeResponse 解析：**

```
DashScopeResponse {
  requestId: "req-xxx"
  output: null
  usage: null
  code: "InvalidParameter"
  message: "Model not found: qwen-nonexistent"
}
```

`output` 为 null 时，`DashScopeResponseParser.parseResponse()` 返回一个 content 为空的 `ChatResponse`，`finishReason` 也为 null。

实际的错误处理在 `DashScopeHttpClient` 层，检查 `response.isError()` 后抛出异常：

```java
public boolean isError() {
    return code != null && !code.isEmpty();
}
```

---

## 4. 完整转换链路（源码追踪）

以下是从 `ReActAgent` 到 DashScope API 的完整调用链，所有行号基于当前源码。

```
ReActAgent.reasoning()
  │
  ├─ modelInput = buildModelInput(memory.getMessages())
  │   └─ List<Msg> (含 SYSTEM + 历史 USER/ASSISTANT/TOOL)
  │
  └─ model.stream(modelInput, toolkit.getToolSchemas(), options)
      │
      ▼
DashScopeChatModel.doStream(messages, tools, options)
  │                                                       // :198-215
  └─ streamWithHttpClient(messages, tools, options)       // :222-318
      │
      ├─ [第1步] 消息格式化 (两个分支)
      │   │
      │   ├─ useMultimodal=true (VL 模型)
      │   │   └─ chatFmt.formatMultiModal(messages)       // :235
      │   │       └─ messageConverter.convertToMessage(msg, true)
      │   │           └─ convertToMultimodalContent(msg)   // ALL multimodal
      │   │
      │   └─ useMultimodal=false (纯文本模型)
      │       └─ formatter.format(messages)               // :245
      │           └─ AbstractBaseFormatter.format()       // 含 tracer 包装
      │               └─ DashScopeChatFormatter.doFormat() // :58-68
      │                   └─ 逐条 hasMediaContent(msg) 判断
      │                       ├─ true → convertToMessage(msg, true)
      │                       │         → convertToMultimodalContent()
      │                       └─ false → convertToMessage(msg, false)
      │                                  → convertToSimpleContent()
      │
      ├─ [第2步] 构建 DashScopeRequest
      │   │
      │   ├─ chatFormatter.buildRequest(                   // :251-259
      │   │       model, msgs, stream, options,
      │   │       defaultOptions, tools, toolChoice)
      │   │   ├─ buildRequest(model, msgs, stream)        // :133-143
      │   │   │   └─ DashScopeRequest { model, input, params }
      │   │   ├─ applyOptions(params, options, defaultOpts)
      │   │   │   └─ 注入 temperature, top_p, max_tokens 等
      │   │   ├─ applyTools(params, tools)
      │   │   │   └─ ToolSchema → DashScopeTool 转换
      │   │   └─ applyToolChoice(params, toolChoice)
      │   │       └─ Auto/"auto", None/"none", Specific/{type,fn}
      │   │
      │   └─ (或 MultiAgentFormatter 分支，类似逻辑)       // :260-269
      │
      ├─ [第3步] applyThinkingMode(request, effectiveOpts) // :272
      │   └─ enableThinking → params.enable_thinking
      │       thinkingBudget → params.thinking_budget
      │
      ├─ [第4步] applyCacheControl (可选)                   // :275-281
      │   └─ system msgs + last msg 注入 cache_control
      │
      ├─ [第5步] request.setEndpointType(endpointType)     // :284
      │
      ├─ [第6步] HTTP 调用                                  // :286-317
      │   ├─ stream=true
      │   │   └─ httpClient.stream(request, headers, body, query)
      │   │       └─ Flux<DashScopeResponse> (SSE 逐行解析)
      │   │
      │   └─ stream=false
      │       └─ httpClient.call(request, ...)
      │           └─ DashScopeResponse (单次 Jackson 反序列化)
      │
      └─ [第7步] 响应解析                                   // :293
          └─ .map(resp -> formatter.parseResponse(resp, start))
              └─ DashScopeResponseParser.parseResponse()
                  ├─ output.choices[0].message
                  │   ├─ reasoning_content → ThinkingBlock
                  │   ├─ content           → TextBlock
                  │   └─ tool_calls        → ToolUseBlock (per item)
                  ├─ usage → ChatUsage
                  └─ finishReason
                      ↓
                  ChatResponse
```

**返回后的处理（回到 ReActAgent.reasoning()）：**

```
Flux<ChatResponse>
  │
  ├─ streamOptions 控制事件类型过滤 → Flux<HookEvent>
  │
  └─ ReActAgent 内部 hook 消费
      └─ 累积所有 ContentBlock → 构建 Msg(ASSISTANT)
          ├─ 无 tool_calls → 返回给用户
          └─ 有 tool_calls → acting() 执行工具 → 循环回 reasoning()
```

---

## 5. DTO 类结构速查

### 请求方向

```
DashScopeRequest
├── model: String                       # "qwen-plus"
├── input: DashScopeInput
│   └── messages: List<DashScopeMessage>
│       ├── role: String                # "system" | "user" | "assistant" | "tool"
│       ├── content: String | List<DashScopeContentPart>
│       │   ├── text: String
│       │   ├── image: String           # URL 或 data: URI
│       │   ├── audio: String           # URL 或 data: URI
│       │   ├── video: Object           # String(URL) | List<String>(帧列表)
│       │   ├── fps: Float
│       │   ├── max_frames: Integer
│       │   ├── min_pixels: Integer
│       │   ├── max_pixels: Integer
│       │   └── total_pixels: Integer
│       ├── name: String                # TOOL role: 工具名称
│       ├── tool_call_id: String        # TOOL role: 对应的 tool call ID
│       ├── tool_calls: List<DashScopeToolCall>  # ASSISTANT role
│       │   ├── id: String
│       │   ├── type: "function"
│       │   ├── function: DashScopeFunction
│       │   │   ├── name: String
│       │   │   └── arguments: String   # JSON string
│       │   └── index: Integer
│       ├── reasoning_content: String   # (仅响应中，请求中不使用)
│       └── cache_control: Map<String,String>
└── parameters: DashScopeParameters
    ├── result_format: "message"
    ├── incremental_output: Boolean
    ├── temperature: Double
    ├── top_p: Double
    ├── top_k: Integer
    ├── max_tokens: Integer
    ├── enable_thinking: Boolean
    ├── thinking_budget: Integer
    ├── tools: List<DashScopeTool>
    ├── tool_choice: Object             # "auto" | "none" | {type, function: {name}}
    ├── parallel_tool_calls: Boolean
    ├── seed: Integer
    ├── frequency_penalty: Double
    ├── presence_penalty: Double
    └── response_format: ResponseFormat
```

### 响应方向

```
DashScopeResponse
├── request_id: String
├── output: DashScopeOutput
│   ├── choices: List<DashScopeChoice>
│   │   └── [0]:
│   │       ├── message: DashScopeMessage
│   │       │   ├── role: "assistant"
│   │       │   ├── content: String | List<DashScopeContentPart>
│   │       │   ├── reasoning_content: String   # thinking 模式
│   │       │   └── tool_calls: List<DashScopeToolCall>
│   │       ├── finish_reason: String  # "stop" | "length" | "tool_calls" | null
│   │       └── index: Integer
│   ├── finish_reason: String
│   └── text: String                   # legacy text field
├── usage: DashScopeUsage
│   ├── input_tokens: Integer
│   └── output_tokens: Integer
├── code: String                       # 错误码
└── message: String                    # 错误消息

        ↓ DashScopeResponseParser.parseResponse()

ChatResponse
├── id: String                         # 来自 request_id
├── content: List<ContentBlock>
│   ├── ThinkingBlock                  # reasoning_content → ThinkingBlock
│   ├── TextBlock                      # content → TextBlock
│   └── ToolUseBlock                   # tool_calls → ToolUseBlock
├── usage: ChatUsage
│   ├── inputTokens: int
│   ├── outputTokens: int
│   └── time: double                   # Duration.between(startTime, Instant.now())
└── finishReason: String
```

### finish_reason 取值含义

| 值 | 含义 |
|---|------|
| `"stop"` | 正常结束 |
| `"length"` | 达到 max_tokens 限制 |
| `"tool_calls"` | 模型要求调用工具 |
| `null` | 流式中间块，尚未完成 |

---

## 总结

| 转换方向 | 关键类 | 核心方法 |
|---------|-------|---------|
| `Msg` → `DashScopeMessage` | `DashScopeMessageConverter` | `convertToMessage(msg, hasMedia)` |
| `List<Msg>` → `List<DashScopeMessage>` | `DashScopeChatFormatter` | `doFormat(msgs)` |
| `List<Msg>` + params → `DashScopeRequest` | `DashScopeChatFormatter` | `buildRequest(...)` |
| `ImageBlock` → URL String | `DashScopeMediaConverter` | `convertImageBlockToUrl(block)` |
| `List<ToolSchema>` → `List<DashScopeTool>` | `DashScopeToolsHelper` | `convertTools(tools)` |
| `List<ToolUseBlock>` → `List<DashScopeToolCall>` | `DashScopeToolsHelper` | `convertToolCalls(blocks)` |
| `DashScopeResponse` → `ChatResponse` | `DashScopeResponseParser` | `parseResponse(response, startTime)` |
| `DashScopeRequest` → JSON | Jackson (自动) | — |
| JSON → `DashScopeResponse` | Jackson (自动) | — |

**记住几个关键规则：**

1. **ThinkingBlock 只在响应中出现，请求中总是被跳过**
2. **content 字段有两种格式：String (simple) 或 List&lt;DashScopeContentPart&gt; (multimodal)**
3. **ToolUseBlock.input 在响应解析时总是空 Map，由后续处理填充**
4. **流式 tool_calls 用 `__fragment__` 标记后续增量块**
5. **ContentBlock 组装顺序永远是：ThinkingBlock → TextBlock → ToolUseBlock**
6. **TOOL role 消息同时需要 `tool_call_id` 和 `name` 字段**
