---
hide-toc: true
---

# First Harness Framework Release — Bringing OpenClaw's "Continuous Evolution" Experience Inside Enterprise-Grade Security Boundaries

Picking up from where I left off, in a previous article I took a deep dive into OpenClaw and the Harness Engineering practices behind it, and sketched out a "Harness Framework" to explain how that philosophy could be applied to enterprise agent development.

The good news is that AgentScope Java 1.1.0 has officially shipped. In this milestone release, we have fully implemented the "Harness Framework" plan. Developers can use 1.1 to quickly practice Harness, building local productivity apps such as XxxClaw and Coding Agents, as well as enterprise apps for distributed scenarios such as DataAgent and SRE Agent.

AgentScope Java 1.1.0 delivers four core capabilities in this release:

+ **Workspace-driven agent runtime**: An agent's persona, knowledge, skills, memory, and subagent specs are all persisted in a structured workspace. On every run, context is loaded automatically from the workspace; after the run, memory is written back automatically, so the agent's capabilities evolve continuously over time.
+ **Pluggable abstract filesystem**: The physical storage of a workspace can be swapped freely — local disk, remote shared storage, or isolated sandbox — all operated through the same interface. The same agent logic can be adapted to personal development environments and enterprise distributed deployments without modification.
+ **Out-of-the-box context management**: Built-in dialogue compaction, layered memory persistence, and full-text retrieval solve the stubborn problems of long-dialogue context bloat and cross-session memory loss. Background maintenance keeps the memory store from growing uncontrollably over time.
+ **Subagent orchestration and isolated execution**: Supports declarative definition of subagents and synchronous or asynchronous delegation of subtasks. Tool execution can be configured to run inside an isolated sandbox, with sandbox state recoverable across multi-turn dialogues, while supporting both session-level and user-level isolation for multi-tenant scenarios.

## OpenClaw/Hermes Are Great, but Why Don't They Work in Enterprise Agent Scenarios?

Over the past year, agent products such as OpenClaw, Hermes, and Claude Code sparked a wave of enthusiasm, and also popularized the Harness Engineering philosophy behind them: using a structured workspace, context management, and tool conventions to replace the primitive "every conversation starts from scratch" usage pattern. More and more teams are bringing this mindset into their own agent development.

However, those who actually try to land it often find that the road gets stuck at "enterprise-grade." We have sorted out the five obstacles most often mentioned by frontline developers:

1. **Multiple users, multiple replicas — what about the workspace?** OpenClaw uses a local directory as the workspace, which is perfectly fine for a single machine and single user. But when the service is exposed externally, multiple users' workspaces need to be isolated, and when the agent scales horizontally across multiple machines, the same user's workspace must be shared across replicas — the local-directory assumption breaks immediately.
2. **Tools and Skill scripts cannot run on the host; how to execute them in isolation?** An agent invoking Shell or running user-provided code is fine on a trusted local development machine. But once in service, executing arbitrary user input directly on the host is a security vulnerability. A sandbox is essential, but "having a sandbox" is only the first step: tools inside the sandbox also need to see the full context, and the same sandbox instance must be recoverable across multi-turn dialogues, rather than starting from zero every time.
3. **How can the "workspace + filesystem" combination be moved to a distributed environment?** The filesystem-driven workspace is the most intuitive and effective pattern in Harness Engineering, but it relies on a "filesystem." In distributed scenarios there is no unified local disk; remote storage, KV services, and object storage each have their own interfaces. Rewriting for each one couples agent logic to infrastructure.
4. **How to do Multi-Agent right?** Subtask distribution, context isolation, asynchronous execution, result collection, timeout cancellation — each item is not hard on its own, but assembling them into a manageable orchestration layer causes code complexity to rise quickly. Moreover, most frameworks only provide primitives; the engineering questions of "how to declare subagents, when to spawn them, and how to manage state" are left for you to figure out.
5. **Is there an out-of-the-box implementation of context compaction and layered memory?** Harness Engineering makes these two things very clear, but the details are numerous in practice: compaction timing, compaction strategy, fact extraction before compaction, historical retrievability, recovery after cross-process restart... Most frameworks only provide short/long memory abstract interfaces, leaving the concrete implementation to you.

