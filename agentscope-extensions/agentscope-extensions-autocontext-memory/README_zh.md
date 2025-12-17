# AutoContextMemory

AutoContextMemory 是一个智能上下文内存管理系统，用于自动压缩、卸载和摘要对话历史，以优化 LLM 上下文窗口的使用。

## 背景与问题

### 问题背景

在构建基于大语言模型（LLM）的智能 Agent 系统时，上下文窗口管理是一个关键挑战：

1. **上下文窗口限制**: 大多数 LLM 模型都有固定的上下文窗口大小限制（如 128K tokens），当对话历史超过这个限制时，模型无法处理完整的上下文。

2. **成本问题**: 随着对话历史的增长，每次 API 调用需要发送的 token 数量不断增加，导致：
   - API 调用成本线性增长
   - 响应时间变长
   - 计算资源消耗增加

3. **信息冗余**: 在长对话中，早期对话内容可能已经不再相关，但系统仍然需要处理这些信息，造成资源浪费。

4. **关键信息丢失风险**: 简单的截断策略会丢失重要信息，影响 Agent 的决策质量和任务完成能力。

### AutoContextMemory 的解决方案

AutoContextMemory 通过智能压缩和上下文管理，解决了上述问题：

- **自动压缩**: 当上下文超过阈值时，自动触发压缩策略，减少 token 使用量
- **智能摘要**: 使用 LLM 模型对历史对话进行智能摘要，保留关键信息而非简单截断
- **内容卸载**: 将大型内容卸载到外部存储，通过 UUID 实现按需重载
- **渐进式策略**: 采用 6 种渐进式压缩策略，从轻量级到重量级，确保在保留信息的同时最大化压缩效果
- **完整追溯**: 所有原始内容都保存在原始存储中，支持完整的历史追溯
- **事件追踪**: 记录每次压缩操作的详细信息，便于分析和优化

## 概述

AutoContextMemory 实现了 `Memory` 接口，提供自动化的上下文管理功能。当对话历史超过配置的阈值时，系统会自动应用多种压缩策略来减少上下文大小，同时尽可能保留重要信息。

## 核心特性

- **自动压缩**: 当消息数量或 token 数量超过阈值时自动触发压缩
- **渐进式压缩策略**: 采用 6 种渐进式压缩策略，从轻量级到重量级
- **智能摘要**: 使用 LLM 模型智能摘要历史对话
- **内容卸载**: 将大型内容卸载到外部存储，减少内存使用
- **工具调用保留**: 在压缩过程中保留工具调用接口信息（名称、参数）
- **双存储机制**: 工作存储（压缩后）和原始存储（完整历史）

## 架构设计

### 存储架构

AutoContextMemory 使用多存储机制：

1. **工作内存存储 (Working Memory Storage)**: 存储压缩后的消息，用于实际对话
2. **原始内存存储 (Original Memory Storage)**: 存储完整的、未压缩的消息历史（仅追加模式）
3. **卸载上下文存储 (Offload Context Storage)**: 使用 `Map<String, List<Msg>>` 存储卸载的消息内容，以 UUID 为键
4. **压缩事件存储 (Compression Events Storage)**: 记录所有压缩操作的详细信息，包括事件类型、时间戳、消息数量、token 消耗等
5. **状态持久化**: 所有四个存储都通过 `StateModuleBase` 支持状态序列化和反序列化，可以结合 `SessionManager` 实现上下文信息的持久化

### 压缩策略

系统按以下顺序应用 6 种压缩策略。压缩遵循以下核心原则：

- **当前轮次优先**: 当前轮次的消息重要性高于历史轮次消息，优先保护当前轮次的完整信息
- **用户交互优先**: 用户输入和 Agent 回复的重要性高于工具调用的输入输出中间结果
- **可回溯性**: 所有压缩的原文都可以通过 UUID 回溯，确保信息不丢失

系统按以下顺序应用 6 种压缩策略：

