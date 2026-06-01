# Subagent 能力对比：opencode vs agentscope-java

> 分析时间：2026-05-31
> 范围：`opencode/packages/opencode/src/{tool/task.ts, agent/agent.ts, agent/subagent-permissions.ts, background/job.ts, permission, session/prompt.ts}` 与 `agentscope-java/agentscope-core/src/main/java/io/agentscope/harness/agent/{tool/{TaskTool,AgentSpawnTool}.java, subagent/*, middleware/{Subagents,DynamicSubagents}Middleware.java}`。
> 目的：识别 opencode 在 subagent 抽象上**有而 Java 缺**的能力，挑出可以借鉴落地到 agentscope 的项。

## 修订记录
- 2026-05-31 初版。
- 2026-05-31（修订）：第 3 节 ★★★ 1 条原标题「把 `task_id` 升级为可复用的子 Session ID」误判 Java 的 task_id 未持久化。审 `WorkspaceTaskRepository` / `TaskRecord` / `SubagentsMiddleware.buildTaskSummary` 后修正为「让子 agent 的活体实例 + 对话历史也能跨重启恢复」 — task_id **状态**已持久，但「子 agent 活体 + 子会话历史」仍是内存态，这才是真正可借鉴的缺口。

---

## 1. opencode subagent 核心设计

### 1.1 Agent 是一等抽象，subagent 只是它的一种 `mode`
- `Agent.Info` 字段：`name`, `description`, `mode: subagent | primary | all`, `permission: Ruleset`, `prompt`, `model`, `temperature`, `topP`, `steps`, `variant`, `options`, `color`, `hidden`。
- 内建 agents（同一注册表内）：`build` / `plan`（primary），`general` / `explore` / `scout`（subagent），`compaction` / `title` / `summary`（`hidden: true` 的 primary）。
- 含义：**“subagent / 主 agent / 内部工具 agent” 用同一份 schema 与注册表表达**，编排、配置、覆盖都走同一条路径。

### 1.2 `task` 工具只是个调度壳，子会话是真正的载体
- 参数：`description`, `prompt`, `subagent_type`, `task_id?`, `background?`, `command?`。
- 行为：调用 `sessions.create({ parentID, title, permission: derive… })` 在 DB 中开一个**子 Session**。返回给 LLM 的 `task_id` 就是子 sessionID。
- 复用：再次调用 `task` 时带相同 `task_id` → **复用同一子 Session 的全部历史与工具输出**继续对话，不是“开新”。

### 1.3 权限继承走显式 ruleset 推导
- `deriveSubagentSessionPermission` 把以下 4 类规则拼成子 session 的 ruleset：
  1. **父 agent** 的 `edit` 类 deny（让 Plan Mode 这种 agent 级文件锁能下沉到 subagent，不会被绕过 — 见 #26514）；
  2. **父 session** 的 deny + `external_directory` 规则；
  3. 若 subagent 自己未显式 allow `todowrite` / `task`，则强制 deny；
  4. 用户配置层 (`primary_tools`) 的 allow。
- ruleset 是 `{permission, pattern, action: allow|ask|deny}` 三元组，跨工具复用，pattern 走通配匹配。

### 1.4 `ask` 询问 + “always” 缓存
- 调度 subagent 前 `ctx.ask({ permission: "task", patterns: [subagent_type], always: ["*"] })`。
- 用户回 `always` 时把 `{permission, pattern, allow}` 写入 project 级 `approved` 表，之后同类 spawn 自动放行。

### 1.5 前/后台统一接口
- 同一个 `task` 工具，`background: true` 时走 `BackgroundJob.start({ run })` 异步执行，立刻返回一段固定模板（"Background task started, will be notified"）。
- 完成时 `inject("completed", text)` **把一段合成 user 消息插回父 session**（XML 包装的 `<task id=… state=completed>`），父 agent 在下一轮自动看到结果，**无需轮询**。
- `BackgroundJob.Service` 自身是**通用**任务运行时（接收 `Effect.Effect<string>`），通过 fiber 中断实现 cancel，通过 `Deferred` 实现 wait — 不绑 subagent。

