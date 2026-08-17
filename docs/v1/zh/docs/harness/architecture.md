# Harness 架构

[概览](./overview.md) 从"解决什么问题"入手介绍 harness 的能力。本文换一个视角，**解释架构本身**：为什么这样设计、各层职责是什么、一次 `call()` 究竟经历了什么，以及状态如何在系统中流动。

---

## 1. 设计理念

理解 harness 架构需要先理解三个核心决策。

### 决策一：薄包装，不替换推理循环

`HarnessAgent` 不是一个新的推理引擎——它只是 `ReActAgent` 的**薄包装**，自身只做两件额外的事：

- **`bindRuntimeContext(ctx)`**：每次 `call()` 开头，把当次身份（`sessionId`、`userId`）分发给关心它的 hook，并按需从 Session 恢复 Memory 状态；
- **`forceCompactAndRetry`**：当模型真的返回 ContextOverflow 错误时，强制压缩并重试一次。

其余所有能力——工作区注入、记忆管理、会话持久化、子 agent 编排——全部通过 ReActAgent 已有的 **Hook** 和 **Toolkit** 扩展点注入。这样做的好处是：ReActAgent 的能力完整保留，harness 只叠加，不替换。

### 决策二：Hook 驱动，能力正交

每个 hook 只做一件事，通过 `priority` 在同一个事件上排好执行顺序：

- `CompactionHook(10)` 在推理前检查是否需要压缩历史；
- `SubagentsHook(80)` 在推理前注入子 agent 列表；
- `WorkspaceContextHook(900)` 最后叠加工作区文件——因为它是最终拼进 system prompt 的一层，需要在所有前置处理完成后才运行。

Hook 之间**不持有彼此的引用**，只通过三个共享对象通信。每项能力都能独立开关：`compaction` 需显式配置，`session persistence` 默认开启，`toolResultEviction` 按需启用。

### 决策三：共享对象是唯一耦合点

所有 hook 都通过同一组"通用语言"协作：

| 对象 | 职责 | 生命周期 |
|------|------|----------|
| `RuntimeContext` | 当次 `call()` 的身份：sessionId、userId、session 引用、extra 数据 | 每次 `call()` 重新注入，**不持久化** |
| `WorkspaceManager` | 工作区无状态访问器：两层读（filesystem 优先 → 本地兜底）、写走 filesystem | 构建时创建，跨 call 复用 |
| `AbstractFilesystem` | 存储后端：本地磁盘 / 沙箱 / KV Store，可插拔 | 构建时创建，跨 call 复用 |

---

## 2. 顶层架构图

```{mermaid}
graph TD
    USER(["调用方\nagent.call(msg, ctx)"])

    subgraph HA["HarnessAgent  ·  薄包装层"]
        BRC["① bindRuntimeContext(ctx)\n分发 ctx · loadIfExists 恢复 Memory"]

        subgraph RA["ReActAgent  ·  推理内核"]
            HOOKS["Hook 链\n按 priority 升序\n拦截生命周期事件"]
            LOOP["ReAct Loop\nreason → act → observe"]
            TK["Toolkit\nFilesystemTool · MemorySearch\nAgentSpawnTool · TaskTool · ..."]
            MEM["Memory\n(InMemoryMemory)"]
            HOOKS <-.->|事件驱动| LOOP
            LOOP <-->|工具调用| TK
            LOOP <-->|读写上下文| MEM
        end

        OVF["③ forceCompactAndRetry\nContextOverflow 兜底"]
        BRC --> RA --> OVF
    end

    subgraph SO["共享对象  ·  Hook 协作的通用语言"]
        RC["RuntimeContext\nsessionId / userId / extra"]
        WM["WorkspaceManager\nAGENTS · MEMORY · knowledge\nskills · subagents"]
        AFS["AbstractFilesystem\n本地 · 沙箱 · 远端 KV"]
    end

    USER -->|"② call(msg, ctx)"| BRC
    HOOKS <-->|"ctx + read/write"| SO
    TK <-->|"文件 / shell 操作"| AFS
    MEM <-.->|"session 持久化"| RC
```

**三层职责一眼看清**：