#### 策略 1: 压缩历史工具调用
- 查找历史对话中的连续工具调用消息（超过 `minConsecutiveToolMessages`，默认：6）
- 使用 `lastKeep` 参数保护最后 N 条消息不被压缩（`lastKeep` 参数在此策略中生效）
- 使用 LLM 智能压缩工具调用历史
- 保留工具名称、参数和关键结果
- 对于计划相关工具，使用最小压缩（仅保留简要描述）

#### 策略 2: 卸载大型消息（带 lastKeep 保护）
- 查找超过 `largePayloadThreshold` 的大型消息
- 保护最新的助手响应和最后 N 条消息（`lastKeep` 参数在此策略中生效，与策略 1 共同使用此参数）
- 卸载原始内容并替换为预览（前 200 字符）和 UUID 提示

#### 策略 3: 卸载大型消息（无保护）
- 与策略 2 类似，但不保护最后 N 条消息（`lastKeep` 参数在此策略中不生效）
- 仅保护最新的助手响应

#### 策略 4: 摘要历史对话轮次
- 查找最新助手响应之前的所有用户-助手对话对
- 对每个对话轮次（包括工具调用和助手响应）进行摘要
- 使用 LLM 生成智能摘要，保留关键决策和信息

#### 策略 5: 摘要当前轮次的大型消息
- 查找当前轮次（最新用户消息之后）超过阈值的大型消息
- 使用 LLM 生成摘要并卸载原始内容
- 替换为摘要版本

#### 策略 6: 压缩当前轮次消息
- 当历史消息已压缩但上下文仍超过限制时触发
- 压缩当前轮次的所有消息（通常是工具调用和结果）
- 合并多个工具结果，保留关键信息
- 支持可配置的压缩比例（`currentRoundCompressionRatio`，默认 30%）
- 针对计划相关工具调用，提供更简洁的摘要，保留任务相关信息

## 配置参数

### AutoContextConfig

所有配置参数都可以通过 `AutoContextConfig` 进行设置：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `msgThreshold` | int | 100 | 触发压缩的消息数量阈值 |
| `maxToken` | long | 128 * 1024 | 上下文窗口的最大 token 限制 |
| `tokenRatio` | double | 0.75 | 触发压缩的 token 比例阈值 (0.0-1.0) |
| `lastKeep` | int | 50 | 保持未压缩的最近消息数量（仅在策略 1 和策略 2 中生效） |
| `largePayloadThreshold` | long | 5 * 1024 | 大型消息阈值（字符数） |
| `offloadSinglePreview` | int | 200 | 卸载消息的预览长度（字符数） |
| `minConsecutiveToolMessages` | int | 6 | 压缩所需的最小连续工具消息数量 |
| `currentRoundCompressionRatio` | double | 0.3 | 当前轮次消息的压缩比例 (0.0-1.0)，默认 30% |

### 配置示例

```java
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(50)
    .maxToken(64 * 1024)
    .tokenRatio(0.7)
    .lastKeep(20)
    .largePayloadThreshold(10 * 1024)
    .offloadSinglePreview(300)
    .minConsecutiveToolMessages(4)
    .currentRoundCompressionRatio(0.3)  // 当前轮次压缩到 30%
    .build();
```

## 使用方法

### 基本使用

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.ContextOffloadTool;
import io.agentscope.core.tool.Toolkit;

// 配置
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)
    .lastKeep(10)
    .tokenRatio(0.3)
    .build();

// 创建内存
AutoContextMemory memory = new AutoContextMemory(config, model);

// AutoContextMemory 实现了 ContextOffLoader 接口，可以直接使用
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ContextOffloadTool(memory));

