# ReMe

`agentscope-extensions-reme` 接入自托管的 ReMe 记忆服务，特点是基于 **trajectory（对话轨迹）** 抽取长期记忆，并按 **workspace** 隔离。

## 何时使用

- 想要一个轻量的本地记忆服务，启动门槛低。
- 关注整段对话轨迹的摘要，而不是单条消息级的存储。
- 用 `userId` 表达逻辑工作区，每个用户一份独立记忆。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-reme</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.memory.reme.ReMeLongTermMemory;

ReMeLongTermMemory memory = ReMeLongTermMemory.builder()
    .userId("task_workspace")            // 映射到 ReMe 的 workspace_id
    .apiBaseUrl("http://localhost:8002") // 你的 ReMe Server 地址
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(memory)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)
    .build();
```

`userId` 在 ReMe 里实际作为 `workspace_id`，这是 ReMe 用来切分记忆的最小单位。

## 工作机制

- **写入（record）**：把过滤后的对话拼成一个 `ReMeTrajectory`，作为整体送给 ReMe 的 `add` 接口；服务端再用 LLM 把轨迹抽取成可检索的记忆片段。
- **检索（retrieve）**：以当前消息为 query 调用 ReMe 的 `search`，优先返回服务端聚合的 `answer`，没有时退化为多个 memory 片段拼接。

写入时遵循与 Bailian 相同的过滤策略：

- 只保留 `USER` 与 `ASSISTANT` 消息。
- 跳过含 `ToolUseBlock` 的助手消息（工具调用请求不入记忆）。
- 跳过含 `<compressed_history>` 标记的压缩历史。

## Builder 配置参数

| 方法 | 是否必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `userId(String)` | ✅ | - | 工作区 ID（写入和检索都基于它） |
| `apiBaseUrl(String)` | ✅ | - | ReMe 服务地址，例如 `http://localhost:8002` |
| `timeout(Duration)` | ❌ | `60s` | HTTP 请求超时 |

> ReMe 暂未提供更细粒度的 metadata 过滤；如果需要按业务标签切分，建议在 `userId` 里编码命名空间（例如 `tenant-a:project-1`）。
