# Message System Architecture

This document introduces the internal design of the AgentScope message system, including message structure, content block types, and serialization mechanisms.

## Message Model Design

AgentScope uses `Msg` as a unified message carrier that flows throughout the framework.

### Core Design Principles

1. **Content Block Composition** - Message content consists of multiple `ContentBlock`s, supporting mixed content
2. **Clear Roles** - `MsgRole` distinguishes message sources
3. **Extensible** - `metadata` carries additional information
4. **Serializable** - Supports JSON serialization for persistence and transmission

### Msg Structure

```java
public class Msg {
    private String name;                    // Sender name
    private MsgRole role;                   // Role
    private List<ContentBlock> content;     // Content block list
    private Map<String, Object> metadata;   // Metadata
    private String id;                      // Message ID
    private Instant timestamp;              // Timestamp
}
```

## Role Types (MsgRole)

Roles determine the semantic position of messages in conversations:

| Role | Description | Use Case |
|------|-------------|----------|
| `SYSTEM` | System prompt | Define agent behavior, inject tool Schema |
| `USER` | User input | User questions, instructions |
| `ASSISTANT` | Assistant response | LLM-generated replies, tool call requests |
| `TOOL` | Tool result | Results returned from tool execution |

### Role Mapping in LLM APIs

Different LLM providers handle roles differently:

```
AgentScope Role     DashScope           OpenAI
─────────────────────────────────────────────────
SYSTEM          →   system          →   system
USER            →   user            →   user
ASSISTANT       →   assistant       →   assistant
TOOL            →   tool            →   tool
```

## Content Block Types (ContentBlock)

`ContentBlock` is the basic unit of message content, using composition pattern to support multiple content types.

### Type Hierarchy

```
ContentBlock (interface)
├── TextBlock           // Plain text
├── ImageBlock          // Image
├── AudioBlock          // Audio
├── VideoBlock          // Video
├── ThinkingBlock       // Reasoning trace
├── ToolUseBlock        // Tool call request
└── ToolResultBlock     // Tool execution result
```

### TextBlock

The most common content type, carrying text information.

```java
TextBlock text = TextBlock.builder()
    .text("Hello, how can I help you?")
    .build();
```

**Serialization format**:

```json
{
  "type": "text",
  "text": "Hello, how can I help you?"
}
```

### ImageBlock

Carries image content, supporting multiple sources.

```java
// URL source
ImageBlock urlImage = ImageBlock.builder()
    .source(URLSource.of("https://example.com/image.jpg"))
    .build();

// Base64 source
ImageBlock base64Image = ImageBlock.builder()
    .source(Base64Source.builder()
        .mediaType("image/png")
        .data(base64EncodedData)
        .build())
    .build();

// Local file source
ImageBlock fileImage = ImageBlock.builder()
    .source(URLSource.of("file:///path/to/image.jpg"))
    .build();
```

**Image Source Types**:

| Source Type | Description | Use Case |
|-------------|-------------|----------|
| `URLSource` | HTTP/HTTPS URL | Web images |
| `URLSource` | file:// protocol | Local files |
| `Base64Source` | Base64 encoded data | In-memory image data |

### ThinkingBlock

Used for reasoning traces output by reasoning models (like QwQ).

```java
ThinkingBlock thinking = ThinkingBlock.builder()
    .thinking("Let me analyze this problem...")
    .build();
```

**Design Notes**:

- Reasoning models output thinking content wrapped in `<think>` tags
- Framework automatically parses into `ThinkingBlock`
- Retained in Memory for debugging and analysis

### ToolUseBlock

Generated when LLM requests to call a tool.

```java
ToolUseBlock toolUse = ToolUseBlock.builder()
    .id("call_abc123")                    // Call ID
    .name("get_weather")                  // Tool name
    .input(Map.of("city", "Beijing"))     // Parameters
    .build();
```

**Field Descriptions**:

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique call identifier for associating results |
| `name` | String | Tool name, must match registered name |
| `input` | Map | Tool parameters in JSON object format |

