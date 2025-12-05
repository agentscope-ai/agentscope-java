# AutoContextMemory

AutoContextMemory 是一个智能的上下文内存管理系统，用于自动压缩、卸载和总结对话历史，以优化 LLM 的上下文窗口使用。

## 概述

AutoContextMemory 实现了 `Memory` 接口，提供了自动化的上下文管理功能。当对话历史超过配置的阈值时，系统会自动应用多种压缩策略来减少上下文大小，同时尽可能保留重要信息。

## 主要特性

- **自动压缩**: 当消息数量或 token 数超过阈值时自动触发压缩
- **多策略压缩**: 采用 5 种渐进式压缩策略，从轻量级到重量级
- **智能总结**: 使用 LLM 模型对历史对话进行智能总结
- **内容卸载**: 将大型内容卸载到外部存储，减少内存占用
- **工具调用保留**: 压缩时保留工具调用的接口信息（名称、参数）
- **双存储机制**: 工作存储（压缩）和原始存储（完整历史）

## 工作原理

### 存储架构

AutoContextMemory 使用双存储机制：

1. **工作存储 (Working Memory Storage)**: 存储压缩后的消息，用于实际对话
2. **原始存储 (Original Memory Storage)**: 存储完整的、未压缩的消息历史

### 压缩策略

系统按以下顺序应用 5 种压缩策略：

#### Strategy 1: 压缩历史工具调用
- 查找历史对话中的连续工具调用消息（超过 4 条）
- 使用 LLM 压缩这些工具调用，保留关键信息
- 将压缩后的内容替换原始工具调用

#### Strategy 2: 卸载大型消息（保留最后 N 条）
- 查找最近一次 assistant 消息之前的大型消息
- 将超过阈值的大型消息卸载到外部存储
- 用预览和卸载提示替换原始消息

#### Strategy 3: 卸载大型消息（不保留）
- 如果 Strategy 2 未生效，尝试更激进的卸载策略
- 卸载所有超过阈值的大型消息

#### Strategy 4: 总结历史轮次
- 找到最近一次 assistant 消息之前的所有对话轮次
- 对每个轮次（user + tools + assistant）进行总结
- 用总结消息替换原始对话轮次

#### Strategy 5: 压缩当前轮次
- 如果历史消息已压缩但上下文仍超限，压缩当前轮次
- 找到最近的 user 消息，合并其后所有消息（通常是工具调用和结果）
- 保留工具调用接口，压缩工具结果

## 配置

### AutoContextConfig

```java
AutoContextConfig config = new AutoContextConfig();

// 消息数量阈值：超过此数量触发压缩
config.setMsgThreshold(100);

// Token 阈值：超过此数量触发压缩
config.setMaxToken(128 * 1024);

// Token 比例：实际触发阈值为 maxToken * tokenRatio
config.setTokenRatio(0.75);

// 保留最后 N 条消息不被压缩
config.setLastKeep(100);

// 大型消息阈值（字符数）
config.setLargePayloadThreshold(5 * 1024);

// 卸载预览长度
config.setOffloadSignlePreview(200);

// 上下文卸载器（可选）
config.setContextOffLoader(new LocalFileContextOffLoader("/path/to/storage"));

// 工作存储（可选，默认使用 InMemoryStorage）
config.setContextStorage(new InMemoryStorage(sessionId));

// 历史存储（可选，默认使用 InMemoryStorage）
config.setHistoryStorage(new InMemoryStorage(sessionId));
```

### 配置参数说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `msgThreshold` | int | 100 | 消息数量阈值 |
| `maxToken` | long | 128 * 1024 | 最大 token 数 |
| `tokenRatio` | double | 0.75 | Token 触发比例 |
| `lastKeep` | int | 100 | 保留最后 N 条消息 |
| `largePayloadThreshold` | long | 5 * 1024 | 大型消息阈值（字符） |
| `offloadSignlePreview` | int | 200 | 卸载预览长度 |
| `contextOffLoader` | ContextOffLoader | null | 上下文卸载器 |
| `contextStorage` | MemoryStorage | null | 工作存储 |
| `historyStorage` | MemoryStorage | null | 历史存储 |

## 使用示例

### 基本使用