The root cause of these five problems is the same thing: **personal-assistant agents and enterprise agents are two different engineering shapes**, and applying the same assumptions to both scenarios will inevitably hit a wall.

From the **deployment shape** perspective: a personal assistant is single-user and single-process; all state can live on one machine. An enterprise agent must scale horizontally, serve multiple tenants, and keep service uninterrupted, so state must be storable and recoverable in a distributed way. From the **security boundary** perspective: local tool execution carries no risk, but arbitrary Shell execution in production is a serious attack surface; sandbox and permission boundaries are not "optional optimizations" but "prerequisites for going live." From the **operational observability** perspective: when a personal tool breaks you can just read the logs; an enterprise service requires memory persistence, session auditability, and traceable state changes. From the **token economics** perspective: individual users are not sensitive to latency and cost, but in enterprise scenarios every invalid context re-push is real cost.

So, is there a framework that lets you "write one set of logic and switch shape on demand"? The Harness module of AgentScope Java 1.1.0 (entry class `HarnessAgent`) is designed around this goal: it does not replace the `ReActAgent` reasoning loop, but inserts hooks at key moments in the loop, adds a set of tool and workspace conventions, and packages the engineering answers to the five problems above, letting you focus on the agent's business logic rather than infrastructure.

## AgentScope Harness Design Philosophy: Why Can It Solve the Above Problems?

The design philosophy of AgentScope Harness can be summarized in one sentence: **package the engineering answers to "what about the next turn, what about the next day, what if context explodes, what if state is lost," rather than making every agent project reinvent them.**

At the implementation level, two core pillars support the entire framework.

### Core Pillar 1: Workspace as the Single Source of Truth

Harness introduces the concept of a **workspace** for every agent — a structured directory that carries all persistent content required for the agent to run: persona definition (`AGENTS.md`), long-term memory (`MEMORY.md`), domain knowledge (`knowledge/`), reusable skills (`skills/`), subagent specs (`subagents/`), and session history (`agents/<agentId>/`).

This is not a new idea — OpenClaw and Hermes both discovered in practice that giving an agent a stable "workbench" is far more effective than reinitializing it every time. Harness systematizes this intuition: the workspace is the agent's single source of truth, and all state reads and writes revolve around the workspace rather than being scattered across code, databases, and memory.

In actual operation, before each reasoning turn, `WorkspaceContextHook` automatically injects key files such as `AGENTS.md`, `MEMORY.md`, and `knowledge/` into the system prompt, ensuring the agent's persona and knowledge are fully presented in every turn. After the agent run ends, `MemoryFlushHook` extracts new facts from the conversation and writes them to the memory file; then the background `MemoryConsolidator` periodically merges the running log into refined long-term memory. The workspace evolves continuously through conversations, and every run "knows more" about the user and task than the last.

<!-- 这是一张图片，ocr 内容为： -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1777338565508-2d485103-d3b6-4c8f-830b-7ee6e783cda3.png)

### Core Pillar 2: AbstractFilesystem Lets the Workspace Run in Any Environment

The workspace idea is appealing, but it has a practical constraint: local disk directories do not work in distributed scenarios. Multiple pods each have their own local disk — where is `MEMORY.md` written? Which replica's version is the "true" one?

AgentScope Harness solves this problem with the **AbstractFilesystem** abstraction layer. For the upper layer, the agent only needs to call unified interfaces such as `read/write/ls/grep`, without caring where the "files" actually land. For the lower layer, it can be adapted to local disk, remote object storage (OSS), KV databases (Redis), sandbox filesystems, or any other medium, and can even route different paths to different backends through `CompositeFilesystem`.