### ToolResultBlock

Result after tool execution completes.

```java
// Success result
ToolResultBlock success = ToolResultBlock.builder()
    .toolUseId("call_abc123")             // Associated call ID
    .output(List.of(
        TextBlock.builder().text("Beijing: Sunny, 25°C").build()
    ))
    .build();

// Error result
ToolResultBlock error = ToolResultBlock.error(
    "call_abc123",
    "API call failed: Connection timeout"
);
```

**Result Association Mechanism**:

```
ToolUseBlock (id: "call_abc123")
        │
        │ Execute
        ▼
ToolResultBlock (toolUseId: "call_abc123")
```

LLM associates call requests and execution results through `id` and `toolUseId`.

## Message Building

### Simplified API

The framework provides convenience methods for common operations:

```java
// Text-only message
Msg textMsg = Msg.builder()
    .textContent("Hello")
    .build();

// Equivalent to
Msg textMsg = Msg.builder()
    .content(List.of(TextBlock.builder().text("Hello").build()))
    .build();
```

### Multimodal Messages

```java
Msg multimodalMsg = Msg.builder()
    .name("user")
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("What's in this image?").build(),
        ImageBlock.builder()
            .source(URLSource.of("https://example.com/photo.jpg"))
            .build()
    ))
    .build();
```

### Tool Call Messages

```java
// LLM response with tool call
Msg toolCallMsg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(List.of(
        TextBlock.builder().text("Let me check the weather").build(),
        ToolUseBlock.builder()
            .id("call_001")
            .name("get_weather")
            .input(Map.of("city", "Beijing"))
            .build()
    ))
    .build();

// Tool result message
Msg toolResultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(List.of(
        ToolResultBlock.builder()
            .toolUseId("call_001")
            .output(List.of(TextBlock.builder().text("Beijing: Sunny").build()))
            .build()
    ))
    .build();
```

## Serialization Mechanism

Messages support JSON serialization for persistence and network transmission.

### Jackson Configuration

The framework uses Jackson for serialization with polymorphic type handling:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
    // ...
})
public interface ContentBlock { }
```

### Serialization Example

```json
{
  "name": "user",
  "role": "USER",
  "content": [
    {
      "type": "text",
      "text": "What is this image?"
    },
    {
      "type": "image",
      "source": {
        "type": "url",
        "url": "https://example.com/photo.jpg"
      }
    }
  ],
  "metadata": {},
  "id": "msg_001",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

## Multi-Agent Message Processing

In multi-agent scenarios, the `name` field distinguishes messages from different agents.

### Message Identification

```java
// Message from Agent A
Msg msgFromA = Msg.builder()
    .name("AgentA")
    .role(MsgRole.ASSISTANT)
    .textContent("I think we should do this...")
    .build();

// Message from Agent B
Msg msgFromB = Msg.builder()
    .name("AgentB")
    .role(MsgRole.ASSISTANT)
    .textContent("I disagree because...")
    .build();
```

### Formatter Processing

Multi-agent messages undergo special processing before being sent to LLM:

```
Original messages:
  name: "AgentA", role: ASSISTANT, content: "View A"
  name: "AgentB", role: ASSISTANT, content: "View B"

After Formatter conversion:
  role: user, content: "[AgentA]: View A"
  role: user, content: "[AgentB]: View B"
```

This helps the LLM understand different participants' statements.

## Content Extraction

The framework provides convenience methods to extract specific content types:

```java
Msg msg = ...;

// Extract all text
String text = msg.getTextContent();

// Extract tool calls
List<ToolUseBlock> toolCalls = msg.getContent().stream()
    .filter(block -> block instanceof ToolUseBlock)
    .map(block -> (ToolUseBlock) block)
    .toList();

// Check if contains tool call
boolean hasToolCall = msg.getContent().stream()
    .anyMatch(block -> block instanceof ToolUseBlock);
```

## Related Documentation

- [ReAct Loop Internals](./react-loop.md)
- [Formatter Internals](./formatter-internals.md)
