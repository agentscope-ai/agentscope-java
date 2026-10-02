# 记忆（Memory）

`LongTermMemory` 是 AgentScope 用来在多轮、多会话之间持久化用户偏好、事实、要点的接口。`agentscope-extensions-*` 仓库下提供了对接主流记忆服务的开箱即用实现：

| 扩展 | 后端 | 适合场景 |
| --- | --- | --- |
| [Mem0](mem0.md) | [Mem0](https://mem0.ai/) 平台 / 自托管 | 通用语义记忆，支持多租户隔离与自定义 metadata 过滤 |
| [Bailian](bailian.md) | 阿里云百炼记忆服务 | 云上托管，支持 rerank / judge / rewrite 等高级特性 |
| [ReMe](reme.md) | 自托管 ReMe 服务 | 工作区级别记忆，支持轨迹（trajectory）摘要 |

三者都实现了同一个 `io.agentscope.core.memory.LongTermMemory` 接口，可通过 `ReActAgent.builder().longTermMemory(...)` 直接挂入 Agent，使用方式相同：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(memory)                       // 任选一种实现
    .longTermMemoryMode(LongTermMemoryMode.BOTH)  // 同时记录与检索
    .build();
```

## 选型建议

- **想要本地一把 `docker run` 跑起来 → Mem0** 或 **ReMe**
- **已经在阿里云百炼上有记忆库 → Bailian**
- **需要 metadata 过滤（按业务维度切分） → Mem0**
- **关注会话轨迹整体摘要 → ReMe**

每个实现仅在初始化参数和过滤模型上有差异，对 Agent 侧透明。详见各分页。