<!-- 这是一张图片，ocr 内容为：ABSTRACTFILESYTEM 继承 继承 继承 继承 继承 SANDBOXFILESYSTEM REMOTEFILESYSTEM LOCALFILESYSTEM LOCALFILESYSTEMWITHSHELL COMPOSITEFILESYSTEM -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1778218615934-eec5c4c7-4a9c-44c2-84cb-56688f64d7f0.png)

As shown in the figure, based on the AbstractFilesystem interface, AgentScope provides three built-in extension implementations, corresponding to three usage modes.

> To be expanded in detail: the three implementations and modes.
>

In AgentScope 1.1, workspace is the core abstraction of the agent, and AbstractFilesystem is the physical implementation carrier of the workspace. All file operations, command execution, and memory management tools use AbstractFilesystem as the standard operation entry.

<!-- 这是一张图片，ocr 内容为：FILESYSTEMTOOL SHELLEXECUTETOOL MEMORY 命令 记忆 读写 搜索 执行 管理 WORKSPACE BASED ON ABSTRACTFILESYSTEM -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1778218989236-658ff65d-94ae-42e6-a004-4fe7b223a52a.png)



Based on this filesystem abstraction, the AgentScope framework directly brings three major engineering capabilities to agent development:

**Security and Isolation**

+ Shell/Code/Skill execution is isolated through a sandbox backend; user-input-driven commands no longer run directly on the host.
+ The workspace itself can also run inside a sandbox, achieving isolation at the file-read/write level.
+ Tool registration and exposure are managed uniformly by the framework; the `execute` tool only appears when the backend implements the sandbox interface.

**Distributed Deployment**

+ Agents can be deployed as equal replicas; key files such as `MEMORY.md` and session logs are routed to shared storage through the Remote backend, naturally achieving cross-node synchronization.
+ Through the combination of `IsolationScope` (SESSION / USER / AGENT / GLOBAL) and `RuntimeContext`, multiple tenant strategies such as session-level isolation and user-level sharing can be achieved without code changes.

**Subagent and Asynchronous Tasks**

+ Subagent workspace, filesystem, and session state are inherited from or independently configured from the parent agent; orchestration strategies are declared by specs, without manual assembly.
+ The asynchronous task state machine (PENDING/RUNNING/COMPLETED/FAILED/CANCELLED) and result-collection mechanism are ready to use, and support replacement with a cross-process implementation.

## AgentScope Harness Typical Use Cases: Map Quickly to Your Scenario

The three scenarios below cover typical development shapes from personal to enterprise. They are not mutually exclusive options, but represent three different complexity paths — you can start with the simplest and migrate gradually as requirements evolve.

### Personal Agent — Typical OpenClaw-Style Apps

**Characteristics of this scenario**: single user, local machine execution, needs to operate local files or run scripts. Typical products are personal assistants, note-taking bots, and local Coding Agents.

The core need in this scenario is "make the agent truly know and remember me," rather than being a stateless Q&A machine. The value of Harness here is: the `AGENTS.md` in the workspace defines the agent's persona and behavior preferences; after the conversation, new facts are automatically extracted and written to memory; the next time it opens, the agent still recognizes you and remembers the previous progress. Skills and domain knowledge also live in the workspace and can be edited and adjusted at any time without touching code.

Under local deployment, Shell execution can also be enabled, letting the agent run scripts and operate the filesystem directly — this is the most attractive part of OpenClaw-style products. Harness adds the "continuous evolution" layer on top: the workspace is like the agent's brain, becoming more experienced with every conversation.

**Core capabilities provided by AgentScope Harness in this scenario:**