- **薄包装层**（HarnessAgent）：负责 per-call 的身份绑定与极端情况兜底；
- **推理内核**（ReActAgent）：负责 Hook 事件驱动 + ReAct 循环 + 工具执行；
- **共享对象层**：三个对象是所有 Hook 的协作底座，不属于任何 Hook，被所有 Hook 读写。

---

## 3. 构建阶段（`Builder.build()`）

能力注入发生在**一次性**的构建阶段，构建完成后运行期不再改变 hook 链或 toolkit 组成：

```{mermaid}
graph LR
    B["HarnessAgent.Builder.build()"]

    B -->|"创建"| SO2["三共享对象\nWorkspaceManager\nAbstractFilesystem\nRuntimeContext(ref)"]

    B -->|"按 priority 串好"| HK["Hook 链\n[0] AgentTraceHook\n[5] MemoryFlushHook\n[6] MemoryMaintenanceHook\n[10] CompactionHook ✗可选\n[50] SandboxLifecycleHook ✗可选\n[50] ToolResultEvictionHook ✗可选\n[80] SubagentsHook\n[900] WorkspaceContextHook\n[900] SessionPersistenceHook"]

    B -->|"追加内置工具"| TK2["Toolkit\n用户工具 + 内置工具\n(SubagentsHook 的工具在 tools() 里额外注册)"]

    B -->|"从 workspace/skills/ 装配"| SK["SkillBox\n自动 or AgentSkillRepository"]

    B -->|"交给"| RA2["ReActAgent.builder()\n→ 最终产物: delegate"]

    B -->|"启动后台"| BG["MemoryMaintenanceScheduler\n守护线程, 6h 周期"]
```

> **✗可选** 的 hook 只在满足条件时装配：`CompactionHook` 需调用 `.compaction(...)`；`SandboxLifecycleHook` 需 `filesystem(SandboxFilesystemSpec)`；`ToolResultEvictionHook` 需 `.toolResultEviction(...)`。

---

## 4. Hook 事件管道

`ReActAgent` 在 ReAct 循环的各个关键节点触发事件；Hook 在对应事件上**按 priority 升序**执行。下表是完整的 Hook × 事件矩阵：

| 事件 | 触发时机 | 触发的 Hook（priority 升序） |
|------|----------|------------------------------|
| `PreCallEvent` | 推理循环启动前 | `AgentTraceHook`(0) |
| `PreReasoningEvent` | **每次**调用模型前 | `AgentTraceHook`(0) → `CompactionHook`(10) → `SubagentsHook`(80) → `WorkspaceContextHook`(900) |
| `PostReasoningEvent` | 每次模型返回后 | `AgentTraceHook`(0) |
| `PreActingEvent` | 每个工具调用前 | `AgentTraceHook`(0) |
| `PostActingEvent` | 每个工具调用后 | `AgentTraceHook`(0) → `ToolResultEvictionHook`(50) |
| `PostCallEvent` | 最终回复产出后 | `AgentTraceHook`(0) → `MemoryFlushHook`(5) → `MemoryMaintenanceHook`(6) → `SessionPersistenceHook`(900) |
| `ErrorEvent` | 推理出现异常时 | `AgentTraceHook`(0) → `SessionPersistenceHook`(900) |

priority 的排布体现了设计意图：

- **0**：纯日志，最先运行，不干扰任何事件；
- **5/6/10**：记忆与压缩，在推理循环外围处理上下文生命周期；
- **50**：沙箱生命周期与工具结果卸载，在 acting 阶段就地处理；
- **80**：子 agent 注入，先于工作区注入——因为子 agent 信息需要出现在 system prompt 里；
- **900**：最后写 system prompt（WorkspaceContextHook）和持久化（SessionPersistenceHook）——保证它们叠加在所有前置处理之上，且记忆先 flush 再 snapshot。

---

## 5. `call()` 生命周期时序

