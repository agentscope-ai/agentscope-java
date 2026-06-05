---
title: "记忆（Memory）"
description: "双层长期记忆、对话压缩、大工具结果卸载"
---

## 作用

让 agent "记住跨会话的事实"，同时避免对话上下文无限增长。Harness 把记忆拆成两层：

- **第一层·日流水账** `memory/YYYY-MM-DD.md` —— 每天追加，原始且未去重；
- **第二层·策划后长期记忆** `MEMORY.md` —— 周期性 LLM 合并去重的产物；每轮推理时作为长期记忆注入 system prompt。

围绕这两层，还有三个常用机制：

- **对话压缩** —— 上下文太长时摘要历史、保留尾部；
- **上下文溢出兜底** —— 模型真的报错时强制压缩并重试；
- **大工具结果卸载** —— 单次工具返回过大时落盘 + 占位符。

## 两层记忆是怎么工作的

```{mermaid}
graph LR
    Conv["对话 messages"] -->|超阈值| Compactor["对话压缩"]
    Compactor -->|offload| Sess["sessions/&lt;id&gt;.log.jsonl"]
    Compactor -->|提炼新事实| Daily["memory/YYYY-MM-DD.md"]
    Daily -. 后台周期合并 .-> MEM["MEMORY.md"]
    MEM -->|每轮推理注入| SYS["system prompt"]
```

要点：

- 第一层只追加，不去重；第二层周期性整体重写；**两层互不覆盖**。
- 第二层永远是 LLM 注入提示的来源；第一层等待被合并。
- 对话被压缩前的原始消息会另存一份永不压缩的日志（`*.log.jsonl`），供事后审计或 `session_search`。

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
| `flushBeforeCompact` | `true` | 压缩前先把新事实写入日流水账 |
| `offloadBeforeCompact` | `true` | 压缩前先把原始消息存一份永不压缩的日志 |

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

- 把超过 90 天的日流水账归档到 `memory/archive/`
- 跑一次 `MEMORY.md` 合并
- 清理超过 180 天的会话日志

这些数字都可以调，但绝大多数项目不需要。

## 完全关掉

如果你想自己接管记忆 / 自己写工具：

```java
HarnessAgent.builder()
    ...
    .disableMemoryHooks()      // 关掉 flush + 后台维护
    .disableMemoryTools()      // 不注册 memory_search / memory_get / session_search
    .build();
```

## 相关文档

- [工作区](./workspace) — `MEMORY.md` / `memory/` 在工作区的位置
- [Context](./context) — 永不压缩的对话日志 `*.log.jsonl`
- [架构](./architecture) — 长会话事实如何沉淀进 `MEMORY.md`