+ **Continuous memory**: After the conversation, new facts are automatically extracted and written to the workspace. The next time it starts, there is no need to re-"inform" the agent of the background; long-term memory accumulates with use.
+ **Local Shell execution**: In a trusted local environment, the agent can directly run scripts and operate files, reproducing the core experience of OpenClaw-style products.
+ **Workspace as configuration**: Modify `AGENTS.md` to adjust persona, add skills in the `skills/` directory — changing one file is equivalent to upgrading the agent once, without recompiling or redeploying.
+ **Cross-process session recovery**: Close and reopen; as long as the sessionId is unchanged, the previous conversation state is fully restored, not started from zero.

### Enterprise Data Service — Typical DataAgent

**Characteristics of this scenario**: serving multiple users, needs to execute SQL / Python / Shell, tasks take a long time, input comes from untrusted external users, and requires multi-turn conversation state recovery and consistent user experience across replicas.

The biggest risk in this scenario is **execution safety** — user-driven code cannot run unrestricted on the server. Harness's sandbox mechanism confines the agent's file operations and command execution to an isolated environment; the server process itself is unaffected. More importantly, the sandbox is not "destroyed after use": after each turn, the sandbox state is persisted and brought back for the next turn, so users do not lose work progress because of service restart or node switching.

During multi-replica deployment, a user's long-term memory (the agent's accumulated knowledge about this user) can be stored in shared storage, so no matter which node the request lands on, the agent sees the same memory. Long analysis tasks can be split into multiple subagents running in parallel, with the main agent only responsible for coordination and aggregation, without blocking and waiting the whole time.

**Core capabilities provided by AgentScope Harness in this scenario:**

+ **Isolated sandbox execution**: All code and commands run in an isolated environment; the host service process is not affected by user input, and the security boundary is clear.
+ **Multi-turn sandbox state recovery**: After each turn, the sandbox state is automatically saved and restored in place for the next turn or service restart, so the user's work scene is not lost.
+ **Distributed memory sharing**: User long-term memory is stored in shared storage, so under multi-node deployment all replicas read the same "knowledge about this user," delivering a consistent experience.
+ **Subagent parallel orchestration**: Long tasks can be broken down into multiple subagents executing concurrently; the main agent only coordinates, improving overall efficiency and making timeout and failure management easier.
+ **Multi-tenant isolation**: Workspaces and execution environments are isolated by session or user dimension, so multiple users online at the same time do not interfere with each other.

### Enterprise Online Service — Typical Taobao-Tmall Transaction Agent

**Characteristics of this scenario**: tasks are mainly completed by calling business APIs (placing orders, querying, approving, etc.), no Shell execution on the server is needed, but multi-instance operation, persistent session state, and cross-user knowledge sharing are required.

The core need in this scenario is **stability and safety** — an online service cannot fail because the agent invokes a Shell command it should not. The value of Harness here is: when sandbox execution capability is not configured, the framework will not expose Shell tools by default. The agent can only interact externally through clearly defined business tools, and the security boundary is determined by configuration rather than developer self-discipline.

Session state and memory can be persisted to remote storage, and multiple service instances share the same set of user memories. If a user switches to another entry and starts a new conversation, the agent can still continue the previous context. When parallel processing of multiple subtasks is needed (such as checking inventory, calculating discounts, and generating summaries simultaneously), the subagent mechanism is also applicable and can be integrated with external task queues for cross-process task management.

**Core capabilities provided by AgentScope Harness in this scenario:**

+ **Default security boundary**: When sandbox execution is not enabled, the framework does not expose Shell tools. The agent can only interact externally through business tools you explicitly register, and security policy is determined by configuration.
+ **Multi-instance shared memory**: Session state and user memory are persisted to remote storage, so any service instance can read the same context, allowing users to switch among instances transparently.
+ **Cross-request session continuity**: Each request carries the same user identifier, and the agent automatically restores the previous conversation state, enabling true multi-turn continuous dialogue.
+ **Parallel subtask support**: When multiple business steps need to be processed at the same time, subtasks can be delegated to subagents for parallel execution, with results aggregated before a unified reply, without affecting the main flow response speed.

