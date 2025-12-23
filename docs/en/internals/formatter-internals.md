# Formatter Internals

This document introduces the internal design of the AgentScope Formatter, explaining how to convert unified message formats to different LLM provider API formats.

## Design Purpose

Different LLM providers have API format differences:

| Difference | DashScope | OpenAI | Anthropic | Gemini |
|------------|-----------|--------|-----------|--------|
| Tool call field | `tool_calls` | `tool_calls` | `tool_use` | `function_calls` |
| Tool result role | `tool` | `tool` | `user` (with tool_result) | `function` |
| System message | role=system | role=system | Separate system param | system_instruction |
| Thinking trace | Supports `<think>` tag | Not supported | Supports extended_thinking | Supported |
| Multimodal format | Nested structure | Flat structure | content blocks | parts array |

Formatter serves as an adapter layer to abstract away these differences.

## Architecture Design

```
                    AgentScope Messages
                          │
                          ▼
                ┌─────────────────┐
                │    Formatter    │ (interface)
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

## Formatter Interface

```java
public interface Formatter<T> {
    // Format message list
    List<T> format(FormatContext context);

    // Format tool definitions
    List<Object> formatTools(List<AgentTool> tools);

    // Parse response
    Msg parseResponse(Object response);
}
```

### FormatContext

Format context contains all needed information:

```java
public class FormatContext {
    private final String sysPrompt;           // System prompt
    private final List<Msg> messages;         // Message list
    private final List<AgentTool> tools;      // Tool list
    private final String agentName;           // Agent name
    private final GenerateOptions options;    // Generation options
}
```

## Message Formatting Flow

### 1. System Prompt Building

```java
// DashScopeFormatter example
String buildSystemPrompt(FormatContext context) {
    StringBuilder sb = new StringBuilder();

    // Base system prompt
    sb.append(context.getSysPrompt());

    // Tool descriptions (if any)
    if (!context.getTools().isEmpty()) {
        sb.append("\n\nYou have the following tools available:\n");
        for (AgentTool tool : context.getTools()) {
            sb.append("- ").append(tool.getName())
              .append(": ").append(tool.getDescription()).append("\n");
        }
    }

    // Long-term memories (if any)
    String memories = context.getLongTermMemories();
    if (memories != null) {
        sb.append("\n\n<long_term_memory>\n")
          .append(memories)
          .append("\n</long_term_memory>");
    }

    return sb.toString();
}
```

### 2. Role Mapping

```java
// AgentScope role → API role
String mapRole(MsgRole role) {
    return switch (role) {
        case SYSTEM -> "system";
        case USER -> "user";
        case ASSISTANT -> "assistant";
        case TOOL -> "tool";
    };
}
```

### 3. Content Block Conversion

```java
// TextBlock conversion
Object formatTextBlock(TextBlock block) {
    return Map.of(
        "type", "text",
        "text", block.getText()
    );
}

// ImageBlock conversion (DashScope format)
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

// ToolUseBlock conversion
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

## Multi-Agent Message Processing

In multi-agent scenarios, different agents' statements need to be distinguished.

### Problem

LLMs only accept USER/ASSISTANT roles, cannot directly distinguish multiple agents.

### Solution

Inject agent name into message content:

```java
// Original message
Msg msg = Msg.builder()
    .name("AgentA")
    .role(MsgRole.ASSISTANT)
    .textContent("My view is...")
    .build();

// After formatting (sent to LLM)
{
    "role": "user",  // Converted to user role
    "content": "[AgentA]: My view is..."  // Name injected
}
```

### Implementation

```java
Object formatMultiAgentMessage(Msg msg, String currentAgent) {
    String name = msg.getName();
    String text = msg.getTextContent();

    // Non-current agent messages, convert to user role
    if (!name.equals(currentAgent)) {
        return Map.of(
            "role", "user",
            "content", "[" + name + "]: " + text
        );
    }

    // Current agent messages, keep assistant role
    return Map.of(
        "role", "assistant",
        "content", text
    );
}
```

## Tool Schema Formatting

### JSON Schema Generation

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

### DashScope Format

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "Get weather for a city",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "City name"
        }
      },
      "required": ["city"]
    }
  }
}
```

### OpenAI Format

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "Get weather for a city",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "City name"
        }
      },
      "required": ["city"]
    }
  }
}
```

(Both are essentially the same for tool definitions)

## Response Parsing

### Streaming Response Processing

```java
// Streaming chunk parsing
Msg parseStreamChunk(StreamChunk chunk) {
    List<ContentBlock> blocks = new ArrayList<>();

    // Parse text delta
    if (chunk.hasTextDelta()) {
        blocks.add(TextBlock.builder()
            .text(chunk.getTextDelta())
            .build());
    }

    // Parse thinking delta
    if (chunk.hasThinkingDelta()) {
        blocks.add(ThinkingBlock.builder()
            .thinking(chunk.getThinkingDelta())
            .build());
    }

    // Parse tool call delta
    if (chunk.hasToolCallDelta()) {
        // Tool calls need to accumulate before parsing
        accumulateToolCall(chunk.getToolCallDelta());
    }

    return Msg.builder()
        .role(MsgRole.ASSISTANT)
        .content(blocks)
        .build();
}
```

### Tool Call Parsing

```java
// Parse tool calls from API response
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

## DashScope-Specific Processing

### Thinking Trace Parsing

DashScope reasoning models like QwQ output `<think>` tags:

```java
Msg parseResponseWithThinking(String content) {
    List<ContentBlock> blocks = new ArrayList<>();

    // Parse <think>...</think>
    Matcher matcher = THINK_PATTERN.matcher(content);
    int lastEnd = 0;

    while (matcher.find()) {
        // Text before thinking
        if (matcher.start() > lastEnd) {
            blocks.add(TextBlock.builder()
                .text(content.substring(lastEnd, matcher.start()))
                .build());
        }

        // Thinking content
        blocks.add(ThinkingBlock.builder()
            .thinking(matcher.group(1))
            .build());

        lastEnd = matcher.end();
    }

    // Remaining text
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

### Vision Model Routing

DashScope vision models (qwen-vl-*) need special API endpoints:

```java
String getApiEndpoint(String modelName) {
    if (modelName.startsWith("qwen-vl") || modelName.startsWith("qvq")) {
        return "/multimodal/chat/completions";
    }
    return "/chat/completions";
}
```

## Formatter Selection

Framework automatically selects Formatter based on Model type:

```java
// Inside Model
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

### Anthropic-Specific Processing

Anthropic API has some unique requirements:

```java
// System message must be handled separately, not in messages
MessageCreateParams.Builder builder = MessageCreateParams.builder()
    .model(modelName)
    .system(systemPrompt)  // Separate system parameter
    .maxTokens(4096);

// Tool results must be returned in user messages
MessageParam toolResultMsg = MessageParam.builder()
    .role(MessageParam.Role.USER)
    .content(ToolResultBlockParam.builder()
        .toolUseId(toolUseId)
        .content(result)
        .build())
    .build();
```

### Gemini-Specific Processing

Gemini API uses Content and Parts structure:

```java
// Build Gemini message
Content content = Content.builder()
    .role("user")
    .addPart(Part.fromText("Question content"))
    .addPart(Part.fromImage(imageBytes))  // Multimodal
    .build();

// Tool calls use FunctionCall
FunctionCall functionCall = FunctionCall.builder()
    .name("get_weather")
    .args(Map.of("city", "Beijing"))
    .build();
```

## Related Documentation

- [Message System Architecture](./message-system.md)
- [Model Integration](../task/model.md)
