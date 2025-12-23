# 消息系统架构

本文介绍 AgentScope 消息系统的内部设计，包括消息结构、内容块类型和序列化机制。

## 消息模型设计

AgentScope 使用 `Msg` 作为统一的消息载体，在整个框架中流转。

### 核心设计原则

1. **内容块组合** - 消息内容由多个 `ContentBlock` 组成，支持混合内容
2. **角色明确** - 通过 `MsgRole` 区分消息来源
3. **可扩展** - 通过 `metadata` 携带额外信息
4. **可序列化** - 支持 JSON 序列化用于持久化和传输

### Msg 结构

```java
public class Msg {
    private String name;                    // 发送者名称
    private MsgRole role;                   // 角色
    private List<ContentBlock> content;     // 内容块列表
    private Map<String, Object> metadata;   // 元数据
    private String id;                      // 消息 ID
    private Instant timestamp;              // 时间戳
}
```

## 角色类型（MsgRole）

角色决定了消息在对话中的语义位置：

| 角色 | 说明 | 使用场景 |
|------|------|----------|
| `SYSTEM` | 系统提示 | 定义智能体行为、注入工具 Schema |
| `USER` | 用户输入 | 用户问题、指令 |
| `ASSISTANT` | 助手响应 | LLM 生成的回复、工具调用请求 |
| `TOOL` | 工具结果 | 工具执行返回的结果 |

### 角色在 LLM API 中的映射

不同 LLM 提供商对角色的处理方式不同：

```
AgentScope Role     DashScope           OpenAI
─────────────────────────────────────────────────
SYSTEM          →   system          →   system
USER            →   user            →   user
ASSISTANT       →   assistant       →   assistant
TOOL            →   tool            →   tool
```

## 内容块类型（ContentBlock）

`ContentBlock` 是消息内容的基本单元，采用组合模式支持多种内容类型。

### 类型层次

```
ContentBlock (接口)
├── TextBlock           // 纯文本
├── ImageBlock          // 图像
├── AudioBlock          // 音频
├── VideoBlock          // 视频
├── ThinkingBlock       // 推理过程
├── ToolUseBlock        // 工具调用请求
└── ToolResultBlock     // 工具执行结果
```

### TextBlock

最常见的内容类型，承载文本信息。

```java
TextBlock text = TextBlock.builder()
    .text("你好，请问有什么可以帮助你的？")
    .build();
```

**序列化格式**：

```json
{
  "type": "text",
  "text": "你好，请问有什么可以帮助你的？"
}
```

### ImageBlock

承载图像内容，支持多种来源。

```java
// URL 来源
ImageBlock urlImage = ImageBlock.builder()
    .source(URLSource.of("https://example.com/image.jpg"))
    .build();

// Base64 来源
ImageBlock base64Image = ImageBlock.builder()
    .source(Base64Source.builder()
        .mediaType("image/png")
        .data(base64EncodedData)
        .build())
    .build();

// 本地文件来源
ImageBlock fileImage = ImageBlock.builder()
    .source(URLSource.of("file:///path/to/image.jpg"))
    .build();
```

**图像来源类型**：

| 来源类型 | 说明 | 适用场景 |
|----------|------|----------|
| `URLSource` | HTTP/HTTPS URL | 网络图片 |
| `URLSource` | file:// 协议 | 本地文件 |
| `Base64Source` | Base64 编码数据 | 内存中的图像数据 |

### ThinkingBlock

用于推理模型（如 QwQ）输出的思考过程。

```java
ThinkingBlock thinking = ThinkingBlock.builder()
    .thinking("让我分析一下这个问题...")
    .build();
```

**设计说明**：

- 推理模型会输出 `<think>` 标签包裹的思考内容
- 框架自动解析为 `ThinkingBlock`
- 在 Memory 中保留，可用于调试和分析

### ToolUseBlock

LLM 请求调用工具时生成。

