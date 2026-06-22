---
title: "记忆（Memory）"
description: "双层长期记忆、对话压缩、大工具结果卸载，prompt 与触发策略均可定制"
---

## 作用

让 agent "记住跨会话的事实"，同时避免对话上下文无限增长。Harness 把记忆拆成两层：

- **第一层·日流水账** `memory/YYYY-MM-DD.md` —— 每天追加，原始且未去重；
- **第二层·策划后长期记忆** `MEMORY.md` —— 周期性 LLM 合并去重的产物；每轮推理时作为长期记忆注入 system prompt。

围绕这两层，还有三个常用机制：

- **对话压缩** —— 上下文太长时摘要历史、保留尾部；
- **上下文溢出兜底** —— 模型真的报错时强制压缩并重试；
- **大工具结果卸载** —— 单次工具返回过大时落盘 + 占位符。

## 三处 LLM 调用的全景

记忆管线里有 **三处独立的 LLM 调用**，每一处都有自己的 prompt 和触发时机。这是定制时最容易混淆的地方：

| # | 操作 | 写入目标 | Prompt 默认值 | 定制入口 |
|---|------|----------|---------------|----------|
| 1 | **Flush** —— 从对话窗口抽取长期事实 | `memory/YYYY-MM-DD.md`（追加） | `MemoryFlushManager.DEFAULT_FLUSH_PROMPT` | `MemoryConfig.builder().flushPrompt(...)` |
| 2 | **Consolidation** —— 把每日流水账合并到 `MEMORY.md` | `MEMORY.md`（整体重写） | `MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT` | `MemoryConfig.builder().consolidationPrompt(...)` |
| 3 | **Compaction summary** —— 把对话前缀蒸馏成一条摘要消息 | 注入到当前上下文 | `CompactionConfig.DEFAULT_SUMMARY_PROMPT` | `CompactionConfig.builder().summaryPrompt(...)` |

前两个是"沉淀长期记忆"，由 `MemoryConfig` 管；第三个是"压缩当下上下文"，由 `CompactionConfig` 管。三处 LLM 调用默认共享 agent 主模型，但 `MemoryConfig` 和 `CompactionConfig` 各自支持 `.model(...)` 覆盖，允许用更轻量的模型执行这些辅助操作。

## 两层记忆是怎么工作的

```{mermaid}
graph LR
    Conv["对话 messages"]
    Conv -->|每次调用结束 / 可节流| Flush["Flush LLM 调用"]
    Flush -->|提炼新事实| Daily["memory/YYYY-MM-DD.md"]
    Conv -->|超阈值| Compactor["对话压缩"]
    Compactor -->|offload 原文| Sess["sessions/&lt;id&gt;.log.jsonl"]
    Compactor -->|压缩前再 flush 一次| Flush
    Daily -. 节流后台 Consolidation .-> MEM["MEMORY.md"]
    MEM -->|每轮推理注入| SYS["system prompt"]
```

要点：

- 第一层只追加，不去重；第二层周期性整体重写；**两层互不覆盖**。
- 第二层永远是 LLM 注入提示的来源；第一层等待被合并。
- 对话被压缩前的原始消息会另存一份永不压缩的日志（`*.log.jsonl`），供事后审计或 `session_search`。

## Flush 的三个触发点

Flush（路径 1）会在以下三个时机被触发：

1. **每次 `call()` 结束** —— `MemoryFlushMiddleware` 的默认行为。可以用 `flushTrigger` 改成 `NEVER` 或 `THROTTLED(Duration)`。
2. **压缩前的预提取** —— `CompactionConfig.flushBeforeCompact = true`（默认）时，压缩对话前缀前先 flush 一次。
3. **上下文溢出兜底** —— 模型真的报 `context_length_exceeded` 时，框架做一次紧急压缩，连带 flush。

这三处用的是 **同一份** `flushPrompt`，定制后三处行为一致。

Flush 和 offload 都是**异步执行**的：它们在响应流结束后通过 `doOnComplete` 以 fire-and-forget 方式启动，不会阻塞当前 `call()` 的返回。换句话说，调用方拿到完整响应之后，flush LLM 调用和 JSONL offload 才在后台开始。