## AgentScope Harness in Detail — Take a Moment to Learn More About the Framework

This section explains the core capabilities of AgentScope Harness from the **user perspective**: what it is, how it works, and how to think about configuration.

### Quick Start

Getting started with Harness takes only three steps: add the dependency, prepare the workspace, and build and call the agent.

**1. Add the dependency**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>

```

**2. Prepare the workspace**

Choose a directory on disk as the `workspace`, and create `AGENTS.md` in it. This is not an "optional initialization step," but Harness's core entry point — the agent's persona, memory, skills, and subagent specs all revolve around this directory. A few lines of convention in `AGENTS.md` are enough at first; it evolves with use.

**3. Build `HarnessAgent` and call it**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .compaction(CompactionConfig.builder()     // recommended to configure from the start to avoid context overflow in production
        .triggerMessages(50)
        .keepMessages(20)
        .build())
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("user-session-001")   // multiple calls with the same sessionId automatically continue context
    .userId("alice")                 // required in multi-user scenarios for namespace isolation
    .build();

Msg reply = agent.call(userMessage, ctx).block();
```

After running, check the workspace directory: the three paths `AGENTS.md`, `memory/`, and `agents/<agentId>/` should all exist, indicating that the agent is already writing memory and persisting session state normally.

For a complete runnable example, see `QuickstartExample` in `agentscope-examples/agents/harness-examples/harness-quickstart`.

---

### Core Concepts

Understanding the following six concepts basically means you have mastered Harness's operating logic.

| Concept | Definition | Problem Solved | Usage Suggestion |
| --- | --- | --- | --- |
| `HarnessAgent` | An engineering wrapper entry point based on `ReActAgent`. At `build()` time it assembles hooks, built-in tools, skills, and session persistence. | "I don't want to assemble compaction, memory, sessions, subtasks, and filesystem from scratch." | Business code only deals with `HarnessAgent.builder()` and `agent.call(msg, ctx)`. |
| `workspace` | The agent's working directory, carrying all persistent content such as `AGENTS.md`, `MEMORY.md`, `skills/`, `subagents/`, and session history. | "Where do persona, knowledge, memory, and state go, and how do they evolve continuously?" | Plan the workspace structure before writing prompts; treat the workspace as versionable assets. |
| `filesystem` | A unified interface for file reads and writes, the abstraction layer between the agent tool layer and physical storage. Supports local disk, remote storage, sandbox, and other backends. | "How can the same agent logic switch among local, shared storage, and sandbox?" | Prefer selecting from the three declarative modes (Local / Remote / Sandbox). |
| `RuntimeContext` | The identity context of a single `call()`, containing `sessionId`, `userId`, etc. Passed freshly on each call, not persisted. | "Who is this turn, where is state read/written, and how are multiple tenants isolated?" | Always pass a stable `sessionId`; in multi-tenant scenarios, `userId` is required. |
| `sandbox` | An isolated execution environment where file operations and commands run on the sandbox side. After each turn, state is persisted and restored on the next turn. | "How to safely execute tools and scripts under untrusted input while maintaining multi-turn state continuity?" | Enable it first when there is code execution demand; choose isolation granularity according to business. |
| `memory` | A two-layer memory system: after each conversation, new facts are automatically extracted and appended to a running log; a background process periodically merges them into injectable long-term memory, with full-text retrieval. | "Long conversations don't lose facts, context doesn't explode, and history is retrievable." | Enable dialogue compaction and watch the memory file change; use the search tool to recover old facts. |


**Summary**: `HarnessAgent` handles orchestration, `workspace` handles persistence, `filesystem` handles landing points, `RuntimeContext` handles identity, `sandbox` handles boundaries, and `memory` handles long-term evolution.

---

### Features

#### Workspace: The Single Source of Truth for the Agent

The workspace is the most important design that distinguishes Harness from ordinary agent frameworks. It is not a temporary storage directory, but the agent's "externalized brain" — everything that needs to be retained across sessions lives here.