```{mermaid}
sequenceDiagram
    autonumber
    actor User
    participant HA as HarnessAgent
    participant RA as ReActAgent
    participant H as Hooks (priority ↑)
    participant M as Model
    participant T as Toolkit

    User->>HA: call(msg, ctx)
    HA->>HA: ① bindRuntimeContext(ctx)<br/>分发 ctx · loadIfExists 恢复 Memory

    HA->>RA: delegate.call(msg)
    RA->>H: PreCallEvent → Trace(0)

    loop ReAct 推理循环 (直到无工具调用)
        RA->>H: PreReasoningEvent
        Note over H: Compact(10): 超阈值 → flushMemories + LLM distill + 替换 memory<br/>Subagents(80): 注入子 agent 列表<br/>WorkspaceCtx(900): 注入 AGENTS/MEMORY/KNOWLEDGE

        RA->>M: stream(messages)
        M-->>RA: ChatResponse

        RA->>H: PostReasoningEvent → Trace(0)

        opt 含 tool_calls
            loop 每个 tool_call
                RA->>H: PreActingEvent → Trace(0)
                RA->>T: invoke(toolCall)
                T-->>RA: ToolResult
                RA->>H: PostActingEvent
                Note over H: Eviction(50): 超 80K chars → 落盘 + 占位符替换
            end
        end
    end

    RA->>H: PostCallEvent
    Note over H: Trace(0) · MemFlush(5): flush facts + offload JSONL<br/>MemMaint(6): requestConsolidation<br/>Session(900): saveTo(session, key)

    RA-->>HA: final Msg
    HA-->>User: ② final Msg

    Note over HA: 失败路径: ErrorEvent → Session(900) saveTo<br/>ContextOverflow: ③ forceCompactAndRetry → delegate.call 重试
```

---

## 6. 状态流转

状态在 harness 里有三个层次，从短到长：

```{mermaid}
graph LR
    subgraph INCALL["调用内 (in-call)\n随 call() 开始 ↔ 结束"]
        IM["Memory\n(InMemoryMemory)\n当次对话消息序列"]
        RC2["RuntimeContext\nsessionId · userId · extra"]
    end

    subgraph CROSSCALL["跨调用 (cross-call)\n同 sessionId 下持久"]
        SP["WorkspaceSession\nagents/&lt;id&gt;/context/&lt;sess&gt;/*.json\nMemory 快照 + StateModule"]
        JSONL["sessions/&lt;sess&gt;.log.jsonl\n完整对话日志 (追加, 不压缩)"]
    end

    subgraph LONGTERM["长期记忆 (long-term)\n跨 session 积累"]
        DAILY["memory/YYYY-MM-DD.md\n每日事实流水账 (append-only)"]
        MMEM["MEMORY.md\n策划后长期记忆 (整体重写)"]
        FTS["memory_index.db\nSQLite FTS5 全文索引"]
    end

    IM -- "PostCallEvent\nMemoryFlushHook.flush()" --> DAILY
    IM -- "PostCallEvent\nSessionPersistenceHook.saveTo()" --> SP
    IM -- "压缩 / offload\nMemoryFlushHook.offload()" --> JSONL

    DAILY -- "后台 6h\nMemoryConsolidator" --> MMEM
    DAILY -- "增量写入后\nMemoryIndex" --> FTS

    SP -- "下次 call() 开头\nbindRuntimeContext + loadIfExists" --> IM

    MMEM -- "每次 PreReasoningEvent\nWorkspaceContextHook" --> IM
    FTS -- "agent 调用\nmemory_search 工具" --> IM
```

**核心规律**：
- `Memory` 是调用内的"工作内存"，随 `call()` 结束通过两条路持久化；
- `WorkspaceSession` 保证"下次同 sessionId 还记得这一轮"；
- `MEMORY.md` + FTS 索引保证"长期事实不随 session 丢失"。

---

## 7. 几个典型协作场景

### 场景 A — 工作区文件如何变成模型看到的 system prompt

```{mermaid}
sequenceDiagram
    participant RA as ReActAgent
    participant Hook as WorkspaceContextHook(900)
    participant WM as WorkspaceManager
    participant FS as AbstractFilesystem
    participant LD as 本地磁盘
    participant M as Model

    RA->>Hook: PreReasoningEvent
    Hook->>WM: readAgentsMd / readMemoryMd / readKnowledgeMd
    WM->>FS: read(path) 优先
    alt FS 命中非空
        FS-->>WM: 文件内容（多租户透明）
    else 未命中
        WM->>LD: Files.readString(workspace/...)
        LD-->>WM: 文件内容（兜底）
    end
    WM-->>Hook: AGENTS / MEMORY / KNOWLEDGE 内容
    Note over Hook: 内容包入 loaded_context XML，<br>合并到第一条 SYSTEM 消息
    Hook-->>RA: 修改后的 event
    RA->>M: stream(newMessages)
```

