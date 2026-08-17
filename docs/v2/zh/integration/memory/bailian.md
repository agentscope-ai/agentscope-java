# 百炼记忆（Bailian Memory）

`agentscope-extensions-memory-bailian` 接入阿里云百炼的长期记忆服务，提供云端托管、企业级的语义记忆能力，支持 rerank、judge、rewrite 等检索增强特性。

## 何时使用

- 已经在使用阿里云百炼平台，希望直接复用平台上的记忆库。
- 关注检索质量，想启用百炼提供的 rerank / judge / rewrite 流水线。
- 需要按 `userId` + `memoryLibraryId` + `projectId` 三个维度做记忆隔离。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-memory-bailian</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.memory.bailian.BailianLongTermMemory;

try (BailianLongTermMemory memory = BailianLongTermMemory.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .userId("user_001")
        .memoryLibraryId("lib_xxxxx")
        .projectId("proj_xxxxx")
        .build()) {

    ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .longTermMemory(memory)
        .longTermMemoryMode(LongTermMemoryMode.BOTH)
        .build();

    agent.call(new UserMessage("帮我每天 9 点提醒喝水")).block();
}
```

`BailianLongTermMemory` 实现了 `AutoCloseable`，建议用 try-with-resources，确保底层 HTTP 连接被释放。

## 检索增强开关

百炼记忆服务在召回基础上还支持三个流水线开关：

```java
BailianLongTermMemory memory = BailianLongTermMemory.builder()
    .apiKey(apiKey)
    .userId("user_001")
    .memoryLibraryId("lib_xxxxx")
    .topK(20)
    .minScore(0.4)
    .enableRerank(true)   // 二次重排，更准但更慢
    .enableJudge(true)    // 让 LLM 判断结果是否真的相关
    .enableRewrite(true)  // 在写入时做改写、合并
    .build();
```

不需要的特性建议保持默认（关闭），可以减少调用延迟和费用。

## 消息过滤行为

百炼记忆只会写入用户和助手之间的"自然"对话：

- 仅写入 `MsgRole.USER` 与 `MsgRole.ASSISTANT` 消息。
- 含 `ToolUseBlock` 的助手消息（即工具调用请求）会被跳过。
- 含 `<compressed_history>` 标记的压缩历史消息也会被跳过，避免重复存储。

如果你希望让工具调用结果进入记忆，需要自己用更上层的逻辑写入，再调用 `record(...)`。

## Builder 配置参数

| 方法 | 是否必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `apiKey(String)` | ✅ | - | 百炼 DashScope API Key |
| `userId(String)` | ✅ | - | 用户维度 ID |
| `memoryLibraryId(String)` | ❌ | - | 记忆库 ID |
| `projectId(String)` | ❌ | - | 项目 ID |
| `profileSchema(String)` | ❌ | - | 用户画像 schema ID |
| `apiBaseUrl(String)` | ❌ | `https://dashscope.aliyuncs.com` | 自定义网关时使用 |
| `topK(Integer)` | ❌ | `10` | 检索返回条数上限 |
| `minScore(Double)` | ❌ | `0.3` | 最低相似度阈值（0~1） |
| `enableRerank(Boolean)` | ❌ | `false` | 是否启用 rerank |
| `enableJudge(Boolean)` | ❌ | `false` | 是否启用 LLM judge |
| `enableRewrite(Boolean)` | ❌ | `false` | 写入时是否启用 rewrite |
| `metadata(Map)` | ❌ | - | 写入时附带的自定义 metadata |
| `httpTransport(HttpTransport)` | ❌ | 默认实现 | 替换 HTTP 客户端 |
