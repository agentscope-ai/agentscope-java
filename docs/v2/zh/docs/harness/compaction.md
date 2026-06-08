---
title: "上下文压缩"
description: "在不丢失关键信息的前提下,把对话上下文控制在模型的 token 预算内"
---

:::{note}
本页讲的是**上下文压缩**——`HarnessAgent` 用来把对话控制在模型 token 预算内的几种策略。它建立在[上下文与 AgentState](../building-blocks/context) 所描述的无状态引擎和 `AgentState` 持久化基础之上。如果还没有读过那一页,建议先看——压缩操作的对象正是持久化链路保存和恢复的同一份 `AgentState`。

**两条链路如何配合**:压缩在内存里更新 `AgentState.contextMutable()`,状态存储在 call 结束时把更新后的 `AgentState` 整体持久化存储。两条路径独立但按顺序执行——状态存储拿到的永远是压缩后的版本。
:::

LLM 的 token 预算是有限的。一段对话越跑越长,要么主动压缩、要么撞到模型的硬上限报错。`HarnessAgent` 内置了一整套压缩链路,默认是关的,按需 `.compaction(...)` 或 `.toolResultEviction(...)` 开启。

## HarnessAgent 内置的几种策略

| 策略 | 解决的问题 | 触发时机 | 中间件 |
|------|----------|----------|--------|
| **对话摘要压缩** | 上下文太"深"——消息条数 / token 累计太多 | 每次模型推理前 | `CompactionMiddleware` |
| **大工具结果卸载** | 上下文太"宽"——单条工具结果体量过大 | 工具执行后 | `ToolResultEvictionMiddleware` |
| **上下文溢出兜底** | 真的撞到模型 `context_length_exceeded` | `call()` 抛错时 | `HarnessAgent.recoverFromOverflow` |
| **预压缩参数截断** | 工具调用参数(write_file 的内容)体量大但后期没人看 | 摘要之前的轻量预处理 | `CompactionConfig.TruncateArgsConfig` |

四套策略**正交,可以任意组合**,默认全部不开。

### 1. 对话摘要压缩 (`CompactionMiddleware`)

按消息条数或估算 token 触发,把对话**前缀**用一次 LLM 调用压成结构化摘要,**保留尾部 N 条最近消息原文**,然后把 `[summary] + [recent tail]` 写回 `AgentState.contextMutable()`。

```java
HarnessAgent.builder()
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)     // 30 条触发
        .keepMessages(10)        // 压缩后保留最近 10 条原文
        .build())
    .build();
```

默认摘要 prompt 会把内容组织成 `SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS` 四个小节,适合工程/编排类 agent。`CompactionConfig` 还支持 `.model(...)` 为压缩摘要指定独立模型（不设则用 agent 主模型）。完整字段表(`triggerTokens`、`keepTokens`、`flushBeforeCompact`、`offloadBeforeCompact`、`model`、`TruncateArgsConfig`)与摘要 prompt 模板在[记忆](./memory#开启压缩)文档里有详细列表,这里不重复。

### 2. 大工具结果卸载 (`ToolResultEvictionMiddleware`)

跟摘要压缩独立。当某条工具结果文本超过阈值(默认 80K 字符 ≈ 20K tokens),把全文写到工作区某个目录,上下文里**只保留首尾各约 2K 字符 + 一个 `read_file` 路径提示符**。agent 想看全文就自己 `read_file`。

```java
HarnessAgent.builder()
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

默认排除 `read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files` / `memory_*` / `session_search`——这些工具要么自带分页、要么返回值很小。**Shell `execute` 默认不排除**,因为命令输出可能非常大。

详情见[记忆 - 大工具结果卸载](./memory#大工具结果卸载)。

### 3. 上下文溢出兜底

如果模型直接返回 `context_length_exceeded` / `maximum context` / `token limit` 等错误,`HarnessAgent.recoverFromOverflow()` 会强制走一次 `triggerMessages=1` 的极端压缩,然后**自动重试一次**。前提是构造 agent 时配了 `.compaction(...)`,否则错误原样抛回上层。

这条兜底链路无需额外配置:只要 `compaction` 开了,溢出恢复就自动开。

### 4. 预压缩参数截断 (可选)

在 LLM 摘要前,先做一遍**不走 LLM** 的字符串截断——`write_file`、`edit_file` 这类工具的入参体量大但事后没人再看:

```java
CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000)
        .truncationText("... [truncated] ...")
        .build())
    .build();