### 1.6 Spawn 时的一次性工具屏蔽
```ts
tools: {
  ...(next.permission.some(r => r.permission === "todowrite") ? {} : { todowrite: false }),
  ...(next.permission.some(r => r.permission === "task")      ? {} : { task: false }),
  ...Object.fromEntries((cfg.experimental?.primary_tools ?? []).map(t => [t, false])),
}
```
即使 subagent 的静态 permission 允许某工具，调度方也可在调用瞬间临时屏蔽。

### 1.7 Subtask 作为消息一等成员
- `MessageV2.SubtaskPart` 是消息 parts 中的具名类型 `{ type:"subtask", prompt, description, agent, model?, command? }`。
- `handleSubtask` 流程把 SubtaskPart 转成一个隐式的 assistant 消息 + `task` 工具调用 — 用户可在 prompt 里直接挂 `@agent` 调度，UI 也能展示“谁把什么派给谁”。

### 1.8 LLM 反推 agent 规格
- `Agent.generate({ description })` 调用模型按 `PROMPT_GENERATE` 输出 `{ identifier, whenToUse, systemPrompt }`，配合 CLI `agent` 命令落盘为 Markdown 文件 — **自动批量化生产 subagent**。

### 1.9 Markdown + frontmatter 装载
- `{agent,agents}/**/*.md` 与 Java 的 `subagents/*.md` 思路一致；opencode 多了 `mode`/`hidden`/`color`/`temperature`/`top_p`/`variant`/`steps` 等更丰富的元数据字段，且对“已废弃 `tools` map”做了向后兼容标准化。

---

## 2. agentscope-java 现状

| 维度 | 实现 | 文件 |
|---|---|---|
| 调度入口 | `agent_spawn` / `agent_send` / `agent_list` | `tool/AgentSpawnTool.java` |
| 任务管理 | `task_output` / `task_cancel` / `task_list` | `tool/TaskTool.java` |
| 注册管理 | `DefaultAgentManager`（volatile factory map + remote 声明） | `subagent/DefaultAgentManager.java` |
| 声明模型 | `SubagentDeclaration` + `WorkspaceMode (ISOLATED/SHARED)` | `subagent/{SubagentDeclaration,WorkspaceMode}.java` |
| 注入 system 段 | `SubagentsMiddleware` / `DynamicSubagentsMiddleware`（每轮 reasoning 重写 SYSTEM） | `middleware/(Dynamic)?SubagentsMiddleware.java` |
| 流式事件 | `SubagentEventBus` 通过 Reactor Context 向父级聚合 | `core/agent/SubagentEventBus.java` |
| 远程 subagent | `SubagentDeclaration.url + headers` → `TaskRunSpec.RemoteTaskRunSpec` | `subagent/task/TaskRunSpec.java` |
| 加载 | `AgentSpecLoader` 解析 `subagents/*.md` YAML frontmatter | `subagent/AgentSpecLoader.java` |
| 安全 | `MAX_SPAWN_DEPTH=3`，工具白名单 `tools: List<String>` | `tool/AgentSpawnTool.java` |

亮点（opencode 没有的）：
- **远程 HTTP subagent** 一等公民（Java `SubagentDeclaration.url` + `RemoteSubagentStub`）。
- **流式事件总线** `SubagentEventBus` 把 child 事件实时回传父 sink，跨层 path 拼接。
- **workspace 双模** `ISOLATED`/`SHARED`，每个 subagent 可有独立 `AGENTS.md` + skills/ + knowledge/ + MEMORY.md。
- **label + agent_key** 双 handle，方便 LLM 通过人类可读名复用。
- **DynamicSubagentsMiddleware** 每轮 reasoning 从 namespace-scoped Filesystem 重新解析 — 支持多租户。

---

## 3. 差距与可借鉴项（按价值排序）

### ★★★ 1) 让子 agent 的「活体实例 + 对话历史」也能跨重启恢复
> 修订说明：原版本误判 Java 的 task_id 不持久化。重新审视 `WorkspaceTaskRepository` + `TaskRecord` + `SubagentsMiddleware.buildTaskSummary` 后，**task_id 本身已经做到跨压缩 / 跨节点 / 跨重启稳定**（详见下方“现状”）。真正的差距在 task_id 的语义和它覆盖的对象边界。

