# Bailian Memory

`agentscope-extensions-memory-bailian` integrates with Alibaba Cloud Bailian's long-term memory service. It is fully managed and supports advanced retrieval features such as rerank, judge, and rewrite.

## When to use

- You are already on Alibaba Cloud Bailian and want to reuse memory libraries from the platform.
- You care about retrieval quality and want to use Bailian's rerank / judge / rewrite pipeline.
- You need three-level isolation via `userId` + `memoryLibraryId` + `projectId`.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-memory-bailian</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

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

    agent.call(new UserMessage("Remind me to drink water at 9am every day")).block();
}
```

`BailianLongTermMemory` implements `AutoCloseable` — try-with-resources is recommended so the underlying HTTP connections get released.

## Retrieval feature switches

On top of basic recall, Bailian supports three pipeline switches:

```java
BailianLongTermMemory memory = BailianLongTermMemory.builder()
    .apiKey(apiKey)
    .userId("user_001")
    .memoryLibraryId("lib_xxxxx")
    .topK(20)
    .minScore(0.4)
    .enableRerank(true)   // Re-rank results, more accurate but slower
    .enableJudge(true)    // Let an LLM judge whether results are actually relevant
    .enableRewrite(true)  // Rewrite/merge memories on write
    .build();
```

Leave them off by default unless you need them — they add latency and cost.

## Message filtering

Bailian memory only stores natural user/assistant exchanges:

- Only `MsgRole.USER` and `MsgRole.ASSISTANT` messages are written.
- Assistant messages containing `ToolUseBlock` (tool-call requests) are skipped.
- Messages with the `<compressed_history>` marker are skipped to avoid storing duplicated compressed history.

If you need tool results to enter memory, write them yourself via higher-level logic before calling `record(...)`.

## Builder reference

| Method | Required | Default | Notes |
| --- | --- | --- | --- |
| `apiKey(String)` | ✅ | - | Bailian DashScope API key |
| `userId(String)` | ✅ | - | User-level ID |
| `memoryLibraryId(String)` | ❌ | - | Memory library ID |
| `projectId(String)` | ❌ | - | Project ID |
| `profileSchema(String)` | ❌ | - | User profile schema ID |
| `apiBaseUrl(String)` | ❌ | `https://dashscope.aliyuncs.com` | Override for custom gateways |
| `topK(Integer)` | ❌ | `10` | Maximum number of retrieved items |
| `minScore(Double)` | ❌ | `0.3` | Minimum similarity threshold (0–1) |
| `enableRerank(Boolean)` | ❌ | `false` | Enable rerank |
| `enableJudge(Boolean)` | ❌ | `false` | Enable LLM judge |
| `enableRewrite(Boolean)` | ❌ | `false` | Enable rewrite on write |
| `metadata(Map)` | ❌ | - | Custom metadata stored with each memory |
| `httpTransport(HttpTransport)` | ❌ | default | Replace the HTTP client |
