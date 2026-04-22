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
- **双存储机制**: 工作存储（压缩后）和原始存储（完整历史）
- **计划感知**: 自动集成 PlanNotebook，根据当前计划状态调整压缩策略，确保压缩过程中保留关键的计划相关信息
- **Prompt 定制**: 支持根据具体场景和领域定制上下文压缩 prompt，实现针对性的压缩优化

## 架构设计

### 存储架构

AutoContextMemory 使用多存储机制：

1. **工作内存存储 (Working Memory Storage)**: 存储压缩后的消息，用于实际对话
2. **原始内存存储 (Original Memory Storage)**: 存储完整的、未压缩的消息历史（仅追加模式）
3. **卸载上下文存储 (Offload Context Storage)**: 使用 `Map<String, List<Msg>>` 存储卸载的消息内容，以 UUID 为键
4. **压缩事件存储 (Compression Events Storage)**: 记录所有压缩操作的详细信息，包括事件类型、时间戳、消息数量、token 消耗等
5. **状态持久化**: 所有四个存储都通过 `StateModuleBase` 支持状态序列化和反序列化，可以结合 `SessionManager` 实现上下文信息的持久化

### 压缩策略

压缩遵循以下核心原则：

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
| `customPrompt` | PromptConfig | null | 定制上下文压缩 prompt 配置（可选，未设置时使用默认 prompt） |

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

在 `ReActAgent` 中使用 `AutoContextMemory` 时，建议使用 `AutoContextHook` 来自动处理集成设置。该 Hook 会自动完成必要的配置。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.tool.Toolkit;

// 配置
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)
    .lastKeep(10)
    .tokenRatio(0.3)
    .build();

// 创建内存
AutoContextMemory memory = new AutoContextMemory(config, model);

// 创建 Agent，使用 AutoContextHook 自动处理集成
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(new Toolkit())
    .enablePlan()  // 启用 PlanNotebook 支持（可选，但推荐）
    .hook(new AutoContextHook())  // 自动注册 ContextOffloadTool 并附加 PlanNotebook
    .build();
```

`AutoContextHook` 会自动完成以下操作：
- 将 `ContextOffloadTool` 注册到 agent 的 toolkit（启用上下文重载功能）
- 将 agent 的 `PlanNotebook` 附加到 `AutoContextMemory`，实现计划感知压缩（如果启用了 PlanNotebook）
- 在每次 LLM 推理调用前通过 `PreReasoningEvent` 触发内存压缩（确保压缩在确定的时间点执行）
- 确保 `AutoContextMemory` 与 `ReActAgent` 之间的正确集成

### 定制上下文压缩Prompt

AutoContextMemory 内部采用默认的通用压缩 prompt，这些 prompt 经过精心设计，期望尽量满足大多数业务需求。然而，根据实际场景和业务特点进行压缩 prompt 的定制设计，可能可以带来更优的压缩效果。例如，针对特定领域的工具调用接口，可以明确指导系统保留哪些关键信息、适当丢弃哪些冗余内容，从而在保证信息完整性的同时实现更高的压缩率。

AutoContextMemory 支持定制上下文压缩策略中使用的 prompt，允许针对特定领域和场景进行优化。

#### PromptConfig

`PromptConfig` 类用于配置定制上下文压缩 prompt，所有 prompt 都是可选的。如果未指定，将使用 `Prompts` 类中的默认值。

可配置的 prompt：

| 字段 | 说明 | 对应策略 |
|------|------|---------|
| `previousRoundToolCompressPrompt` | 历史轮次工具调用压缩提示词 | 策略 1 |
| `previousRoundSummaryPrompt` | 历史轮次对话摘要提示词 | 策略 4 |
| `currentRoundLargeMessagePrompt` | 当前轮次大型消息摘要提示词 | 策略 5 |
| `currentRoundCompressPrompt` | 当前轮次消息压缩提示词 | 策略 6 |

#### 使用示例

`customPrompt` 是可选的，可以不设置（使用默认 prompt），也可以只设置其中任意个 prompt（未设置的将使用默认值）。

```java
import io.agentscope.core.memory.autocontext.PromptConfig;

// 方式 1：不设置 customPrompt，使用默认 prompt（向后兼容）
AutoContextConfig config1 = AutoContextConfig.builder()
    .msgThreshold(50)
    .maxToken(64 * 1024)
    .build();

// 方式 2：只设置部分 prompt，其他使用默认值
PromptConfig customPrompt2 = PromptConfig.builder()
    .previousRoundToolCompressPrompt("定制策略1提示词...")
    // 其他 prompt 未设置，将使用默认值
    .build();
AutoContextConfig config2 = AutoContextConfig.builder()
    .msgThreshold(50)
    .customPrompt(customPrompt2)
    .build();

// 方式 3：设置所有 prompt
PromptConfig customPrompt3 = PromptConfig.builder()
    .previousRoundToolCompressPrompt("定制策略1提示词...")
    .previousRoundSummaryPrompt("定制策略4提示词...")
    .currentRoundLargeMessagePrompt("定制策略5提示词...")
    .currentRoundCompressPrompt("定制策略6提示词...")
    .build();