- **现状（已实现的部分）**：
  - `WorkspaceTaskRepository` 把 `TaskRecord` 持久化到 `agents/<parentAgentId>/tasks/<sessionId>.json`，是分布式 truth source；30 秒心跳 + 5/10 分钟孤儿扫描 + workspace 端 `cancelRequested` flag 做跨节点 cancel。
  - `TaskRecord` 字段：`taskId, subAgentId, parentSessionId, subSessionId, status, result, errorMessage, cancelRequested, createdAt, lastUpdatedAt, transportType, remoteBaseUrl, remoteHeaders`。
  - `getTask` 本地无 future 时回退读 workspace 合成 `BackgroundTask`；远程任务还会自动重建 polling。
  - `SubagentsMiddleware.buildTaskSummary` 每轮 reasoning 重写 SYSTEM 注入 task 列表；system prompt 显式写「After compaction or session resume, call `task_list()` first to recover all task IDs」。
  - 结论：「task 状态 / 结果」是持久的 ✅。
- **现状（仍是内存）**：
  - `AgentSpawnTool.agentSpawn()` 每次 `sessionId = "sub-" + UUID.randomUUID()`，同一逻辑 subagent 的多次任务**没有绑到同一个 `subSessionId`**（虽然 `TaskRecord` 这个字段早已预留）。
  - `agent_key → SpawnedAgent` 走 `ConcurrentHashMap`，活体 `Agent` 实例 + 子 agent 的对话上下文**纯内存**，进程重启即丢；后续 `agent_send` 链路断裂。
  - 也就是说：「子 agent 活体实例 + 对话历史」是非持久的 ❌。
- **opencode**：`task` 工具的 `task_id` 就是子 sessionID。子 session 是 DB-backed 的 `Session` 实体，**完整消息历史**都在表里；带同一 `task_id` 再调一次 `task` = 在那段历史上继续对话。它把「任务状态」「子 agent 身份」「子会话历史」收敛成同一个 id，从 schema 上就只有一种句柄。
- **Java vs opencode 差距矩阵**：

  | 维度 | opencode `task_id` | Java `task_id` |
  |---|---|---|
  | 标识对象 | 子 Session（一个持续对话） | 一次任务调用（request → response） |
  | 持久化粒度 | 状态 + 结果 + 全部消息历史 | 状态 + 结果（无对话历史） |
  | 多轮延续 | 同 id 再调 = 接着聊 | 必须靠内存 `agent_key` 调 `agent_send` |
  | 跨重启续聊 | 天然支持 | ❌ 失活 |

- **改造方向**：
  1. 把 `AgentSpawnTool.agent_spawn` 的 `sessionId` 改为「同一 (label / 调用方 + agent_id) 复用同一 `subSessionId`」，并存进 `TaskRecord.subSessionId`；
  2. 给 subagent 加一份独立的子会话历史持久化（如 `agents/<parentAgentId>/sub-sessions/<subSessionId>.jsonl`），由 `HarnessAgent` 的 `AgentState` 在每次 reasoning 后落盘 — 复用 [[java-agentstate-refactor]] 里 `AgentState` 作为唯一状态容器的设计；
  3. 让 `agent_spawn(task_id=…)` 或 `agent_send(sub_session_id=…)` 在父侧重启 / 节点迁移后能：①从 `subagentFactory.create()` 重建活体实例；②把 workspace 中的历史回灌到 `AgentState.contextMutable()`；③在新的内存 `agent_key` 与持久 `subSessionId` 之间建立映射；
  4. 长期上，让 `agent_key` 直接等于 `subSessionId`，消除「内存句柄 vs 持久句柄」二元区分（与 opencode 的「只有一个 id」一致）。
- **不能照抄的地方**：opencode 那套是单进程 SQLite 写库即可；Java 已经面向分布式（namespace-scoped Filesystem + heartbeat + 孤儿扫描），子会话历史持久化必须沿用同一条 workspace 路径与 namespace 解析逻辑，不能引入新的 DB 依赖。