### 场景 B — 长会话里事实如何沉淀进 `MEMORY.md`

```{mermaid}
graph TD
    A["对话累积 → CompactionHook 阈值触发"] --> B["ConversationCompactor.compactIfNeeded"]
    B --> C["MemoryFlushManager.flushMemories(prefix)\n→ LLM 提炼新事实"]
    B --> D["offloadMessages\n→ sessions/&lt;sess&gt;.log.jsonl"]
    B --> E["LLM distill summary\n→ 替换 Memory + setInputMessages"]

    C --> C1["append to memory/YYYY-MM-DD.md"]
    C --> C2["MemoryIndex.indexFromString (FTS5 增量)"]
    C --> C3["scheduler.requestConsolidation()"]

    C3 -- "30min 节流" --> C4["submit consolidateMemory"]
    C4 --> C5["MemoryConsolidator + LLM\n读旧流水账 + 当前 MEMORY.md"]
    C5 --> C6["覆盖写 MEMORY.md"]
    C6 --> NEXT["下次 call\nWorkspaceContextHook 读新 MEMORY.md\n→ 注入 system prompt"]
```

### 场景 C — 同一 sessionId 如何跨调用"记住"历史

```{mermaid}
graph LR
    subgraph T1["第一轮 call(msg1, ctx{sess=A})"]
        A1["bindRuntimeContext\nloadIfExists → Memory 为空（首次）"] --> B1["ReAct 循环"]
        B1 --> C1["PostCallEvent\nMemoryFlushHook: flush + offload\nSessionPersistenceHook: saveTo → 写盘"]
    end

    subgraph T2["第二轮 call(msg2, ctx{sess=A})"]
        A2["bindRuntimeContext\nloadIfExists → 读 context/A/memory.json\n恢复第一轮对话到 Memory"] --> B2["ReAct 循环\n(已知第一轮内容)"]
        B2 --> C2["PostCallEvent → 写盘（覆盖）"]
    end

    C1 -. "context/A/memory.json" .-> A2
```

### 场景 D — 主 agent 委派子 agent：同步与后台两条路径

```{mermaid}
sequenceDiagram
    participant Parent as 父 Agent
    participant Hook as SubagentsHook
    participant Sub as 子 HarnessAgent (leaf)
    participant Repo as TaskRepository
    participant Exec as Executor

    rect rgb(235, 245, 255)
    Note over Parent,Sub: 同步路径（agent_send / timeout_seconds > 0）
    Parent->>Hook: agent_send(agent_id, message)
    Hook->>Sub: factory.create() · sub.call(msg).block()
    Sub-->>Hook: reply
    Hook-->>Parent: ToolResultBlock(reply)
    end

    rect rgb(255, 245, 235)
    Note over Parent,Exec: 后台路径（agent_spawn + timeout_seconds=0）
    Parent->>Hook: agent_spawn(agent_id, task, timeout=0)
    Hook->>Repo: putTask(taskId, supplier)
    Repo->>Exec: submit(supplier) → 立即返回
    Hook-->>Parent: ToolResultBlock(taskId)

    Note over Parent: 后续轮轮询
    Parent->>Hook: task_output(taskId, block=false)
    Hook->>Repo: getTask(taskId)
    Repo-->>Hook: RUNNING / result
    Hook-->>Parent: 状态 / 最终结果
    end
```

---

## 延伸阅读

- [Workspace](./workspace.md) — 工作区目录结构、WorkspaceManager 两层读写细节
- [Memory](./memory.md) — 双层记忆模型、压缩配置、FTS5 检索
- [Filesystem](./filesystem.md) — AbstractFilesystem 三种模式与扩展方式
- [Subagent](./subagent.md) — 子 agent 声明格式、TaskRepository、五行判定表
- [Session](./session.md) — WorkspaceSession / JsonSession 序列化协议
- [Tool](./tool.md) — 内置工具参考与注册方式