// 创建 Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .build();
```

## API 参考

### AutoContextMemory

#### 主要方法

- `void addMessage(Msg message)`: 添加消息到工作存储和原始存储
- `List<Msg> getMessages()`: 获取消息列表（可能触发压缩）
- `void deleteMessage(int index)`: 从工作存储中删除指定索引的消息
- `void clear()`: 清空所有存储
- `List<Msg> getOriginalMemoryMsgs()`: 获取原始内存存储中的完整消息历史
- `List<Msg> getInteractionMsgs()`: 获取用户-助手交互消息（过滤工具调用）
- `Map<String, List<Msg>> getOffloadContext()`: 获取卸载上下文映射
- `List<CompressionEvent> getCompressionEvents()`: 获取所有压缩事件的记录列表

#### ContextOffLoader 接口方法

- `void offload(String uuid, List<Msg> messages)`: 卸载消息到存储
- `List<Msg> reload(String uuid)`: 通过 UUID 重载卸载的消息
- `void clear(String uuid)`: 清除指定 UUID 的卸载内容

### ContextOffloadTool

提供 `context_reload` 工具，允许 Agent 通过 UUID 重载之前卸载的上下文消息。

```java
@Tool(name = "context_reload", description = "...")
public List<Msg> reload(@ToolParam(name = "working_context_offload_uuid") String uuid)
```

## 工作原理

### 压缩触发条件

压缩在以下情况下触发：

1. **消息数量阈值**: `currentMessages.size() >= msgThreshold`
2. **Token 数量阈值**: `calculateToken(currentMessages) >= maxToken * tokenRatio`

两个条件满足任一即触发压缩。

### 压缩流程

1. 检查是否达到压缩阈值
2. 按顺序尝试 6 种压缩策略
3. 每种策略成功后立即返回压缩后的消息列表
4. 如果所有策略都无法满足要求，记录警告并返回当前工作存储

### 消息保护机制

- **lastKeep 保护**: 最后 N 条消息不会被压缩
- **最新助手响应保护**: 最新的最终助手响应及其后的所有消息不会被压缩
- **当前轮次保护**: 当前轮次（最新用户消息之后）的消息优先使用更轻量级的压缩策略

## 提示词系统

AutoContextMemory 使用预定义的提示词来指导 LLM 进行压缩和摘要。提示词按照压缩策略的渐进顺序组织：

### 策略 1: 工具调用压缩
- `TOOL_INVOCATION_COMPRESS_PROMPT_START/END`: 工具调用压缩提示
  - 保留工具名称、参数和关键结果
  - 对计划相关工具使用最小压缩

### 策略 4: 历史对话摘要
- `PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT_START/END`: 历史对话轮次摘要提示
  - 保留关键决策和信息
  - 摘要用户-助手对话对

### 策略 5: 当前轮次大型消息摘要
- `CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT_START/END`: 当前轮次大型消息摘要提示
  - 针对单个大型消息生成摘要
  - 保留关键信息

### 策略 6: 当前轮次消息压缩
- `CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT_START/END`: 当前轮次消息压缩提示
  - 支持可配置的压缩比例（`currentRoundCompressionRatio`）
  - 明确指定目标字符数
  - 针对计划相关工具调用提供简洁摘要
  - 强调低压缩率，保留任务相关信息

所有提示词都设计为保留关键信息，同时减少 token 使用。策略 6 的提示词特别优化，通过明确的目标字符数和严格的压缩要求，确保压缩效果符合预期。

## 压缩事件追踪

AutoContextMemory 提供了完整的压缩事件追踪系统，记录每次压缩操作的详细信息：

### CompressionEvent

每个压缩事件包含以下信息：

- **eventType**: 压缩策略类型（TOOL_INVOCATION_COMPRESS, LARGE_MESSAGE_OFFLOAD, PREVIOUS_ROUND_CONVERSATION_SUMMARY 等）
- **timestamp**: 事件发生的时间戳（毫秒）
- **compressedMessageCount**: 被压缩的消息数量
- **previousMessageId**: 压缩范围前一条消息的 ID
- **nextMessageId**: 压缩范围后一条消息的 ID
- **compressedMessageId**: 压缩后消息的 ID（如果适用）
- **metadata**: 包含以下信息的元数据：
  - `inputToken`: 压缩操作消耗的输入 token（从 `_chat_usage` 获取）
  - `outputToken`: 压缩操作消耗的输出 token（从 `_chat_usage` 获取）
  - `time`: 压缩操作的耗时（秒，从 `_chat_usage` 获取）
  - `tokenBefore`: 压缩前的 token 数量（仅 offload 操作）
  - `tokenAfter`: 压缩后的 token 数量（仅 offload 操作）

### 使用压缩事件

```java
// 获取所有压缩事件
List<CompressionEvent> events = memory.getCompressionEvents();