```java
ToolUseBlock toolUse = ToolUseBlock.builder()
    .id("call_abc123")                    // 调用 ID
    .name("get_weather")                  // 工具名称
    .input(Map.of("city", "北京"))        // 参数
    .build();
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 唯一调用标识，用于关联结果 |
| `name` | String | 工具名称，需与注册名匹配 |
| `input` | Map | 工具参数，JSON 对象格式 |

### ToolResultBlock

工具执行完成后的结果。

```java
// 成功结果
ToolResultBlock success = ToolResultBlock.builder()
    .toolUseId("call_abc123")             // 关联的调用 ID
    .output(List.of(
        TextBlock.builder().text("北京：晴，25°C").build()
    ))
    .build();

// 错误结果
ToolResultBlock error = ToolResultBlock.error(
    "call_abc123",
    "API 调用失败：连接超时"
);
```

**结果关联机制**：

```
ToolUseBlock (id: "call_abc123")
        │
        │ 执行
        ▼
ToolResultBlock (toolUseId: "call_abc123")
```

LLM 通过 `id` 和 `toolUseId` 关联调用请求和执行结果。

## 消息构建

### 简化 API

框架提供便捷方法简化常见操作：

```java
// 纯文本消息
Msg textMsg = Msg.builder()
    .textContent("你好")
    .build();

// 等价于
Msg textMsg = Msg.builder()
    .content(List.of(TextBlock.builder().text("你好").build()))
    .build();
```

### 多模态消息

```java
Msg multimodalMsg = Msg.builder()
    .name("user")
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("这张图片里有什么？").build(),
        ImageBlock.builder()
            .source(URLSource.of("https://example.com/photo.jpg"))
            .build()
    ))
    .build();
```

### 工具调用消息

```java
// LLM 返回的工具调用消息
Msg toolCallMsg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(List.of(
        TextBlock.builder().text("我来查询一下天气").build(),
        ToolUseBlock.builder()
            .id("call_001")
            .name("get_weather")
            .input(Map.of("city", "北京"))
            .build()
    ))
    .build();

// 工具结果消息
Msg toolResultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(List.of(
        ToolResultBlock.builder()
            .toolUseId("call_001")
            .output(List.of(TextBlock.builder().text("北京：晴").build()))
            .build()
    ))
    .build();
```

## 序列化机制

消息支持 JSON 序列化，用于持久化和网络传输。

### Jackson 配置

框架使用 Jackson 进行序列化，配置了多态类型处理：

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

### 序列化示例

```json
{
  "name": "user",
  "role": "USER",
  "content": [
    {
      "type": "text",
      "text": "这张图片是什么？"
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

## 多智能体消息处理

在多智能体场景中，`name` 字段用于区分不同智能体的消息。

### 消息标识

```java
// Agent A 的消息
Msg msgFromA = Msg.builder()
    .name("AgentA")
    .role(MsgRole.ASSISTANT)
    .textContent("我认为应该这样做...")
    .build();

// Agent B 的消息
Msg msgFromB = Msg.builder()
    .name("AgentB")
    .role(MsgRole.ASSISTANT)
    .textContent("我不同意，因为...")
    .build();
```

### Formatter 处理

多智能体消息在发送给 LLM 前，Formatter 会进行特殊处理：

```
原始消息:
  name: "AgentA", role: ASSISTANT, content: "观点A"
  name: "AgentB", role: ASSISTANT, content: "观点B"

Formatter 转换后:
  role: user, content: "[AgentA]: 观点A"
  role: user, content: "[AgentB]: 观点B"
```

这样 LLM 能够理解不同参与者的发言。

## 内容提取

框架提供便捷方法提取特定类型的内容：

```java
Msg msg = ...;

// 提取所有文本
String text = msg.getTextContent();

// 提取工具调用
List<ToolUseBlock> toolCalls = msg.getContent().stream()
    .filter(block -> block instanceof ToolUseBlock)
    .map(block -> (ToolUseBlock) block)
    .toList();

// 检查是否包含工具调用
boolean hasToolCall = msg.getContent().stream()
    .anyMatch(block -> block instanceof ToolUseBlock);
```

## 相关文档

- [ReAct 循环原理](./react-loop.md)
- [Formatter 格式化器原理](./formatter-internals.md)