### ★★★ 2) 通用 permission ruleset + 父→子继承推导
- **现状**：Java `SubagentDeclaration.tools` 只是“工具名白名单”，没有 `allow|ask|deny` 三态、没有 pattern、没有“父 agent edit deny 自动下沉”机制。`PlanModeMiddleware` 给主 agent 限制文件写入，subagent 完全感受不到。
- **opencode**：`Permission.Ruleset` + `deriveSubagentSessionPermission` 是显式、可推理、可合并的。
- **改造方向**：
  1. 引入 `PermissionRule {permission, pattern, action}` + `Ruleset` + `evaluate` 三件套；
  2. `SubagentDeclaration` 增加 `permission: Ruleset`；
  3. 父→子继承函数（`PlanModeMiddleware` 设置的 deny 进入推导第 1 行；父 session 的 deny + 外部目录规则进入第 2 行）；
  4. `agent_spawn` 调度时合并出子 session ruleset 并下推到 child `HarnessAgent` 的工具 invoker。

### ★★★ 3) 后台任务完成的“反向通知”机制
- **现状**：Java 仰赖 system-prompt 里那几条「Never poll immediately / Never poll in a loop」硬规则 + `task_list` 每轮 reasoning 注入摘要，本质上 LLM 必须**主动轮询**才能拿到结果。
- **opencode**：完成时把一段合成 user/text 部分**直接插回父 session 的消息流**（`backgroundMessage` 函数 + `ops.prompt(...)`），父 agent 下一轮天然看到，零轮询。
- **改造方向**：在 `TaskRepository` 完成回调里通过 `SessionService`/事件总线**向父 session 注入一段 `<task id=… state=completed>` 文本 part**；与现有 `SubagentEventBus` 协同（流式期间 forward 事件，完结时 inject 终态）。

### ★★ 4) 一次性工具屏蔽 + 自动禁递归 spawn
- **现状**：Java 用 `MAX_SPAWN_DEPTH=3` 兜底，但子 agent 上仍带 `agent_spawn`/`task` 工具，仍可能尝试递归调用。`tools` 白名单是声明期固定，无法在 spawn 现场临时屏蔽。
- **opencode**：每次 spawn 都会按 subagent 自身 permission 决定是否把 `task`/`todowrite` 强制 deny 掉。
- **改造方向**：
  - 在 `agent_spawn` 调用栈中加 `disabled_tools: List<String>` 参数；
  - 默认对子级屏蔽 `agent_spawn`/`agent_send`，除非 declaration 显式 `allow`；
  - 与 (2) 合一：成为 ruleset 派生的副作用。

### ★★ 5) 把 compaction / title / summary 内部 agent 纳入统一 schema
- **现状**：Java 的 `CompactionMiddleware` / 等价于 summary 的逻辑散在 middleware 里，没有“agent 注册表”的位置，用户**无法替换** compaction 用的模型或 system prompt。
- **opencode**：`compaction`/`title`/`summary` 都是 `Agent.Info` 条目（`hidden:true`），享受所有 declaration 字段（permission、model、temperature、prompt）。
- **改造方向**：把这些内部流程包成 `hidden:true` 的 `SubagentDeclaration`，注册到 `DefaultAgentManager`；middleware 改成查 manager 拿 agent 实例。带来的收益：用户可以 `subagents/compaction.md` 自定义压缩模型与 prompt。

### ★★ 6) Per-agent 模型超参（temperature / top_p / variant / steps）
- **现状**：`SubagentDeclaration` 只有 `model: String` + `maxIters: int`。
- **opencode**：`temperature` / `topP` / `variant` / `steps` 都可声明，比如 `title` 用 0.5、`compaction` 用 deny-all 权限。
- **改造方向**：扩 `SubagentDeclaration` + `AgentSpecLoader` 的 frontmatter 解析；通过 `ReActAgentBuilder` 注入到 `ModelOptions`。

### ★★ 7) LLM 反推 subagent 规格（`Agent.generate`）
- **现状**：Java 完全靠人手写 `subagents/*.md`。
- **opencode**：`Agent.generate({description})` 给模型一段 `PROMPT_GENERATE` 直接产 `{identifier, whenToUse, systemPrompt}`，再写盘。
- **改造方向**：新增 `SubagentSpecGenerator`（独立 service），输入描述输出符合 `SubagentDeclaration` schema 的 markdown；接到一个新的 `agent_generate` 工具或 CLI 子命令上。