```java
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.LocalFileContextOffLoader;
import io.agentscope.core.model.DashScopeChatModel;

// 创建模型
DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey("your-api-key")
    .modelName("qwen-plus")
    .build();

// 配置 AutoContextMemory
AutoContextConfig config = new AutoContextConfig();
config.setMsgThreshold(30);
config.setLastKeep(10);
config.setContextOffLoader(new LocalFileContextOffLoader("/tmp/context"));

// 创建 AutoContextMemory
String sessionId = UUID.randomUUID().toString();
Memory memory = new AutoContextMemory(config, sessionId, model);

// 在 Agent 中使用
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();
```

### 完整示例

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.ContextOffloadTool;
import io.agentscope.core.memory.autocontext.LocalFileContextOffLoader;
import io.agentscope.core.tool.Toolkit;

// 配置
AutoContextConfig config = new AutoContextConfig();
config.setContextOffLoader(new LocalFileContextOffLoader("/tmp/context"));
config.setMsgThreshold(30);
config.setLastKeep(10);

// 创建内存
String sessionId = UUID.randomUUID().toString();
Memory memory = new AutoContextMemory(config, sessionId, model);

// 注册上下文重载工具（可选）
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ContextOffloadTool(config.getContextOffLoader()));

// 创建 Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .build();
```

## 存储实现

### MemoryStorage

AutoContextMemory 支持自定义存储实现：

- **InMemoryStorage**: 内存存储（默认）
- **FileSysMemoryStorage**: 文件系统存储

### ContextOffLoader

支持自定义上下文卸载器：

- **InMemoryContextOffLoader**: 内存卸载器（默认）
- **LocalFileContextOffLoader**: 本地文件卸载器

## API 文档

### 主要方法

#### `addMessage(Msg message)`
添加消息到内存。消息会同时添加到工作存储和原始存储。

#### `getMessages()`
获取消息列表。如果超过阈值，会自动触发压缩策略。

#### `deleteMessage(int index)`
删除指定索引的消息。

#### `clear()`
清空所有消息。

## 压缩策略详解

### Strategy 1: 压缩工具调用

当检测到历史对话中有超过 4 条连续的工具调用消息时：
1. 提取这些工具调用消息
2. 使用 LLM 进行智能压缩
3. 可选地将原始内容卸载到外部存储
4. 用压缩后的消息替换原始消息

### Strategy 2 & 3: 卸载大型消息

当消息内容超过 `largePayloadThreshold` 时：
1. 查找大型消息（在最近一次 assistant 之前）
2. 将原始内容卸载到外部存储
3. 用预览和卸载提示替换原始消息
4. 用户可以通过 `ContextOffloadTool` 重新加载内容

### Strategy 4: 总结历史轮次

对最近一次 assistant 之前的所有对话轮次：
1. 识别每个 user-assistant 对
2. 总结每轮对话（包括工具调用和 assistant 响应）
3. 用总结消息替换原始轮次
4. 保留卸载 UUID 以便后续重载

### Strategy 5: 压缩当前轮次

当历史已压缩但上下文仍超限时：
1. 找到最近的 user 消息
2. 合并其后所有消息（工具调用和结果）
3. 保留工具调用接口信息
4. 压缩工具结果，保留关键信息

## 最佳实践

1. **合理设置阈值**: 根据你的应用场景调整 `msgThreshold` 和 `maxToken`
2. **使用外部存储**: 对于生产环境，建议使用 `LocalFileContextOffLoader` 或自定义卸载器
3. **注册重载工具**: 注册 `ContextOffloadTool` 以便 Agent 可以重新加载卸载的内容
4. **监控日志**: 关注压缩策略的触发情况，优化配置参数
5. **保留重要信息**: 压缩策略会尽可能保留关键信息，但建议在重要对话前手动保存

## 注意事项

- 压缩操作会使用 LLM 模型，可能产生额外的 API 调用成本
- 压缩后的消息可能丢失部分细节，但会保留关键信息
- 原始消息始终保存在 `originalMemoryStorage` 中
- 卸载的内容可以通过 `ContextOffloadTool` 重新加载

## 依赖

AutoContextMemory 需要以下依赖：

- `io.agentscope:agentscope-core`
- 一个实现了 `Model` 接口的 LLM 模型（用于压缩和总结）

## 许可证

Apache License 2.0