AutoContextConfig config3 = AutoContextConfig.builder()
    .msgThreshold(50)
    .customPrompt(customPrompt3)
    .build();
```

**领域特定 Prompt 示例**

**电商订单处理场景（具体工具调用接口示例）**

```java
// 针对电商订单处理场景的定制 prompt
// 假设系统中有以下工具：get_order_info, update_order_status, calculate_price, send_notification
PromptConfig ecommerceCustomPrompt = PromptConfig.builder()
    .previousRoundToolCompressPrompt(
        "你是一个电商订单处理助手。请压缩以下工具调用历史，按照以下规则处理：\n" +
        "\n" +
        "【必须保留的信息】\n" +
        "1. get_order_info 工具调用：保留订单号、订单状态、关键商品信息（商品ID、名称、数量、价格）\n" +
        "2. update_order_status 工具调用：保留订单号、状态变更（从X到Y）、变更时间\n" +
        "3. calculate_price 工具调用：保留最终计算的总价、优惠金额、运费\n" +
        "4. send_notification 工具调用：保留通知类型（短信/邮件）、通知对象、通知内容摘要\n" +
        "\n" +
        "【可以丢弃的信息】\n" +
        "1. 详细的商品描述、图片URL等非关键信息\n" +
        "2. 重复的订单信息查询结果（只保留最后一次查询的关键结果）\n" +
        "3. 中间计算过程的详细步骤（只保留最终结果）\n" +
        "4. 通知发送的详细日志和响应内容（只保留发送状态）\n" +
        "\n" +
        "请将多个工具调用合并为简洁的摘要，突出订单处理的关键决策点和最终状态。"
    )
    .currentRoundCompressPrompt(
        "当前轮次包含订单处理相关的工具调用。压缩时请遵循以下原则：\n" +
        "1. 保留所有订单状态变更的关键信息（订单号、状态变化）\n" +
        "2. 保留价格计算的结果（总价、优惠、实付金额）\n" +
        "3. 保留通知发送的关键信息（通知类型、发送状态）\n" +
        "4. 可以简化工具调用的详细参数，但保留核心业务数据\n" +
        "5. 合并相同订单的多次操作，只保留最终状态和关键中间状态"
    )
    .build();

AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(50)
    .customPrompt(ecommerceCustomPrompt)
    .build();
```

## API 参考

### AutoContextMemory

#### 主要方法

- `void addMessage(Msg message)`: 添加消息到工作存储和原始存储
- `List<Msg> getMessages()`: 获取工作内存中的消息列表
- `boolean compressIfNeeded()`: 如果达到阈值则压缩工作内存（如果执行了压缩则返回 true）
- `void deleteMessage(int index)`: 从工作存储中删除指定索引的消息
- `void clear()`: 清空所有存储
- `List<Msg> getOriginalMemoryMsgs()`: 获取原始内存存储中的完整消息历史
- `List<Msg> getInteractionMsgs()`: 获取用户-助手交互消息（过滤工具调用）
- `Map<String, List<Msg>> getOffloadContext()`: 获取卸载上下文映射
- `List<CompressionEvent> getCompressionEvents()`: 获取所有压缩事件的记录列表
- `void attachPlanNote(PlanNotebook planNotebook)`: 附加 PlanNotebook 以启用计划感知压缩

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

### AutoContextHook

一个 `Hook`，同时处理 `PreCallEvent` 和 `PreReasoningEvent`，用于设置和管理 `AutoContextMemory` 与 `ReActAgent` 的集成：

**PreCallEvent 处理（每个 agent 只执行一次，线程安全）：**
- 自动将 `ContextOffloadTool` 注册到 agent 的 toolkit
- 自动将 agent 的 `PlanNotebook` 附加到 `AutoContextMemory`，实现计划感知压缩（如果启用了 PlanNotebook）

**PreReasoningEvent 处理（在每次 LLM 推理调用前执行）：**
- 如果达到阈值，通过 `compressIfNeeded()` 触发内存压缩
- 更新事件中的输入消息，以反映压缩后的工作内存
- 确保压缩在确定的时间点（推理前）执行，LLM 接收到压缩后的上下文

**必须使用**：在 `ReActAgent` 中使用 `AutoContextMemory` 时必须使用此 Hook，以确保正确的集成和自动压缩触发。

使用方法：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .enablePlan()  // 可选，但推荐启用
    .hook(new AutoContextHook())  // 自动处理集成设置
    .build();
```

## 工作原理

### 压缩触发条件

压缩由 `AutoContextHook` 通过 `PreReasoningEvent`（在每次 LLM 推理调用前）自动触发，当满足以下条件时：

1. **消息数量阈值**: `currentMessages.size() >= msgThreshold`
2. **Token 数量阈值**: `calculateToken(currentMessages) >= maxToken * tokenRatio`

两个条件满足任一即触发压缩。触发时间点是确定的：总是在 LLM 推理之前，确保推理使用压缩后的上下文。

