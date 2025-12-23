# Formatter 格式化器原理

本文介绍 AgentScope Formatter 的内部设计，解释如何将统一消息格式转换为不同 LLM 提供商的 API 格式。

## 设计目的

不同 LLM 提供商的 API 格式存在差异：

| 差异点 | DashScope | OpenAI | Anthropic | Gemini |
|--------|-----------|--------|-----------|--------|
| 工具调用字段 | `tool_calls` | `tool_calls` | `tool_use` | `function_calls` |
| 工具结果角色 | `tool` | `tool` | `user` (含 tool_result) | `function` |
| 系统消息处理 | role=system | role=system | 单独 system 参数 | system_instruction |
| 思考过程 | 支持 `<think>` 标签 | 不支持 | 支持 extended_thinking | 支持 |
| 多模态格式 | 嵌套结构 | 扁平结构 | content blocks | parts 数组 |

Formatter 作为适配层，屏蔽这些差异。

## 架构设计

```
                    AgentScope 消息
                          │
                          ▼
                ┌─────────────────┐
                │    Formatter    │ (接口)
                └─────────────────┘
                          │
        ┌─────────┬───────┴───────┬─────────┐
        ▼         ▼               ▼         ▼
┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐
│ DashScope │ │  OpenAI   │ │ Anthropic │ │  Gemini   │
│ Formatter │ │ Formatter │ │ Formatter │ │ Formatter │
└───────────┘ └───────────┘ └───────────┘ └───────────┘
        │         │               │         │
        ▼         ▼               ▼         ▼
  DashScope   OpenAI API    Anthropic    Gemini API
    API                        API       /Vertex AI
```

## Formatter 接口

```java
public interface Formatter<T> {
    // 格式化消息列表
    List<T> format(FormatContext context);

    // 格式化工具定义
    List<Object> formatTools(List<AgentTool> tools);

    // 解析响应
    Msg parseResponse(Object response);
}
```

### FormatContext

格式化上下文包含所有需要的信息：

```java
public class FormatContext {
    private final String sysPrompt;           // 系统提示
    private final List<Msg> messages;         // 消息列表
    private final List<AgentTool> tools;      // 工具列表
    private final String agentName;           // 智能体名称
    private final GenerateOptions options;    // 生成选项
}
```

## 消息格式化流程

### 1. 系统提示构建

```java
// DashScopeFormatter 示例
String buildSystemPrompt(FormatContext context) {
    StringBuilder sb = new StringBuilder();

    // 基础系统提示
    sb.append(context.getSysPrompt());

    // 工具说明（如果有）
    if (!context.getTools().isEmpty()) {
        sb.append("\n\n你有以下工具可用：\n");
        for (AgentTool tool : context.getTools()) {
            sb.append("- ").append(tool.getName())
              .append(": ").append(tool.getDescription()).append("\n");
        }
    }

    // 长期记忆（如果有）
    String memories = context.getLongTermMemories();
    if (memories != null) {
        sb.append("\n\n<long_term_memory>\n")
          .append(memories)
          .append("\n</long_term_memory>");
    }

    return sb.toString();
}
```

### 2. 角色映射

```java
// AgentScope 角色 → API 角色
String mapRole(MsgRole role) {
    return switch (role) {
        case SYSTEM -> "system";
        case USER -> "user";
        case ASSISTANT -> "assistant";
        case TOOL -> "tool";
    };
}
```

### 3. 内容块转换

```java
// TextBlock 转换
Object formatTextBlock(TextBlock block) {
    return Map.of(
        "type", "text",
        "text", block.getText()
    );
}

// ImageBlock 转换（DashScope 格式）
Object formatImageBlock(ImageBlock block) {
    Source source = block.getSource();
    if (source instanceof URLSource url) {
        return Map.of(
            "type", "image",
            "image", url.getUrl()
        );
    } else if (source instanceof Base64Source b64) {
        return Map.of(
            "type", "image",
            "image", "data:" + b64.getMediaType() + ";base64," + b64.getData()
        );
    }
    throw new IllegalArgumentException("Unknown source type");
}

// ToolUseBlock 转换
Object formatToolUseBlock(ToolUseBlock block) {
    return Map.of(
        "type", "function",
        "id", block.getId(),
        "function", Map.of(
            "name", block.getName(),
            "arguments", JsonUtils.toJson(block.getInput())
        )
    );
}
```

## 多智能体消息处理

在多智能体场景中，需要区分不同智能体的发言。

### 问题

LLM 只接受 USER/ASSISTANT 角色，无法直接区分多个智能体。

### 解决方案

将智能体名称注入消息内容：

```java
// 原始消息
Msg msg = Msg.builder()
    .name("AgentA")
    .role(MsgRole.ASSISTANT)
    .textContent("我的观点是...")
    .build();

// 格式化后（发送给 LLM）
{
    "role": "user",  // 转换为 user 角色
    "content": "[AgentA]: 我的观点是..."  // 注入名称
}
```

### 实现

```java
Object formatMultiAgentMessage(Msg msg, String currentAgent) {
    String name = msg.getName();
    String text = msg.getTextContent();

    // 非当前智能体的消息，转换为 user 角色
    if (!name.equals(currentAgent)) {
        return Map.of(
            "role", "user",
            "content", "[" + name + "]: " + text
        );
    }

    // 当前智能体的消息，保持 assistant 角色
    return Map.of(
        "role", "assistant",
        "content", text
    );
}
```