```

很多场景下,光这一步就能把触发摘要的频率压下来一大截,几乎零成本。

## 压缩与 Memory 的联动

`CompactionConfig.flushBeforeCompact`(默认 `true`)决定**摘要发生前是否先把对话前缀里的事实抽取到长期记忆(Memory)中**——这一步由 `MemoryFlushMiddleware` + `MemoryFlushManager` 完成,会读 `<workspace>/MEMORY.md` 与 `memory/*.md`,把新事实增量写进去。等会儿摘要丢掉前缀消息时,信息不会随之消失——agent 仍可以通过 `memory_search` / `memory_get` 工具回头查。

类似地,`offloadBeforeCompact`(默认 `true`)在摘要前把**原始消息**整段写到永不压缩的 `*.log.jsonl`,供 `session_search` 检索。

> Memory 子系统的完整工作机制——双层结构、后台维护任务(归档、合并)、记忆工具——见 [记忆](./memory) 文档。压缩与 memory 是一对常常一起用的组件,但有各自独立的开关。

## 压缩不会触碰的内容

`ConversationCompactor` 只处理 `AgentState.contextMutable()` 里的**对话消息列表**。下面这些活在 `AgentState` 其他字段里,**完全不会被摘要压缩波及**:

- **Plan Mode 状态**(`AgentState.getPlanModeContext()`):是否在 plan 阶段、当前计划文件路径。计划文件本身在工作区 `plans/` 下,生命周期由 Plan Mode 自己管理。详见 [Plan Mode](./plan-mode)。
- **子 agent 后台任务**(`task_id`、状态、结果):住在 `<workspace>/agents/<parentAgentId>/tasks/<sessionId>.json` 里,由 `TaskRepository` 单独维护;主 agent 下一轮推理前通过 system reminder 反向注入完成结果,**不进入对话消息流**,所以摘要也无从压缩。详见 [子 Agent - 异步任务的存储位置](./subagent#异步任务的存储位置)。
- **`todo_write` 任务清单**(`AgentState.getTasksContext()`):独立字段,跟着 `AgentState` 一起持久化,但不参与对话压缩。详见 [Plan Mode - 与 `todo_write` 的协作](./plan-mode#与-todo_write-的协作)。
- **权限规则**(`getPermissionContext()`):独立字段,自带持久化。

这些组件各有自己的状态机和恢复机制,压缩通路对它们是透明的——你可以放心开启 `.compaction(...)` 而不用担心丢 plan / 丢未完成的后台 task。

## 用 agent 自己查历史会话

启用会话能力时(默认开),三个查询工具会自动注册,agent 自己就能调:

- `session_list agentId="..."` —— 列出某个 agent 的历史会话。
- `session_history agentId="..." sessionId="..." lastN=20` —— 看某次会话最近 N 条消息。
- `session_search query="..." agentId="..."` —— 在历史会话里关键词搜索。

这些工具读的是**永不压缩的对话日志**(`<workspace>/agents/<agentId>/sessions/<sessionId>.log.jsonl`),所以即使上下文已经被压缩成摘要,agent 也能查到原始消息。

---

## 相关文档

- [上下文与 AgentState](../building-blocks/context) —— 无状态引擎设计、`AgentState` 结构、状态持久化、`RuntimeContext`
- [架构](./architecture) —— Context、状态持久化、工作区在一次 call 内如何协作
- [记忆](./memory) —— 长期记忆、对话压缩的详细配置、大工具结果卸载、后台维护
- [Plan Mode](./plan-mode) —— plan 状态的独立持久化与恢复
- [子 Agent](./subagent) —— 后台任务的存储位置与跨节点恢复
- [文件系统](./filesystem) —— `userId` 多租户路径隔离