## 开启压缩

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)     // 消息条数到 30 触发
        .keepMessages(10)        // 压缩后保留最近 10 条
        .build())
    .build();
```

常用配置项：

| 参数 | 默认 | 含义 |
|------|------|------|
| `triggerMessages` | `50` | 按条数触发（`0` 表示关闭） |
| `triggerTokens` | `80_000` | 按 token 估算触发（`0` 表示关闭） |
| `keepMessages` | `20` | 保留尾部条数 |
| `keepTokens` | `0` | 非 0 时按 token 预算从尾部往前算，覆盖 `keepMessages` |
| `flushBeforeCompact` | `true` | 压缩前先把新事实写入日流水账（路径 2） |
| `offloadBeforeCompact` | `true` | 压缩前先把原始消息存一份永不压缩的日志 |
| `summaryPrompt` | 见 `DEFAULT_SUMMARY_PROMPT` | 路径 3 的摘要 prompt（必须含 `{messages}` 占位符） |
| `model` | `null`（使用 agent 主模型） | 压缩摘要使用的独立模型 |

**上下文溢出自动恢复**：模型真的返回 `context_length_exceeded` 等错误时，框架会强制做一轮压缩然后重试一次——前提是你配了 `compaction(...)`，否则错误直接抛回上层。

### 想再轻一些？预处理参数截断

`write_file` 这种工具调用，参数体量很大但后期没人再看。在 LLM 摘要之前，可以先做一个**不走 LLM** 的字符串截断：

```java
CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000)
        .truncationText("... [truncated] ...")
        .build())
    .build();
```

## 定制 Memory pipeline：`MemoryConfig`

`MemoryConfig` 集中管理 flush / consolidation 两条路径的 prompt、节流、保留时长，以及 per-call flush 的触发策略。所有字段都有默认值，不调 `.memory(...)` 时与历史行为完全一致。

### 例 1：节流 per-call flush，省 token

每次 agent 调用结束都做一次 flush LLM 调用，对长会话来说成本不低。把它节流到「最多每 10 分钟一次」：

```java
HarnessAgent.builder()
    ...
    .memory(MemoryConfig.builder()
        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
        .build())
    .build();
```

注意：

- `THROTTLED` 只影响**路径 1**（per-call flush）。压缩内嵌的 flush（路径 2）和兜底 flush（路径 3）按各自的触发条件照常跑——压缩很少发生，那两条本来就不频繁。
- **Offload 不受影响**，session JSONL 仍然每次写完整。`session_search` 和会话恢复正常工作。

### 例 2：完全关掉 per-call flush

```java
.memory(MemoryConfig.builder()
    .flushTrigger(MemoryConfig.FlushTrigger.never())
    .build())
```

这样只有压缩发生时才会 flush（成本和原始压缩成本一致）。

> 想把 flush + 后台维护**全部**关掉用 `.disableMemoryHooks()`；`flushTrigger(NEVER)` 只关 per-call flush，后台 consolidation 仍跑。

### 例 3：在默认 prompt 上追加项目规则

```java
.memory(MemoryConfig.builder()
    .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT + """

        Additional project rules:
        - Never record customer PII (names, emails, phone numbers).
        - Always use Chinese for project-internal vocabulary.
        """)
    .build())
```

### 例 4：完全自定义 consolidation prompt

```java
.memory(MemoryConfig.builder()
    .consolidationPrompt("""
        You are merging daily memory ledgers into MEMORY.md.
        Keep within %d tokens (~%d chars). Output the complete file in markdown.
        ... your custom rules ...
        """)
    .build())
```

> **重要**：自定义 consolidation prompt **必须** 包含恰好两个 `%d` 占位符（依次是 max-tokens 和 max-chars），否则 Builder 构造时就会拒绝。这是为了让错误尽早暴露，而不是等到运行时才抛 `MissingFormatArgumentException`。

### 例 5：调整后台维护节奏

```java
.memory(MemoryConfig.builder()
    .consolidationMinGap(Duration.ofHours(2))   // 后台合并最少 2 小时一次
    .dailyFileRetentionDays(30)                 // 30 天就归档
    .sessionRetentionDays(60)                   // 60 天后删 session JSONL
    .consolidationMaxTokens(8_000)              // MEMORY.md 上限放宽到 8K tokens
    .build())