## 工具 Schema 格式化

### JSON Schema 生成

```java
// AgentTool → JSON Schema
Map<String, Object> formatToolSchema(AgentTool tool) {
    return Map.of(
        "type", "function",
        "function", Map.of(
            "name", tool.getName(),
            "description", tool.getDescription(),
            "parameters", tool.getParameters()
        )
    );
}
```

### DashScope 格式

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "获取指定城市的天气",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "城市名称"
        }
      },
      "required": ["city"]
    }
  }
}
```

### OpenAI 格式

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "获取指定城市的天气",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "城市名称"
        }
      },
      "required": ["city"]
    }
  }
}
```

（两者在工具定义格式上基本一致）

## 响应解析

### 流式响应处理

```java
// 流式 chunk 解析
Msg parseStreamChunk(StreamChunk chunk) {
    List<ContentBlock> blocks = new ArrayList<>();

    // 解析文本增量
    if (chunk.hasTextDelta()) {
        blocks.add(TextBlock.builder()
            .text(chunk.getTextDelta())
            .build());
    }

    // 解析思考增量
    if (chunk.hasThinkingDelta()) {
        blocks.add(ThinkingBlock.builder()
            .thinking(chunk.getThinkingDelta())
            .build());
    }

    // 解析工具调用增量
    if (chunk.hasToolCallDelta()) {
        // 工具调用需要累积完整后再解析
        accumulateToolCall(chunk.getToolCallDelta());
    }

    return Msg.builder()
        .role(MsgRole.ASSISTANT)
        .content(blocks)
        .build();
}
```

### 工具调用解析

```java
// 从 API 响应解析工具调用
List<ToolUseBlock> parseToolCalls(Object response) {
    List<ToolUseBlock> toolCalls = new ArrayList<>();

    for (ToolCall call : getToolCalls(response)) {
        toolCalls.add(ToolUseBlock.builder()
            .id(call.getId())
            .name(call.getFunction().getName())
            .input(JsonUtils.fromJson(
                call.getFunction().getArguments(),
                Map.class
            ))
            .build());
    }

    return toolCalls;
}
```

## DashScope 特有处理

### 思考过程解析

DashScope 的 QwQ 等推理模型输出 `<think>` 标签：

```java
Msg parseResponseWithThinking(String content) {
    List<ContentBlock> blocks = new ArrayList<>();

    // 解析 <think>...</think>
    Matcher matcher = THINK_PATTERN.matcher(content);
    int lastEnd = 0;

    while (matcher.find()) {
        // 思考前的文本
        if (matcher.start() > lastEnd) {
            blocks.add(TextBlock.builder()
                .text(content.substring(lastEnd, matcher.start()))
                .build());
        }

        // 思考内容
        blocks.add(ThinkingBlock.builder()
            .thinking(matcher.group(1))
            .build());

        lastEnd = matcher.end();
    }

    // 剩余文本
    if (lastEnd < content.length()) {
        blocks.add(TextBlock.builder()
            .text(content.substring(lastEnd))
            .build());
    }

    return Msg.builder()
        .role(MsgRole.ASSISTANT)
        .content(blocks)
        .build();
}
```

### 视觉模型路由

DashScope 视觉模型（qwen-vl-*）需要特殊 API 端点：

```java
String getApiEndpoint(String modelName) {
    if (modelName.startsWith("qwen-vl") || modelName.startsWith("qvq")) {
        return "/multimodal/chat/completions";
    }
    return "/chat/completions";
}
```

## Formatter 选择

框架根据 Model 类型自动选择 Formatter：

```java
// Model 内部
Formatter<?> getFormatter() {
    if (this instanceof DashScopeChatModel) {
        return new DashScopeChatFormatter();
    } else if (this instanceof OpenAIChatModel) {
        return new OpenAIChatFormatter();
    } else if (this instanceof AnthropicChatModel) {
        return new AnthropicChatFormatter();
    } else if (this instanceof GeminiChatModel) {
        return new GeminiChatFormatter();
    }
    throw new IllegalStateException("Unknown model type");
}
```

### Anthropic 特有处理

Anthropic API 有一些独特要求：

```java
// 系统消息需要单独处理，不能放在 messages 中
MessageCreateParams.Builder builder = MessageCreateParams.builder()
    .model(modelName)
    .system(systemPrompt)  // 单独的 system 参数
    .maxTokens(4096);

// 工具结果必须在 user 消息中返回
MessageParam toolResultMsg = MessageParam.builder()
    .role(MessageParam.Role.USER)
    .content(ToolResultBlockParam.builder()
        .toolUseId(toolUseId)
        .content(result)
        .build())
    .build();
```

### Gemini 特有处理

Gemini API 使用 Content 和 Parts 结构：

```java
// 构建 Gemini 消息
Content content = Content.builder()
    .role("user")
    .addPart(Part.fromText("问题内容"))
    .addPart(Part.fromImage(imageBytes))  // 多模态
    .build();

// 工具调用使用 FunctionCall
FunctionCall functionCall = FunctionCall.builder()
    .name("get_weather")
    .args(Map.of("city", "北京"))
    .build();
```

## 相关文档

- [消息系统架构](./message-system.md)
- [模型集成](../task/model.md)