// 分析压缩效果
for (CompressionEvent event : events) {
    System.out.println("Event Type: " + event.getEventType());
    System.out.println("Timestamp: " + new Date(event.getTimestamp()));
    System.out.println("Compressed Messages: " + event.getCompressedMessageCount());
    System.out.println("Input Tokens: " + event.getCompressInputToken());
    System.out.println("Output Tokens: " + event.getCompressOutputToken());
    System.out.println("Time: " + event.getMetadata().get("time") + " seconds");
    System.out.println("Token Reduction: " + event.getTokenReduction());
}
```

## 消息元数据

压缩后的消息包含以下元数据信息：

### _compress_meta

包含压缩相关的元信息：

- `offloaduuid`: 卸载消息的 UUID（如果消息被卸载）

### _chat_usage

包含 LLM 调用的使用信息（如果使用了 LLM 进行压缩）：

- `inputTokens`: 输入 token 数量
- `outputTokens`: 输出 token 数量
- `time`: 执行时间（秒）

这些信息可以直接从消息的 metadata 中获取：

```java
Msg compressedMsg = ...;
Map<String, Object> metadata = compressedMsg.getMetadata();
if (metadata != null) {
    // 获取压缩元信息
    Map<String, Object> compressMeta = (Map<String, Object>) metadata.get("_compress_meta");
    if (compressMeta != null) {
        String uuid = (String) compressMeta.get("offloaduuid");
    }
    
    // 获取 LLM 使用信息
    ChatUsage chatUsage = (ChatUsage) metadata.get(MessageMetadataKeys.CHAT_USAGE);
    if (chatUsage != null) {
        int inputTokens = chatUsage.getInputTokens();
        int outputTokens = chatUsage.getOutputTokens();
        double time = chatUsage.getTime();
    }
}
```

## 状态持久化

AutoContextMemory 继承自 `StateModuleBase`，支持状态序列化和反序列化：

- `workingMemoryStorage`: 工作内存存储状态
- `originalMemoryStorage`: 原始内存存储状态
- `offloadContext`: 卸载上下文状态
- `compressionEvents`: 压缩事件记录状态

这使得可以在会话之间保存和恢复内存状态。可以结合 `SessionManager` 实现上下文信息的持久化，将内存状态保存到数据库或其他持久化存储中，实现跨会话的上下文管理。

## 最佳实践

1. **合理设置阈值**: 根据模型上下文窗口大小和实际使用场景调整 `maxToken` 和 `tokenRatio`
2. **保护重要消息**: 使用 `lastKeep` 确保最近的对话不被压缩
3. **注册重载工具**: 注册 `ContextOffloadTool` 以便 Agent 在需要时访问卸载的详细内容
4. **监控压缩日志**: 关注日志输出以了解压缩策略的应用情况
5. **选择合适的模型**: 用于压缩的模型应该具有良好的摘要能力

## 注意事项

1. **LLM 调用**: 压缩过程需要调用 LLM 模型，会产生额外的 API 调用成本。可以通过 `CompressionEvent` 追踪每次压缩的 token 消耗
2. **异步处理**: 压缩是同步阻塞的，可能会影响响应时间。可以通过 `CompressionEvent` 的 `time` 字段监控压缩耗时
3. **信息丢失**: 压缩可能会丢失一些细节信息，虽然系统尽力保留关键信息。所有原始内容都保存在 `originalMemoryStorage` 中，可以通过 UUID 回溯
4. **内存使用**: 原始存储、卸载上下文和压缩事件记录会占用额外内存
5. **压缩事件持久化**: 压缩事件记录会随着状态持久化一起保存，长期运行可能会积累大量事件记录，建议定期清理或归档

## 依赖

- `agentscope-core`: 核心功能依赖

## 许可证

Apache License 2.0

## 相关文档

- [AgentScope Java 文档](https://agentscope.readthedocs.io/)

