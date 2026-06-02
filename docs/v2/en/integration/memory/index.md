# Memory

`LongTermMemory` is the AgentScope interface for persisting user preferences, facts, and key takeaways across multiple turns and sessions. The `agentscope-extensions-*` repository ships ready-to-use implementations for the major memory backends:

| Extension | Backend | Best for |
| --- | --- | --- |
| [Mem0](mem0.md) | [Mem0](https://mem0.ai/) Platform / self-hosted | General-purpose semantic memory with multi-tenant isolation and metadata filtering |
| [Bailian](bailian.md) | Alibaba Cloud Bailian memory service | Cloud-managed memory with rerank / judge / rewrite features |
| [ReMe](reme.md) | Self-hosted ReMe service | Workspace-level memory with trajectory summarization |

All three implement the same `io.agentscope.core.memory.LongTermMemory` interface and are wired into an Agent the same way:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(memory)                       // any of the three implementations
    .longTermMemoryMode(LongTermMemoryMode.BOTH)  // record AND retrieve
    .build();
```

## Choosing an implementation

- **Want a single `docker run` to start locally** → Mem0 or ReMe
- **Already on Alibaba Cloud Bailian** → Bailian
- **Need metadata filtering (slice memory by business dimension)** → Mem0
- **Care about end-to-end conversation trajectory summarization** → ReMe

The implementations only differ in initialization parameters and filter semantics; they are transparent to the Agent itself. See each subpage for details.
