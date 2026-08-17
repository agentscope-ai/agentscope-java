# Mem0

`agentscope-extensions-mem0` 接入 [Mem0](https://mem0.ai/) 记忆服务，提供基于向量检索 + LLM 抽取的长期记忆能力，支持 Mem0 Platform、自托管以及本地部署三种模式。

## 何时使用

- 需要给 Agent 加上跨会话的事实记忆，例如用户偏好、历史决策。
- 想用 `agentId / userId / runId` 三层 metadata 隔离不同租户、不同 session 的记忆。
- 想用自定义 metadata（例如 `category=travel`）做检索过滤。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mem0</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.memory.mem0.Mem0ApiType;

// 1. 构造记忆实例（本地无鉴权部署）
Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
    .agentName("Assistant")
    .userId("user_123")
    .apiBaseUrl("http://localhost:8000")
    .apiType(Mem0ApiType.SELF_HOSTED)
    .build();

// 2. 挂到 Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(memory)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)
    .build();

// 3. 正常调用，记忆会自动在 record / retrieve 之间往返
agent.call(new UserMessage("我出差喜欢住民宿")).block();
```

## 部署模式

`Mem0ApiType` 决定客户端调用的 URL 与鉴权方式：

| 枚举 | 用途 | apiBaseUrl 示例 | apiKey |
| --- | --- | --- | --- |
| `PLATFORM`（默认） | 直连 Mem0 SaaS | `https://api.mem0.ai` | 必填 |
| `SELF_HOSTED` | 自托管 Mem0 Server | `http://your-host:8000` | 视部署而定 |

## 多租户隔离

`agentName / userId / runName` 是 Mem0 用来组织记忆的三层 ID：

```java
Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
    .agentName("travel-bot")     // 跨用户共享的 Agent 维度记忆
    .userId("alice")             // 用户维度（租户）
    .runName("trip-2026-spring") // 单次会话维度
    .apiBaseUrl("http://localhost:8000")
    .build();
```

至少要提供其中之一，全部为空时 `build()` 抛出 `IllegalArgumentException`。检索时只会返回 metadata 完全匹配的记忆。

## 自定义 metadata 过滤

`metadata(...)` 同时影响写入和读取：写入时随记忆持久化，检索时作为 filter 注入。

```java
Map<String, Object> tags = Map.of(
    "category", "travel",
    "project_id", "proj_001"
);

Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
    .agentName("Assistant")
    .userId("user_123")
    .apiBaseUrl("http://localhost:8000")
    .metadata(tags)
    .build();

// 之后所有 record() 都带上 tags，retrieve() 也只命中相同 tags 的记忆
```

适合按项目、按业务线切分知识；如果只是按用户切分，用 `userId` 就够了。

## Builder 配置参数

| 方法 | 是否必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `apiBaseUrl(String)` | ✅ | - | Mem0 服务地址 |
| `apiKey(String)` | 视部署 | - | API Key；本地无鉴权部署可省略 |
| `apiType(Mem0ApiType)` | ❌ | `PLATFORM` | 选择 SaaS 或自托管路由 |
| `agentName(String)` | 三选一 | - | Agent 维度 ID |
| `userId(String)` | 三选一 | - | 用户维度 ID |
| `runName(String)` | 三选一 | - | 会话/运行维度 ID |
| `metadata(Map)` | ❌ | `null` | 写入与检索时的额外 filter |
| `timeout(Duration)` | ❌ | `60s` | HTTP 请求超时 |

> 三个 ID（agentName / userId / runName）至少要填一个，否则 `build()` 抛 `IllegalArgumentException`。