```

### 例 6：用小模型跑记忆操作

flush 和 consolidation 不需要主推理模型那么强，用更便宜的模型省成本：

```java
HarnessAgent.builder()
    .model("openai:o3")                   // 主推理模型
    .memory(MemoryConfig.builder()
        .model("openai:gpt-4.1-mini")     // 记忆操作用小模型
        .build())
    .compaction(CompactionConfig.builder()
        .model("openai:gpt-4.1-mini")     // 压缩摘要也用小模型
        .build())
    .build();
```

`model(String)` 走 `ModelRegistry.resolve()`，也可以传 `Model` 实例。不设则 fallback 到 agent 主模型。

### `MemoryConfig` 字段速查

| 字段 | 默认 | 作用 |
|------|------|------|
| `model` | `null`（使用 agent 主模型） | flush / consolidation 使用的独立模型；支持 `Model` 实例或 `"provider:model"` 字符串 |
| `flushPrompt` | `null`（使用 `DEFAULT_FLUSH_PROMPT`） | 路径 1 的 SYSTEM prompt |
| `consolidationPrompt` | `null`（使用 `DEFAULT_CONSOLIDATION_PROMPT`） | 路径 2 的 prompt 模板（必须含两个 `%d`） |
| `consolidationMaxTokens` | `4_000` | `MEMORY.md` token 上限 |
| `consolidationMinGap` | `30 min` | 后台维护节流间隔 |
| `dailyFileRetentionDays` | `90` | 多少天后把日流水账归档到 `memory/archive/` |
| `sessionRetentionDays` | `180` | 多少天后清掉 `*.log.jsonl` |
| `flushTrigger` | `FlushTrigger.always()` | `ALWAYS` / `NEVER` / `THROTTLED(Duration)` |

## 大工具结果卸载

跟压缩独立。某次工具返回超过阈值时，全文写到一个目录、上下文里只留首尾预览 + 占位符——agent 想要全文就 `read_file`：

```java
HarnessAgent.builder()
    ...
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

默认行为：

- 超过 80K 字符触发
- 上下文里只保留首尾各约 2K 字符 + 一行"完整内容见 `{path}`"
- 默认排除 `read_file`（避免回读完又被卸载）

需要自己定阈值或卸载根目录用 `ToolResultEvictionConfig.builder()...build()`。

## 给 agent 自己用的记忆工具

启用记忆能力时，agent 自动获得两个工具：

- `memory_search query="..."` —— 关键词扫 `MEMORY.md` + `memory/*.md`，最多返回 30 条命中
- `memory_get path="memory/2026-06-02.md" startLine=10 endLine=40` —— 读指定行范围

模型在看到 `MEMORY.md` 已被截断的提示时通常会自己调 `memory_search` 找老内容。

## 后台维护

启用记忆能力时还会跑一个后台节流任务（每个 `call()` 结束时按最小间隔触发，默认 30 分钟一次最多）：

- 把超过 `dailyFileRetentionDays`（默认 90 天）的日流水账归档到 `memory/archive/`
- 跑一次 `MEMORY.md` 合并（consolidation）
- 清理超过 `sessionRetentionDays`（默认 180 天）的会话日志

所有阈值都可以通过 `.memory(MemoryConfig.builder()...)` 调，绝大多数项目不需要碰。

## 完全关掉

如果你想自己接管记忆 / 自己写工具：

```java
HarnessAgent.builder()
    ...
    .disableMemoryHooks()      // 关掉 flush + 后台维护
    .disableMemoryTools()      // 不注册 memory_search / memory_get / session_search
    .build();
```

`disableMemoryHooks()` 是核选项；只想节流不想关，用 `.memory(MemoryConfig.builder().flushTrigger(...).build())`。

## 相关文档

- [工作区](./workspace) — `MEMORY.md` / `memory/` 在工作区的位置
- [Context](./context) — 永不压缩的对话日志 `*.log.jsonl`
- [架构](./architecture) — 长会话事实如何沉淀进 `MEMORY.md`