The standard directory structure of the workspace is as follows:

```plain
workspace/
├── AGENTS.md              ← Agent persona and behavior conventions, automatically injected into system prompt before each reasoning turn
├── MEMORY.md              ← Refined long-term memory, maintained automatically by the background process and accumulated with use
├── knowledge/             ← Domain knowledge, injected together with AGENTS.md
├── skills/                ← Reusable skills, automatically assembled into the agent's tool set
├── subagents/             ← Subagent spec declarations, automatically discovered and loaded
└── agents/<agentId>/
    ├── context/           ← Session state snapshot (used for recovery after process restart)
    ├── sessions/          ← Dialogue JSONL and compacted context, for audit and retrieval
    └── memory/            ← Daily memory running log
```

**How the workspace works in each reasoning turn**: before reasoning begins, Harness assembles key files such as `AGENTS.md`, `MEMORY.md`, and `knowledge/` into the system prompt; after reasoning ends, it extracts new facts from the conversation and appends them to the daily memory running log. The workspace evolves continuously with each conversation, and the agent becomes "more familiar" with the business and user over time.

**Why the workspace is better than hard-coding prompts in code**: persona, knowledge, skills, and subagent specs are all in workspace files. Adjusting behavior only requires changing files, not recompiling and redeploying. For agents with complex business knowledge, this is especially critical — business rules change frequently, and updates should be lightweight.

---

#### Session Persistence: Continuous State Across Requests and Processes

Harness splits session state persistence into **two parallel paths**, each solving different problems:

+ **State snapshot (`context/`)**: After each `call()` ends, the agent's running state (current dialogue memory, tool execution context, etc.) is serialized into JSON files and stored under `agents/<agentId>/context/<sessionId>/` in the workspace. The next time a call is made with the same `sessionId`, the framework automatically loads this snapshot before reasoning and restores it to where it ended last time. This is the technical guarantee that "close and reopen, still remember last time."
+ **Dialogue log (`sessions/`)**: The complete dialogue history is appended in JSONL format to `<sessionId>.log.jsonl`; this file is never compacted and is used for audit and the `session_search` tool. Another file, `<sessionId>.jsonl`, stores the compacted LLM context, which is the version the model actually "sees."

Both paths are maintained automatically by the framework. The only thing the developer needs to do is **pass the same `sessionId` stably on every call**.

---

#### Memory Management: Automatic Precipitation from Dialogue to Long-Term Knowledge

This is one of Harness's most valuable engineering capabilities. Many agent frameworks' "memory" is essentially stacking historical messages into context until it eventually bursts; AgentScope's current approach is **two-layer separation**:

**Layer 1 — Daily running log**: After each conversation, the framework uses an LLM to extract "new facts" from the conversation and appends them in bullet-point form to the daily memory file (`memory/YYYY-MM-DD.md`). This layer only appends and does not modify, ensuring no new facts are lost.

**Layer 2 — Long-term memory**: A background scheduler periodically reads recent daily running-log files, uses an LLM to merge, deduplicate, and refine them with the existing `MEMORY.md`, and writes back an "injectable version" within the token budget to `MEMORY.md`. This layer is the "fact summary" injected into each reasoning turn, with high quality and controlled size.

The relationship between the two layers: Layer 1 guarantees **no loss**, Layer 2 guarantees **usability**. New facts first land in the running log; when enough accumulate, the background process moves them into long-term memory. During reasoning, the model looks at long-term memory first; if it cannot find something, it uses the `memory_search` tool for full-text retrieval (based on SQLite FTS5).

**Context compaction** is the other side of memory management: when the number of messages or tokens exceeds the threshold, Harness uses an LLM to compress the previous dialogue into a summary, keeps the most recent messages, and offloads the rest to a JSONL file. Compaction occurs after long-term memory extraction, ensuring valuable information is precipitated before being compressed. If the model returns a context overflow error, the framework catches the exception, forces compaction, and retries automatically; the whole process is transparent to the caller.