### 压缩流程

1. `AutoContextHook` 在 LLM 推理前拦截 `PreReasoningEvent`
2. 调用 `compressIfNeeded()` 检查是否达到压缩阈值
3. 如果达到阈值，按顺序尝试 6 种压缩策略
4. 更新 `PreReasoningEvent` 中的输入消息，以反映压缩后的工作内存
5. LLM 推理使用压缩后的上下文进行
6. 如果所有策略都无法满足要求，记录警告并继续使用当前工作存储

### 消息保护机制

- **lastKeep 保护**: 最后 N 条消息不会被压缩
- **最新助手响应保护**: 最新的最终助手响应及其后的所有消息不会被压缩
- **当前轮次保护**: 当前轮次（最新用户消息之后）的消息优先使用更轻量级的压缩策略

## 提示词系统

AutoContextMemory 使用预定义的提示词来指导 LLM 进行压缩和摘要。提示词按照压缩策略的渐进顺序组织：

### 策略 1: 历史轮次工具调用压缩
- `PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT`: 历史轮次工具调用压缩提示
  - 用于对历史轮次的工具调用进行独立压缩
  - 保留工具名称、参数和关键结果
  - 对计划相关工具使用最小压缩
- `COMPRESSION_MESSAGE_LIST_END`: 通用的范围划分标记，表示以上是本次压缩所需要的消息列表

### 策略 4: 历史对话摘要
- `PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT`: 历史对话轮次摘要提示
  - 保留关键决策和信息
  - 摘要用户-助手对话对

### 策略 5: 当前轮次大型消息摘要
- `CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT`: 当前轮次大型消息摘要提示
  - 针对单个大型消息生成摘要
  - 保留关键信息

### 策略 6: 当前轮次消息压缩
- `CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT`: 当前轮次消息压缩提示（不包含字符数要求）
  - 支持可配置的压缩比例（`currentRoundCompressionRatio`）
  - 字符数要求会单独作为最后一条消息发送（`CURRENT_ROUND_MESSAGE_COMPRESS_CHAR_REQUIREMENT`）
  - 针对计划相关工具调用提供简洁摘要
  - 强调低压缩率，保留任务相关信息

所有提示词都设计为保留关键信息，同时减少 token 使用。策略 6 的提示词与字符数要求分离，字符数要求作为最后一条消息发送，确保模型在生成时能够准确控制输出长度。

### 计划感知压缩

当 `PlanNotebook` 附加到 `AutoContextMemory` 后，压缩过程会变得计划感知：

- **计划上下文集成**: 在压缩过程中，系统会自动将当前计划的状态（计划名称、描述、子任务及其状态）作为提示消息包含进来
- **智能信息保留**: 压缩提示词会增强计划上下文，指导 LLM 保留与当前计划和活动子任务相关的信息
- **策略性压缩**: 与进行中或待处理的子任务相关的信息在压缩过程中会被赋予更高的优先级，确保关键的计划相关上下文得以保留

计划感知提示消息会自动插入到压缩提示词中（在最终指令之前），以利用模型的注意力机制，确保压缩指导原则在生成过程中保持在模型的上下文中。

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

1. **使用 AutoContextHook**: 在 `ReActAgent` 中使用 `AutoContextMemory` 时，使用 `AutoContextHook` 来自动处理集成设置，确保 `ContextOffloadTool` 和 `PlanNotebook` 正确配置。
2. **合理设置阈值**: 根据模型上下文窗口大小和实际使用场景调整 `maxToken` 和 `tokenRatio`
3. **保护重要消息**: 使用 `lastKeep` 确保最近的对话不被压缩
4. **启用 PlanNotebook 集成**: 当使用带计划的 `ReActAgent` 时，启用 `PlanNotebook` 支持（`.enablePlan()`）以受益于计划感知压缩
5. **监控压缩日志**: 关注日志输出以了解压缩策略的应用情况
6. **选择合适的模型**: 用于压缩的模型应该具有良好的摘要能力

## 注意事项

1. **LLM 调用**: 压缩过程需要调用 LLM 模型，会产生额外的 API 调用成本。可以通过 `CompressionEvent` 追踪每次压缩的 token 消耗
2. **同步处理**: 压缩是同步阻塞的，可能会影响响应时间。可以通过 `CompressionEvent` 的 `time` 字段监控压缩耗时
3. **信息丢失**: 压缩可能会丢失一些细节信息，虽然系统尽力保留关键信息。所有原始内容都保存在 `originalMemoryStorage` 中，可以通过 UUID 回溯
4. **内存使用**: 原始存储、卸载上下文和压缩事件记录会占用额外内存
5. **压缩事件持久化**: 压缩事件记录会随着状态持久化一起保存，长期运行可能会积累大量事件记录，建议定期清理或归档

## 依赖

- `agentscope-core`: 核心功能依赖

## 许可证

Apache License 2.0

## 相关文档

- [AgentScope Java 文档](https://agentscope.readthedocs.io/)

