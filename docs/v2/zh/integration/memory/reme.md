# ReMe

`agentscope-extensions-reme` 用于接入自托管 ReMe 记忆服务。在 ReMe `0.4.x` 中，AgentScope-Java 通过 `auto_memory` job 写入过滤后的会话消息，通过 `search` job 检索相关上下文。

## 何时使用

- 你希望使用一个启动成本较低的自托管记忆服务。
- 你希望 ReMe 基于整段会话持续演化记忆，而不是只保存单条事实。
- 你可以通过 ReMe 的 `session_id` 或部署级隔离来管理记忆边界。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-reme</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速开始

```java
import io.agentscope.core.memory.reme.ReMeLongTermMemory;

ReMeLongTermMemory memory = ReMeLongTermMemory.builder()
    .sessionId("task-session")
    .apiBaseUrl("http://localhost:8002")
    .build();
```

`userId(String)` 仍然保留用于兼容旧代码，但在 ReMe `0.4.x` 下它只会作为 `session_id` 的回退值，不再映射到 `workspace_id`。

## 工作机制

- **写入（`record`）**：把过滤后的 `USER` / `ASSISTANT` 消息连同 `session_id` 一起发送到 `POST /auto_memory`。
- **检索（`retrieve`）**：把当前消息文本发送到 `POST /search`。如果 ReMe 返回非空 `answer`，直接使用；否则退化为拼接 `metadata.results[].text`。

写入时沿用与 Bailian 相同的过滤策略：

- 只保留 `USER` 和 `ASSISTANT` 消息。
- 跳过包含 `ToolUseBlock` 的助手消息。
- 跳过带有 `<compressed_history>` 标记的消息。

## Builder 参数

| 方法 | 是否必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `sessionId(String)` | 推荐 | - | ReMe `session_id`，用于写入会话消息 |
| `userId(String)` | 兼容旧代码 | - | 仅在未设置 `sessionId` 时作为 `session_id` 使用 |
| `apiBaseUrl(String)` | 是 | - | ReMe 服务地址，例如 `http://localhost:8002` |
| `timeout(Duration)` | 否 | `60s` | HTTP 请求超时 |

> ReMe `0.4.x` 已不再提供旧版 `workspace_id` 级别的 personal-memory API。如果你的业务需要严格的单用户隔离，建议使用独立的 ReMe workspace / 部署，或在 `session_id` 中显式编码隔离边界。