Configuration suggestion:

```java
.compaction(CompactionConfig.builder()
    .triggerMessages(50)    // trigger compaction when messages exceed 50
    .keepMessages(20)       // keep the most recent 20 messages
    .flushBeforeCompact(true) // extract memory before compaction (enabled by default)
    .build())
```

---

#### Subagent Orchestration: Decomposition and Delegation of Complex Tasks

When the main agent encounters a subtask that is time-consuming, context-heavy, or parallelizable, it can delegate it to a subagent for execution. A subagent is an independent agent instance with its own system prompt and memory; it does not share the main agent's dialogue history, and its execution result is returned to the main agent as a tool result.

**There are four ways to declare subagents**, from low to high flexibility:

1. **Built-in `general-purpose` agent**: mirrors the main agent's configuration, suitable for temporarily delegating arbitrary subtasks;
2. **Workspace file-driven**: place Markdown files under `workspace/subagents/` (YAML front matter defines name, description, and tools; body is the system prompt), and the framework automatically discovers and loads them;
3. **Code declaration**: programmatically specify with `builder.subagent(spec)`;
4. **Custom factory**: fully control the subagent's build logic.

The workspace-driven approach is the most recommended — subagent definitions are versioned with the workspace and can be adjusted without touching code.

**Invocation methods** are divided into synchronous and asynchronous:

+ **Synchronous call**: the main agent blocks and waits for the subagent to complete before continuing, suitable for scenarios where the next step cannot proceed without the result;
+ **Asynchronous call**: the main agent submits the task and immediately gets a task ID, can continue doing other things, and later polls the result with the `task_output` tool. For tasks taking more than a few seconds, asynchronous is strongly recommended to avoid the main agent blocking and wasting time and tokens.

**Anti-infinite-recursion**: subagents default to "leaf" form and cannot spawn subagents themselves; the framework also has a maximum depth limit as a safeguard.

---

#### Built-in Tools

When `HarnessAgent` is built, it automatically registers a set of tools covering "closed-loop needs," without manual configuration:

| Tool Category | Tool List | Description |
| --- | --- | --- |
| File operations | `read_file`, `write_file`, `edit_file`, `grep_files`, `glob_files`, `list_files` | Operate workspace files; paths are within the filesystem backend scope |
| Memory retrieval | `memory_search`, `memory_get` | `memory_search` uses SQLite full-text retrieval; `memory_get` reads memory files by line number |
| Session query | `session_search`, `session_list`, `session_history` | Retrieve historical dialogue content for the agent to actively review |
| Subtask management | `agent_spawn`, `agent_send`, `agent_list`, `task_output`, `task_list`, `task_cancel` | Delegate, query, and manage subagent tasks |
| Shell execution | `execute` | **Conditionally registered**: only appears when the filesystem backend supports isolated execution (local Shell mode or sandbox mode) |


Notably: in "remote shared storage" mode, the framework **does not register Shell tools by default** — this is an intentional security design, not an omission. If your business agent does not need to execute commands, this mode eliminates a whole category of execution security risks.

---

#### Filesystem: Three Modes, Choose as Needed

The filesystem is the key layer that connects "agent logic" with "infrastructure" in Harness. The framework provides three declarative modes; choose based on business constraints:

**Mode 1: Local + Shell (default)**

Without configuring `filesystem`, or explicitly writing `filesystem(new LocalFilesystemSpec())`, the workspace is a directory on the local machine and Shell commands can be executed. Suitable for personal local apps and development/test environments; simplest, with no extra dependencies.

**Mode 2: Remote shared storage**

Configure `filesystem(new RemoteFilesystemSpec(store))`; critical data such as memory and session logs are routed to remote KV (e.g., Redis), while the local filesystem only stores content that does not need to be shared. **Shell tools are not registered by default**, suitable for multi-replica online services and scenarios that require cross-node sharing of user memory but do not need code execution.

