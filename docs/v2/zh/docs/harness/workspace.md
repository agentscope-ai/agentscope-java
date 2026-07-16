---
title: "工作区（Workspace）"
description: "智能体定义与进化的 source of truth：目录布局、工作区与 API 的等价关系、原生多租户隔离、filesystem 模式、重点内容深入"
---

## 设计理念

工作区（workspace）是 `HarnessAgent` **智能体定义与进化的 source of truth**。智能体"是什么"以及"在运行中学到了什么"，全部以一个目录 + 普通 Markdown / JSON 文件来组织——不散落在代码里，不绑死在某个数据库表上。

它的设计有四条线索：

**1. 智能体定义与进化的 source of truth。**

智能体的*定义*（它是谁、怎么行事）可以完全在工作区中声明：

| 要定义的内容 | 文件 |
|------------|------|
| 人格、行为约定、系统指令 | `AGENTS.md` |
| 领域知识 | `knowledge/KNOWLEDGE.md` + 参考文件 |
| 技能（可复用能力包） | `skills/<skill-name>/SKILL.md` |
| 子 agent 声明 | `subagents/<agent-id>.md` |
| 工具白名单 + MCP server | `tools.json` |

> **以上全部可选。** 每个工作区文件都有完全对等的 API 配置方式：你可以通过 builder 方法（`.systemPrompt(...)`、`.skill(SkillDeclaration...)`、`.subagent(SubagentDeclaration...)`、`.toolsConfig(...)` 等）传入同等配置。工作区与 API 始终等价——用哪种完全取决于你。
>
> **那为什么还要用工作区？** 因为把"定义"表达成文件（而不是代码），正是让一个 agent 天然多租户的关键：*同一套* agent 逻辑，可以为*不同用户*携带不同的人格、知识库、技能集——只需放一个用户级覆盖目录，无需代码分支、无需多套部署。详见下文 [同一套 agent 逻辑，按用户定制](#同一套-agent-逻辑按用户定制)。

智能体的*进化*（跨会话累积学到的一切）由框架自动写入工作区，无需手动管理生命周期：

- **长期记忆**（`MEMORY.md` + `memory/`）—— 从对话中提取的事实，由后台任务维护与压缩，每轮注入 system prompt。
- **自学习技能**（`skills/`）—— agent 从成功模式中起草新技能；经过可选的审批闸门后成为可复用能力，再由后台 curator 把长期未用的老化、归档。
- **计划文件**（`plans/`）—— Plan Mode 中写下的计划会持久化、跨调用保留，让"想清楚"与"做出来"解耦。
- **工具结果落盘**（compaction）—— 超大工具输出写到磁盘，上下文里只留 head/tail 预览 + `read_file` 指针，agent 之后可按需重读而不撑爆 prompt。
- **会话日志**（`agents/<agentId>/sessions/`）—— 永不压缩的完整对话日志，随时可查。

这些进化数据默认长期有效：记忆无限累积，会话日志只追加不自动清除。每条通道如何产生与维护，详见下文 [智能体如何进化](#智能体如何进化)。

（每次调用的易失*运行期上下文* —— `AgentState` —— 不在这张清单里：它是在途对话的恢复快照，单独存放在 `AgentStateStore`，从不进工作区。详见下方第 2 条下的提示框。）

**2. 内容按生命周期分三类，互不混淆。**

| 类型 | 谁写 | 谁读 | 例子 |
|------|------|------|------|
| **静态资产**（工程师编辑） | 你 / 团队 | 框架每轮注入 system prompt 或调用时按需读 | `AGENTS.md`、`knowledge/`、`skills/`、`subagents/`、`tools.json` |
| **运行时文件**（每次 call 写回） | 框架 / agent | 框架下次 call 时还原 | `agents/<agentId>/sessions/`、`agents/<agentId>/tasks/`、`plans/` |
| **长期记忆**（跨会话累积） | agent + 后台任务 | 框架每轮注入 system prompt + agent 用工具查询 | `MEMORY.md`、`memory/YYYY-MM-DD.md` |

混在一棵树里只是为了部署方便（一个目录拷贝走就是完整 agent），框架内部走不同的读写路径。

> **`AgentState` 不是工作区内容——别把两者混为一谈。** agent 中途恢复对话所需的在途上下文（对话缓冲、滚动摘要、权限 / 工具 / 任务 / Plan-Mode 子上下文，以及指向工作区产物的*元数据*，例如当前激活的计划文件）会被序列化成一份 `AgentState` 文档，存进**独立子系统 `AgentStateStore`**（默认 `~/.agentscope/state/<agentId>/`，完全在工作区树之外）。这是刻意的拆分：工作区保存持久的*文件产物*（永不压缩的会话日志、计划 markdown、任务记录、记忆），而 `AgentState` 保存易失的*运行期上下文 + 工作区元数据*。两个存储、两套生命周期——详见 [Context](../building-blocks/context.md)。

**3. 原生多租户隔离。** 工作区数据（记忆、会话、任务、技能、沙箱状态）由单一的 `IsolationScope` 分桶——无需应用层手写任何分区逻辑。Scope 决定谁和谁共享一个桶：

| `IsolationScope` | 谁共享一个桶 | 典型场景 |
|------------------|------------|---------|
| `SESSION` | 每个 `sessionId` 完全隔离 | 按会话隔离；一次性沙箱 |
| `USER`（默认） | 同一 `userId` 的所有会话 | 用户的多个会话共享长期记忆 / 技能（`userId` 缺失时降级为 `SESSION`） |
| `AGENT` | 该 agent 的所有用户与会话 | 共享知识库型 agent |
| `GLOBAL` | 整个 store 实例共用一个桶 | 慎用——所有 agent/用户争抢同一槽位 |

选定的 scope 在不同 filesystem 模式下落地方式不同（本机为路径前缀、共享存储为 KV 命名空间、沙箱为状态 slot）。完整语义、降级规则、并发说明见 [filesystem — IsolationScope](./filesystem.md#isolationscope--多用户与多副本怎么分桶)。

> `IsolationScope` 管的是上面**工作区 / filesystem** 的分桶。`AgentState` 有自己正交的寻址方式：无论哪种 scope，它始终按 `(userId, sessionId)` 存进 `AgentStateStore`。

单个 `HarnessAgent` 实例可服务数千并发用户，用户间数据零泄漏。

**4. 工作区与 filesystem 解耦。** 同一份目录布局可以落在三种地方：本机磁盘、共享 KV 存储（Redis / JDBC）、沙箱容器。这是 `HarnessAgent` 能"代码不动、部署形态切换"的根因。详细见 [filesystem](./filesystem.md) 的三种模式。

## 工作区目录布局

```
.agentscope/workspace/
├── AGENTS.md                    ← 静态：人格 + 行为约定
├── MEMORY.md                    ← 长期记忆：策划后的长期事实
├── tools.json                   ← 静态：MCP server + 工具白名单（可选）
├── memory/                      ← 长期记忆：每天追加的事实流水账
│   └── YYYY-MM-DD.md
├── knowledge/                   ← 静态：领域知识入口 + 任意参考文件
│   ├── KNOWLEDGE.md
│   └── ...
├── skills/                      ← 静态：技能目录，每个子目录一份 SKILL.md
│   └── <skill-name>/SKILL.md
├── subagents/                   ← 静态：子 agent 声明（文件名即 agent_id）
│   └── <agent-id>.md
├── plans/                       ← 运行时：Plan Mode 写下的计划文件
│   └── PLAN.md
└── agents/<agentId>/            ← 运行时：每个 agent 自己的运行时根
    ├── sessions/                ← 运行时：会话索引 + 永不压缩对话日志
    │   ├── sessions.json
    │   └── <sessionId>.log.jsonl
    └── tasks/                   ← 运行时：子 agent 后台任务记录
        └── <sessionId>.json
```

> **这棵树是*逻辑*布局，不是固定的磁盘路径。** 它画成 `.agentscope/workspace/...`，但那只是默认的本机落点。同一份布局可以物理上落在**本机磁盘**、落在**远端分布式存储**（Redis / JDBC / OSS，通过 `RemoteFilesystemSpec`），或**映射进沙箱容器**（`SandboxFilesystemSpec`）——下面这些相对路径在三种模式下完全一致，变的只是后端存储，你的 agent 代码不用动。用 [filesystem](./filesystem.md) 选后端；本文其余内容都是按这套逻辑布局来写的。

**唯一你真正需要写的是 `AGENTS.md`**（不写也能跑，只是少一段人格注入）。其他目录在你启用对应能力时自动出现：

- 启用记忆压缩（`.compaction(...)`）→ `memory/` + `MEMORY.md`
- 放子 agent spec → `subagents/`
- 装技能 → `skills/`
- 启用 Plan Mode → `plans/`
- 任何 `call()` 跑过一次 → `agents/<agentId>/`

## Builder 配置

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))   // 不传则用 ${user.dir}/.agentscope/workspace
    .additionalContextFile("SOUL.md")                // 任意 workspace 相对路径，全文注入
    .additionalContextFile("PREFERENCES.md")
    .maxContextTokens(8000)                          // MEMORY 注入预算
    .build();
```

`AGENTS.md` 至少写一份骨架：

```markdown
# MyAgent

你是 XX 助手，遵循以下行为约定。

## 行为
- ...
- ...
```

可关掉的子系统（生产很少用，调试或自管时有用）：

| 方法 | 关掉的是 |
|------|---------|
| `disableWorkspaceContext()` | system prompt 注入（`AGENTS.md` / `MEMORY.md` / `knowledge/`） |
| `disableMemoryHooks()` | 记忆 flush + 后台维护 |
| `disableMemoryTools()` | `memory_search` / `memory_get` / `session_search` 工具 |
| `disableSubagents()` | 整个子 agent 子系统 |
| `disableDynamicSkills()` | 每轮重新合并技能；改成 build 时一次 |
| `disableToolsConfig()` | 不读 `tools.json` |
| `disableSessionPersistence()` | AgentState 自动持久化 |

## 工作区内容如何被加载

因为工作区是逻辑布局（见上方提示框），"加载"从不假设它是一个普通本机目录——每次读取都经过配置的 `AbstractFilesystem`，所以无论文件落在本机磁盘、远端存储还是沙箱里，同一套逻辑都成立。下面的[两层读](#两层读架构filesystem-first--本地兜底)正是把这种"与后端无关"落到实处的机制；各模式如何在物理上解析路径，见 [filesystem](./filesystem.md)。

### 一次推理的 system prompt 拼装

每次 `call()` 进入 reasoning 阶段时，`WorkspaceContextMiddleware`（位于 `io.agentscope.harness.agent.middleware`）会按下表把工作区文件拼成一段文本，**追加到** builder 上配置的 `sysPrompt` 之后形成最终 system 消息：

| 段落 | 来源 | 受预算约束 |
|------|------|-----------|
| `## Session Context` | 模板生成（日期、操作系统、workspace 绝对路径、临时目录、当前 `sessionId`） | 否 |
| `## Domain Knowledge` / `## Memory Recall` / `## Memory Persistence` 引导段 | 内置模板（教模型怎么用记忆 + 怎么查 knowledge） | 否 |
| `## Workspace` 段 | 模板生成，**按 filesystem 模式分支**（详见下面）—— 告诉模型自己跑在本机 / 沙箱 / 远端 | 否 |
| `## Workspace Files (Injected)` 段 | 框架自动从工作区把以下文件拉成 `<loaded_context>` XML 块注入 | 见下 |
| `<agents_context>` | `AGENTS.md` 全文 | 无限 |
| `<memory_context>` | `MEMORY.md`（剩余预算下，超出按字符截断 + 提示「用 memory_search 查更早」） | `maxContextTokens` 默认 8000 |
| `<domain_knowledge_context>` | `knowledge/KNOWLEDGE.md` 全文 + `knowledge/` 下所有文件路径列表 | 无限（仅文件名做索引） |
| `<x_md>` / `<y_md>` | 你 `additionalContextFile("X.md")` 添加的任意文件 | 无限 |

要点：

- **每轮都重新拼。** 你改了 `AGENTS.md` 或 `MEMORY.md`，下一次 `call()` 立刻生效，不需要重启或重建 agent。
- **`MEMORY.md` 估算 token 后才注入。** 超出剩余预算就按字符截断并附一行提示，引导模型用 `memory_search` 工具查老内容。
- **`knowledge/` 是目录索引 + 入口文件**。完整内容不会全量塞进 prompt——只把 `KNOWLEDGE.md` 全文加上其它文件的路径清单注入，让模型用 `read_file` 自己取需要的。

### 两层读架构（filesystem-first + 本地兜底）

对所有"被注入到 prompt 的关键文件"（`AGENTS.md` / `MEMORY.md` / `knowledge/KNOWLEDGE.md` / `additionalContextFile`），`WorkspaceManager.readWithOverride()` 都走**两层读**：

```
1. 问当前配置的 AbstractFilesystem：有没有这个 relative path？
   ├─ 有 → 返回该内容（"覆盖"层）
   └─ 没有 → 走第 2 步
2. 读本地磁盘 workspace.resolve(relativePath)
```

写入永远走第 1 层（filesystem 后端），从不直接落到本地磁盘。

这套两层读的价值在**共享存储模式**最明显：第一个副本启动时本地磁盘上有团队 git 同步过来的 `AGENTS.md` 模板，立刻可用；之后任何节点对 `AGENTS.md` 的覆盖（例如管理台编辑）会写到共享 KV，所有副本下一次 `call()` 就读到最新版本。模板是 fallback，远端覆盖是事实。

### 多用户同一工作区时的覆盖优先级

`RuntimeContext.userId` 是切多用户的钥匙——让同一个 agent 实例服务多个用户而互不串读。

对**运行时数据**（sessions / tasks / memory），框架按 `NamespaceFactory` 配的命名空间给路径加前缀（本机模式是路径前缀、远端模式是 KV 命名空间、沙箱模式是状态 slot）。详见下一节"运行时数据与 Memory 怎么存"。

对**静态资产**（特别是 `skills/` 和 `subagents/`），用户级目录可以**覆盖**工作区共用版：

```
workspace/
├── skills/code-reviewer/SKILL.md     ← 共用版（所有人可见）
├── subagents/researcher.md           ← 共用版
└── alice/
    ├── skills/
    │   └── code-reviewer/
    │       └── SKILL.md              ← 只对 alice 生效，覆盖共用版
    └── subagents/
        └── researcher.md             ← 只对 alice 生效
```

调用时只要 `RuntimeContext.userId="alice"`，框架就会先看 `alice/skills/code-reviewer/`，没有再退到 `skills/code-reviewer/`。下层独有的 skill 仍然可见，只在重名时被上层挡住。完整优先级表参考 [技能 — 同名冲突谁说了算](./skill.md#同名冲突谁说了算)。

#### 同一套 agent 逻辑，按用户定制

这套覆盖机制正是让**单个 `HarnessAgent` 实例对每个租户表现得像一个不同的 agent** 的根本——不用 fork 代码、不用多套部署。你只交付一份二进制、一份 agent 定义；每个用户在共享底座之上拿到属于自己的那一层：

| 用户级层 | 定制什么 | 解析方式 |
|---------|---------|---------|
| `<userId>/AGENTS.md`（通过覆盖） | 该用户的人格 / 行为 | 两层读的上层（共享 `AGENTS.md` 作兜底） |
| `<userId>/knowledge/` | 该用户可见的领域知识 | 用户级目录，共享 `knowledge/` 作基线 |
| `<userId>/skills/` | 只对该用户解锁的能力 | 覆盖同名共享技能，独有的叠加可见 |
| `<userId>/subagents/` | 只有该用户能 spawn 的子 agent | 覆盖同名共享声明 |
| 运行时数据（记忆 / 会话 / 任务） | 该用户累积的进化数据 | 按 `userId` 进命名空间（路径前缀 / KV 命名空间 / 沙箱 slot） |

效果是**两层多租户同时生效**：*定义*按用户不同（靠覆盖目录），*进化*按用户隔离（靠命名空间）。共享底座对所有人通用，而每个用户的定制与学到的状态都不会跨租户泄漏——全部出自同一个 agent 进程。这正是前面 [「以上全部可选」](#设计理念) 强调的、把定义当数据带来的红利：因为定义就是数据，按用户定制只是多一个文件，而不是多一条代码分支。

### 三种 filesystem 模式下的加载行为

工作区只是"逻辑布局"，物理落点由 [filesystem](./filesystem.md) 选择。同一份目录在不同模式下被加载的方式不一样，这里分别举例。

**模式 1 · 共享存储（`RemoteFilesystemSpec`）—— 模板 + 远端覆盖**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("store")
    .model(model)
    .workspace(workspace)
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
        .isolationScope(IsolationScope.USER))      // 按 userId 分命名空间
    .build();
```

- **如何加载**：每次推理时，`AGENTS.md` / `MEMORY.md` / `tools.json` 会被框架以"远端为上层、本机模板为下层"的 overlay 读出来。本地磁盘上的 `<workspace>/AGENTS.md` 是**只读模板**——是初始或多副本同步的种子；远端 KV 里如果存在该用户的同名 key，就以远端为准。
- **路由规则**：`memory/` / `skills/` / `subagents/` / `knowledge/` / `agents/<id>/sessions/` / `agents/<id>/tasks/` 都自动按 `IsolationScope` 进命名空间（默认 USER，每个 `userId` 一个命名空间；详见 [filesystem — IsolationScope](./filesystem.md#isolationscope--多用户与多副本怎么分桶)）。
- **最佳实践**：把团队约定的 `AGENTS.md` / `knowledge/` / 共享 `skills/` 用 git 同步到所有副本的本地磁盘作为模板；运行时产物（`MEMORY.md`、`memory/`、`agents/<id>/...`）让 KV 自己长。

**模式 2 · 沙箱（`DockerFilesystemSpec` / K8s / E2B / AgentRun）—— Projection + Hydrate**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION))
    .build();
```

- **如何加载**：沙箱启动时，框架把工作区里的"静态资产"（`AGENTS.md` / `skills/` / `subagents/` / `knowledge/` 等 projection roots）打包成 tar，hydrate 进沙箱的 `/workspace`。`AGENTS.md` 等还是按两层读走（先沙箱内、后宿主模板）。
- **去重 & 增量**：projection 按内容 hash 比对，没变就跳过 hydrate；变了的文件按 SHA-256 增量重写。
- **运行时产物**：`MEMORY.md`、`memory/`、`agents/<id>/...` 都落在沙箱内，沙箱快照会一起保留——下一次 `call()` 用同 `sessionId` 时连同 `node_modules`、`pip install` 一并恢复。
- **最佳实践**：把代码执行 / shell 命令都隔离在沙箱里，宿主只保留工作区"种子"（团队 git 同步过来的人格 + 共享技能 + 知识库）。这也是生产部署不可信代码的首选模式。

**模式 3 · 本机 + shell（默认 `LocalFilesystemSpec` 或不配 filesystem）—— 直读直写**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("local")
    .model(model)
    .workspace(workspace)
    // .filesystem(...) 不配 = 本机 + shell
    .build();
```

- **如何加载**：所有文件直接从 `<workspace>/` 下读，没有 overlay。`<userId>/skills/` 这种用户覆盖按目录前缀切。
- **路径安全**：默认 `ROOTED` 模式，绝对路径只允许 `workspace` 和 `project`（shell cwd）这两个根之下；`..` 路径会被路径策略拦掉。
- **最佳实践**：单进程 / 本机开发 / 单元测试 / 信任环境。生产不要直接用这个模式跑不可信代码——`execute` 是宿主 `sh -c`。

## 运行时数据与 Memory 怎么存

框架会自动写两个数据面，而它们落在**两个不同的地方**。务必分清：

| 数据面 | 是什么 | 落在哪 |
|--------|--------|--------|
| **`AgentState`** | 易失的运行期上下文：对话缓冲、压缩摘要、权限 / 工具 / 任务 / Plan-Mode 上下文，以及指向工作区产物的元数据 | **`AgentStateStore`** —— 独立子系统，**不在**工作区（默认 `~/.agentscope/state/<agentId>/`） |
| **工作区运行时 / 长期文件** | 持久产物：会话日志、任务记录、`MEMORY.md` + `memory/` | 在工作区树内，物理位置随 filesystem 模式而定 |

两者都不需要你手写编辑。本节依次走一遍这两个数据面。

### Agent 状态 —— 独立存储，不在工作区

`AgentState` 是按 `(userId, sessionId)` 维度的运行期上下文，被刻意放在**工作区树之外**。每次 `call()` 结束，它会被序列化成 JSON，按该次调用的 `(userId, sessionId)` 通过 [`AgentStateStore`](../../integration/session/index.md) 持久化；下次同 `(userId, sessionId)` 的 `call()` 自动加载回来。

默认情况下 `HarnessAgent` 使用 `JsonFileAgentStateStore`，根目录在工作区**之外**的 `~/.agentscope/state/<agentId>/`（可通过 `agentscope.state.home` 系统属性改根目录），让运行时状态与工作区数据解耦。可通过 `.stateStore(...)` 换成别的后端。

### 会话日志（这些*才是*工作区文件）

与 `AgentState` 不同，工作区里保留 `agents/<agentId>/sessions/` 下的**对话日志**：

- **`sessions.json`** —— 该 agent 的会话索引（key 是 sessionId，value 是 summary + updatedAt）。
- **`<sessionId>.log.jsonl`** —— **永不压缩**的原始对话日志，append-only。`session_search` / `session_history` 工具就是查它。

> 默认的 `JsonFileAgentStateStore` 仅适合单机。生产多副本必须换成分布式后端（`RedisAgentStateStore` / `MysqlAgentStateStore` ……）。如果你已经在用 `filesystem(SandboxFilesystemSpec)` 或 `filesystem(RemoteFilesystemSpec)` 但没换成分布式状态存储，`build()` 会直接抛 `IllegalStateException`—— 强制提醒你别让运行时状态成为单点。

完整细节（恢复链路、跨节点接续、`(userId, sessionId)` 寻址）见 [Context](../building-blocks/context.md)。

### Memory（长期记忆）

两层结构：

```
workspace/
├── MEMORY.md                  ← 策划后的长期记忆，每轮注入 system prompt
└── memory/
    └── YYYY-MM-DD.md          ← 每天追加的事实流水账（未去重）
```

写入路径：

- 对话压缩前，`MemoryFlushMiddleware` 把对话前缀里的新事实抽到 `memory/YYYY-MM-DD.md`（追加）；
- 后台节流任务定期把 `memory/` 合并去重，重写 `MEMORY.md`；
- `MEMORY.md` 每轮以受预算控制的方式注入 system prompt。

读取路径：

- 框架自动读 `MEMORY.md`（两层读，filesystem 优先）；
- agent 可主动调 `memory_search` / `memory_get` 找老内容（详见 [记忆](./memory.md)）。

### 命名空间隔离怎么落到路径上

`WorkspaceManager.resolveRuntimeDataPath()` 会问 `NamespaceFactory` 当前 `RuntimeContext` 应该走哪个命名空间。命名空间最终怎么变成"具体的存储位置"，按 filesystem 模式分：

| 模式 | "运行时数据"的物理位置 | 多用户隔离机制 |
|------|----------------------|---------------|
| 本机 + shell | `<workspace>/<userId>/agents/<agentId>/...` | 路径前缀 |
| 共享存储（KV） | KV key 前缀，例如 `namespace=alice/memory/...` | KV 命名空间 |
| 沙箱 | 沙箱状态 slot key（配合 `IsolationScope.USER`） | 沙箱实例隔离 |

不传 `userId` 时走单租户默认，所有人共享一个根。

> **静态资产** vs **运行时数据**：`AGENTS.md`、`tools.json`、`knowledge/` 这些静态资产**不**自动按 userId 切——它们对所有用户共享，只能由「用户覆盖目录」（`<userId>/skills/...`、`<userId>/subagents/...`）来差异化。会跟着 `userId` 走的是运行时数据（sessions、tasks、memory）。

## 智能体如何进化

除了静态定义，工作区还是智能体*累积经验*的落点。五条通道自动累积——打开对应能力，数据就开始在工作区里堆积，并和其它内容一样按租户隔离。每条都有自己的深入文档，这张表是索引：

| 通道 | 落在哪 | 怎么开 | 怎么累积 | 深入文档 |
|------|--------|--------|---------|---------|
| **长期记忆** | `MEMORY.md` + `memory/YYYY-MM-DD.md` | `.compaction(...)` | 压缩前 `MemoryFlushMiddleware` 从对话前缀抽取事实；后台节流任务合并去重写回 `MEMORY.md`，每轮重新注入 | [记忆](./memory.md) |
| **自学习技能** | `skills/`、`skills/_drafts/`、`skills/.archive/` | `.enableSkillManageTool(...)` | agent 调 `propose_skill` 从有效模式起草技能 → 可选审批闸门放行 → 后台 curator 把长期未用的标记为 stale（30 天）并归档（90 天） | [技能 — 自学习闭环](./skill.md#自学习闭环可选) |
| **计划文件** | `plans/PLAN.md` | `.enablePlanMode()` | 只读规划阶段用 `plan_write` 写计划；跨调用保留并驱动执行阶段，让意图与动作解耦 | [Plan Mode](./plan-mode.md) |
| **工具结果落盘** | 工作区下的 eviction 目录 | `.toolResultEviction(...)` | 单个工具结果超阈值（默认 80K 字符）时，完整输出写盘，上下文消息替换为 head/tail 预览 + `read_file` 指针 | [上下文压缩](./compaction.md) |
| **会话日志** | `agents/<agentId>/sessions/`（工作区） | 默认开启 | 每次 `call()` 追加到永不压缩的 JSONL 日志；`session_search` / `session_history` 查它 | [Context](../building-blocks/context.md) |

贯穿其中的理念：**智能体在每次运行之间变强，而你不用搭任何存储。** 记忆、技能、计划、会话日志、落盘结果都只是工作区里的文件——它们享受和本页其它内容一样的按租户隔离、两层读、以及跨 filesystem 模式的可移植性。（唯一的例外是易失的 `AgentState` 运行期上下文——它存在独立的 `AgentStateStore`，不在工作区；见 [运行时数据与 Memory 怎么存](#运行时数据与-memory-怎么存)。）

## 重点目录深入

### `skills/`

技能是"写好的能力包"——一份目录里放 `SKILL.md`（说明 + 给 agent 看的指令），可以附带参考文档、脚本。

```
skills/code-reviewer/
├── SKILL.md               ← YAML frontmatter (name + description) + 指令
├── references/style-guide.md   ← 可选，agent 按需 read_file
└── scripts/run-checks.sh       ← 可选，agent 通过 execute_shell_command 调
```

注册路径有四层（低 → 高优先级）：

1. `projectGlobalSkillsDir(Path)` —— 项目全局，例如 `~/.agentscope/skills/`
2. `skillRepository(...)` —— 市场后端（Git / Nacos / MySQL / classpath）
3. `workspace/skills/` —— 工作区共用
4. `<userId>/skills/` —— 用户隔离（覆盖以上所有）

下层独有的 skill 都保留，重名时上层覆盖。每轮推理前 `DynamicSkillMiddleware` 重新合成 → 在 system prompt 里渲染成 `<available_skills>` 块（只列 name + description），agent 看到觉得相关才会 `load_skill_through_path` 拉详情。完整工作机制见 [技能](./skill.md)。

### `subagents/`

每个 `<agent-id>.md` 是一份子 agent 声明（文件名即 `agent_id`），YAML frontmatter 描述身份、模型、工具白名单、工作区策略等，正文是子 agent 的系统提示。

```markdown
---
description: 代码评审专家。当用户需要 review PR、找代码风格问题时使用。
workspace:
  mode: isolated         # isolated（默认） | shared
model: qwen3-max         # 可选；默认继承父
tools: [read_file, grep_files]   # 可选；继承工具的白名单
---

你是一个专注代码评审的子 agent…
```

加载：`AgentSpecLoader` 在构建期**非递归**地扫 `workspace/subagents/*.md`，再合并你通过 `.subagent(SubagentDeclaration...)` 编程注册的声明。主 agent 通过 `agent_spawn agent_id="reviewer" task="..."` 调用。
完整细节（同步 / 后台、远程子 agent、流式转发、任务存储）见 [子 Agent](./subagent.md)。

### `tools.json`

工作区根目录下的 JSON，框架在 `build()` 时一次性读完：

```jsonc
{
  // 白名单：非空时只允许列出的工具
  "allow": ["read_file", "grep_files", "execute"],
  // 黑名单：列出的工具一律不暴露（优先级高于 allow）
  "deny":  ["write_file"],
  // MCP server，键是名字，值是连接配置
  "mcpServers": {
    "amap": {
      "transport": "streamableHttp",
      "url": "https://mcp.amap.com/mcp?key=${AMAP_API_KEY}"
    },
    "local-py": {
      "transport": "stdio",
      "command": "python",
      "args": ["mcp_servers/my_server.py"],
      "env": {"PYTHONUNBUFFERED": "1"}
    }
  }
}
```

行为细节：

- **MCP server 在构建期一次性注册到 toolkit**，agent 看到的就是这些 server 暴露的工具。
- **`allow` / `deny` 在所有工具注册完之后才应用**——所以也会过滤掉 Harness 的内置工具（`read_file` / `memory_search` / `agent_spawn` 等）。**用 `allow` 列白名单时务必把要保留的内置工具一并列出**，否则会一起被砍掉。
- `${ENV_VAR}` 语法做环境变量替换；未设置时给 warning 并替换成空字符串。
- 不想用文件？直接 `builder.toolsConfig(ToolsConfig.builder()...)` 编程注入；要完全关掉读取用 `disableToolsConfig()`。
- 在共享存储模式下，`tools.json` 走前面说的"远端为上层、本机模板为下层"的 overlay。

### `plans/`

Plan Mode 写下的计划文件落在这里。默认 `plans/PLAN.md`，可用 `.planFileDirectory("design-docs")` 改路径。

```
plans/
└── PLAN.md           ← plan_write 写入的当前计划
```

注意：`PlanModeContext`（是否处于 plan 阶段、当前计划文件路径）跟着 `AgentState` 走，是**运行时状态**，通过 `AgentStateStore` 持久化（默认 `~/.agentscope/state/<agentId>/`，在工作区之外）。`plans/` 下只是 markdown 内容本身。详见 [Plan Mode](./plan-mode.md)。

### `agents/<agentId>/`

这是**运行时根**，框架自己写，你一般不动它：

```
agents/<agentId>/
├── sessions/
│   ├── sessions.json          ← 该 agent 的会话索引
│   └── <sessionId>.log.jsonl  ← 永不压缩的原始对话日志（append-only）
└── tasks/
    └── <sessionId>.json       ← 子 agent 后台任务记录（taskId → TaskRecord）
```

> 序列化的 `AgentState`（`agent_state`）默认**不**在工作区里——它存在配置的 `AgentStateStore`（默认 `~/.agentscope/state/<agentId>/`）。工作区里只保留上面的对话日志与任务记录。

跨节点恢复 / 多副本部署时这些数据必须共享（要么走 `RedisAgentStateStore` + `RemoteFilesystemSpec`，要么走沙箱+分布式状态）。详见 [Context](../building-blocks/context.md) 与 [filesystem](./filesystem.md)。

### `knowledge/`

```
knowledge/
├── KNOWLEDGE.md         ← 入口/概览，全文注入 system prompt
├── api-reference.md
├── domain-terms.md
└── ...
```

加载时：

- `KNOWLEDGE.md` 全文进 `<domain_knowledge_context>`；
- 同目录下其它文件（任意深度）只列**路径清单**进 prompt，让 agent 用 `read_file` / `grep_files` / `glob_files` 按需读取。

这种"目录里全是细节，prompt 里只放索引"的模式，避免把整个知识库塞进 token 预算。

## 写入工作区的安全规则

`additionalContextFile`、`writeUtf8WorkspaceRelative`、`memory_get` 这些接口接受**工作区相对路径**。框架做基本的 path-traversal 校验（拒绝 `../../etc/passwd` 这种逃出工作区的写法）。

需要写文件时，**通过 `HarnessAgent#getWorkspaceManager()` 而不是 `java.nio.Files`**——后者在沙箱或共享存储模式下会写到错的地方（写到了宿主磁盘而不是沙箱里 / KV 里）。例外：builder 装配时的初始化脚本（例如 `initWorkspaceIfAbsent` 这种生成 `AGENTS.md` 种子），那时还没有运行时上下文，用 `java.nio.Files` 是 OK 的——它本来就是要落本地模板。

## 相关文档

- [架构](./architecture.md) — system prompt 是怎么拼出来的，能力之间如何协作
- [文件系统](./filesystem.md) — 工作区在物理上落到哪里（本机 / 沙箱 / 共享存储）、`IsolationScope`、多用户隔离
- [Context](../building-blocks/context.md) — `AgentState` 与 `AgentStateStore` 的持久化、跨节点恢复
- [记忆](./memory.md) — `MEMORY.md` / `memory/` 的生成与维护、压缩、卸载
- [技能](./skill.md) — 四层合成、自学习闭环、`<available_skills>` 块
- [子 Agent](./subagent.md) — `subagents/` 声明文件、同步/后台、流式转发
- [Plan Mode](./plan-mode.md) — `plans/` 计划文件、只读阶段、HITL 退出