### ★★ 8) 通用 `BackgroundJob` 运行时（解耦 subagent）
- **现状**：Java `TaskRepository` 围绕 `TaskRunSpec`（Local/Remote 二选一）构建，硬编码到 subagent 场景。
- **opencode**：`BackgroundJob.Service` 接受任意 `Effect<string, unknown>`，提供 list/get/start/wait/cancel — `task` 工具只是一个消费者。
- **改造方向**：抽象出 `JobRuntime { start(Mono<String>): JobInfo; cancel; wait; list }`；让 subagent 后台只是它的一个 caller；`ShellExecuteTool` 等长任务也可以接入同一 `task_list/task_cancel`。

### ★ 9) `mode: primary | subagent | all`
- **现状**：Java 注册的 subagent 全部既能被 `agent_spawn` 调度，又能作为 `HarnessAgent` 顶层启动 — 没有“仅可被调度”的限制。
- **opencode**：明确区分能被人直接对话的 primary 与只能被调度的 subagent。
- **改造方向**：`SubagentDeclaration.mode`；HTTP/CLI 顶层入口校验 `mode != subagent`。

### ★ 10) `hidden: true` 标志位
- 让 `agent_list` 默认隐藏内部 agent（compaction/title/summary），保持 LLM 视野洁净，同时仍可手动列出全部。

### ★ 11) Subtask 作为消息一等 part
- 若 Java 准备支持「用户在 prompt 里用 `@agent` 预先派发」的交互形态，需要在 Msg/Part 模型里加 `SubtaskBlock`，并在 reasoning 编排里识别它直接调度，而非依赖 LLM 出 tool_call。
- 短期价值有限；如果产品形态是 IDE/UI 集成则收益较高。

---

## 4. 落地建议路线

按依赖关系与改造成本排序：

1. **Phase A（低风险增量）**
   - (6) 扩 declaration 字段（temperature/top_p/variant/steps），改 `AgentSpecLoader` + `ReActAgentBuilder`。
   - (9)/(10) 加 `mode` 与 `hidden`，CLI/HTTP 入口校验。
   - (7) LLM 生成 spec（独立 service，不动核心）。

2. **Phase B（流程变化）**
   - (3) 后台完成反向注入：复用 `SubagentEventBus` + `SessionService`，把硬规则提示词改为事件驱动。
   - (1) 持久化子 agent 的「活体重建材料 + 对话历史」：把 `TaskRecord.subSessionId` 真正用起来 → 子会话历史落 `agents/<parentAgentId>/sub-sessions/<subSessionId>.jsonl` → `agent_spawn(task_id=…)` / `agent_send(sub_session_id=…)` 在重启后重建活体 subagent → 长期让 `agent_key == subSessionId`。与 (3) 一起把句柄收敛为一份。

3. **Phase C（架构升级）**
   - (2) 引入 Permission ruleset + 父→子继承；同步把 `PlanModeMiddleware` 的限制翻译成 ruleset deny 规则。
   - (4) 一次性工具屏蔽 + 自动禁递归 spawn 作为 (2) 的副作用落地。
   - (5) compaction/title/summary 改为 hidden subagent declaration；middleware 改造为 manager 消费者。
   - (8) 抽象 `JobRuntime`，重构 `TaskRepository` 为它的子集，把长 shell/工具调用接入。

---

## 5. 不建议照抄的部分

- **opencode 的 Effect/Layer DI 体系**：与 Java/Reactor 的栈不匹配，照搬代价远高于收益。
- **`subtask` 消息 part**：在没有 IDE/UI 同步消费的场景，纯 backend agent 没必要复杂化消息 schema。
- **`color` 字段**：纯 UI 装饰，对 backend agent 没有功能价值。
- **CLI 内的 markdown agent scaffolder**：Java 侧已有 `AgentSpecLoader`，需要的只是“写盘工具”，不需要照搬 prompt 体系。