**Mode 3: Sandbox execution**

Configure `filesystem(sandboxSpec)`; file reads/writes and command execution are all completed inside an isolated sandbox environment, and the host process is unaffected. Suitable for scenarios requiring execution of untrusted code, such as DataAgent and Coding Agent.

The core difference among the three modes is: **who executes commands, where data lands, and what the isolation granularity is**. The same agent code logic can migrate among the three modes by switching the `filesystem` configuration.

---

#### Sandbox: Isolated Execution + Recoverable State

The sandbox mode solves not only "isolated execution," but also "continuity of the isolated environment across multi-turn dialogues" — together these are what really matter.

**Execution boundary**: in sandbox mode, Shell commands and file reads/writes invoked by the agent happen on the sandbox side; the host process only coordinates. Arbitrary commands from user input do not directly affect the server.

**State recovery**: after each `call()` ends, the sandbox's current filesystem state is persisted (snapshot mechanism). At the beginning of the next call, the framework finds the corresponding snapshot by `sessionId` or `userId` and restores the sandbox to where it ended last time. Users do not lose work progress because of service restart or request drift to another node.

**Workspace projection**: host workspace content such as `AGENTS.md`, `skills/`, `subagents/`, and `knowledge/` is synchronized into the sandbox at the beginning of each `call()`, ensuring the agent inside the sandbox sees complete configuration and skill definitions.

**Isolation granularity** (choose as needed):

+ **Session-level**: each session has an independent sandbox state, with no interference between sessions, suitable for multi-user SaaS;
+ **User-level**: multiple sessions of the same user share the same sandbox state, suitable for "user long-term workbench" scenarios;
+ **Global shared**: the entire agent shares one sandbox, suitable for tool-type or read-only agents.



Applying a sandbox in a real production environment involves more considerations. Refer to the official documentation for more details:

+ How to manage the sandbox lifecycle: agent built-in management or user-managed
+ Which processes need to run in the sandbox: Tool In Sandbox, Subagent in Sandbox
+ How to manage internal sandbox state: state, snapshot recovery

---

#### Skills: Workspace-Driven Reusable Skills

Skills are the way to structure "reusable operation workflows." In the workspace's `skills/<skill-name>/` directory, place a `SKILL.md`; the framework automatically discovers and assembles it into the agent's capability library at startup. The agent can invoke these skills during reasoning; a skill itself describes the steps and conventions for "how to do this thing."

The engineering value of this design is: skills are files, can be version-controlled in Git together with code, can be code-reviewed, and can be updated without redeployment. When a team has a large number of SOPs and operation norms to inject into the agent, this is much clearer than piling everything into the system prompt.

In sandbox mode, skill files are synchronized into the sandbox along with workspace projection, and commands involved in skills are executed in the isolated environment without affecting the host.

## Summary

AgentScope Java 1.1 converges the set of capabilities everyone wants from Harness Engineering but is hardest to assemble on your own into **`HarnessAgent` + workspace conventions + pluggable filesystem + hook pipeline**: in personal scenarios, it is a memory-enabled, compaction-enabled, subtask-enabled enhanced ReAct Agent; in enterprise scenarios, it is infrastructure that turns **isolation, multi-tenancy, distributed memory, and subagent orchestration** into configuration items.

If you are evaluating the evolution from a personal-assistant prototype to a production-ready enterprise agent, we recommend starting with the quick start in the [Harness Overview](../overview.md), then choosing a declarative mode from [Filesystem](../filesystem.md), and then enabling compaction, sandbox, and subagents as needed — every step has corresponding documentation and example modules to follow, without having to invent a "workspace-as-truth" runtime from scratch.



![Canvas](https://intranetproxy.alipay.com/skylark/lark/0/2026/jpeg/54037/1778221664765-d534ffa1-1649-4444-ad8c-046c936e40e7.jpeg)
