---
title: "计划模式（Plan Mode）"
description: "动手前先想清楚：只读阶段写计划文件，HITL 后再进入执行阶段"
---

## 作用

Plan Mode 让 agent 在动手前先"把意图想清楚 + 写下来"再执行。开启后 agent 进入一个**只读阶段**：

- 只能调用**只读工具**和 4 个白名单工具：`plan_enter` / `plan_write` / `plan_exit` / `todo_write`（shell 可按需放开，见[下文](#在-plan-阶段放开-shell可选)）；
- 其它工具调用一律被拒绝（agent 看到一条"plan 阶段拒绝"提示）；
- 退出 Plan Mode 走 HITL 确认（复用权限系统的 ASK），避免模型一意孤行直接进入执行。

这条流程明确把"设计 → 写计划 → 人确认 → 执行"四步固化下来，配合 `todo_write` 与子 agent，能在长任务里有效降低"边想边改、改坏一片"的概率。

## 开启

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("planner")
    .model(model)
    .workspace(workspace)
    .enablePlanMode()                          // 装 PlanMode 三件套
    .planFileDirectory("plans")                // 可选；默认 "plans"
    .build();
```

Builder 选项：

| 方法 | 默认 | 说明 |
|------|------|------|
| `enablePlanMode()` / `enablePlanMode(boolean)` | `false` | 是否开启 |
| `planFileDirectory(String)` | `"plans"` | 计划文件根目录（workspace 相对） |
| `allowShellInPlanMode()` / `allowShellInPlanMode(boolean)` | `false` | 按需放开 plan 阶段的 shell（`execute`）——见[在 plan 阶段放开 shell](#在-plan-阶段放开-shell可选) |

也可以同时打开 `enableTaskList()`，让 plan 阶段里写的 todos 在每次推理前以小提示形式给 agent 看一遍。

## 三个工具

| 工具 | 作用 | 参数 |
|------|------|------|
| `plan_enter` | 进入 Plan Mode | 无 |
| `plan_write` | 把计划写到当前计划文件（默认 `plans/PLAN.md`） | `content` |
| `plan_exit` | 退出 Plan Mode → 执行阶段；HITL 确认 | `rationale`（可选） |

`plan_write` 是**专门为 Plan Mode 设计的写入入口**——避开了把通用 `write_file` 加入白名单的安全风险（后者会让模型在 plan 阶段写任意文件）。

## 工作流

```{mermaid}
sequenceDiagram
    autonumber
    participant U as User
    participant A as Agent
    participant H as Human (HITL)
    participant FS as workspace

    U->>A: "帮我重构 X 模块"
    A->>A: plan_enter
    A->>A: 思考 → 调 read_file / grep_files（只读）
    A->>FS: plan_write 写到 plans/PLAN.md
    A->>H: plan_exit → 弹 HITL 确认
    H-->>A: ConfirmResult(true)
    A->>A: 进入执行阶段，所有工具解禁
```

中间任意时刻调用非白名单工具（比如 `write_file`；`execute` 默认也被拒，除非你[按需放开](#在-plan-阶段放开-shell可选)）都会被即时拒绝并返回类似这样的结果给模型：

```text
[Tool denied — plan mode is active]
Only read-only tools and plan_enter / plan_write / plan_exit / todo_write are allowed.
```

模型看到拒绝信息会自然地切回"先写计划"。

## 如何判断运行结果

是否进入 plan mode 由模型自主决定，所以一次运行可能落到四种终态。**只看 `isPlanModeActive() == false` 是有歧义的**——别在没确认"是否真的规划过"之前就当成成功：

| 终态 | 含义 |
|------|------|
| 从未进入 plan mode | 模型选择直接在 build 模式工作——合法决定，常因任务与 workspace 不匹配 |
| 进入 → `plan_exit` | 成功：规划完、获批，已进入 build 模式 |
| 仍在 plan mode + 有 `PLAN.md` | 起草了计划但没退出；在同一 session 发后续消息批准继续 |
| 仍在 plan mode + 无 `PLAN.md` | "只说不做"：最终文本看着像计划但没真正写出——补更具体的输入或换匹配的 codebase |

要在代码里区分这几种，可在监听最终 `isPlanModeActive()` 和计划文件是否存在的同时，记录是否调用过 `plan_enter` / `plan_write`（例如从 `ToolCallStartEvent` 捕获）。

## 在 plan 阶段放开 shell（可选）

默认情况下，shell 工具（`execute`）在 plan 阶段是**被拒绝**的。shell 是*双用途*工具：同一次调用既可能是读（`cat` / `ls` / `grep` / `git log`），也可能是写（`rm` / `>` / `git commit` / `npm install`），而 Plan Mode 完全靠**工具名**判定是否放行——无法区分这一次是读还是写。默认禁掉 shell，是为了保住"只读"这条保证。

但通过 shell 读取各种内容，往往是调查代码库、产出一个*切实可行*计划的最灵活方式。如果你愿意承担这个取舍，可以按需放开：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("planner")
    .model(model)
    .workspace(workspace)
    .enablePlanMode()
    .allowShellInPlanMode()   // 让模型在 plan 阶段以只读方式跑 shell
    .build();
```

开启后：

- `execute` 被加入 plan 阶段放行名单，模型可以用 shell 做调查；
- plan banner 会追加一条提示，要求模型把 shell 用法限制在**只读**（`cat` / `ls` / `grep` / `git log/diff/show/status`），在计划获批前**不要**跑 mutating 命令；
- 专用的文件编辑工具（`write_file` / `edit_file`）**仍然被拒**——它们是主要的改动入口，所以对文件写入的只读意图依然被强制保证。

这与 OpenCode 处理其 plan agent 的方式一致：放开 shell 用于调查、硬禁 edit/write 工具、靠提示词把 shell 约束为只读。因此这条保证比默认情况**更弱**（模型仍可能通过 shell 改东西），建议配合**沙箱文件系统**一起开，把爆炸半径关进沙箱。

## 运行期切换权限模式（"危险开关"逃生口）

Plan Mode 只是一个具体的阶段开关。在它之下，每个 session 都带着一个 [`PermissionMode`](../building-blocks/context)，由权限引擎在评估时使用。你可以在运行期翻转这个 mode——比如提供一个由用户显式触发的"跳过所有权限确认"开关（类似其它编码工具里的 YOLO / dangerous-skip 开关）：

```java
RuntimeContext ctx = RuntimeContext.builder().sessionId("my-session").build();

agent.setPermissionMode(ctx, PermissionMode.BYPASS);    // 全部放开、不再弹确认
// ... 跑需要完整权限的操作 ...
agent.setPermissionMode(ctx, PermissionMode.DEFAULT);   // 恢复正常管控

PermissionMode current = agent.getPermissionMode(userId, sessionId);
```

`setPermissionMode(...)` 会保留该 session 已配置的 allow/deny/ask 规则与工作目录——只改 mode——并重建该 session 缓存的权限引擎，使切换在**下一次** call 生效；正在进行中的 call 仍沿用它启动时的引擎。

⚠ `BYPASS` 会关闭所有规则评估，因此应当作为显式的、按 session 的主动操作，并建议配合沙箱使用。如果想要**无人值守且不弹确认、但仍保留管控**，请改用 `PermissionMode.DONT_ASK`（ASK 决策会变成 DENY，而不是被自动放行）。

## Plan 阶段的状态会被持久化

Plan Mode 是**运行时状态**，会随 `AgentState` 自动持久化——进程重启、节点切换、跨副本恢复后，**plan 阶段会一起恢复**。计划文件本身写到工作区的 `plans/` 下，跟着你选的文件系统模式（本机 / 沙箱 / 远端 KV）走，分布式可用。

## 程序化进出 Plan Mode

需要在业务代码里主动控制（例如管理台按钮）：

```java
RuntimeContext ctx = RuntimeContext.builder().sessionId("my-session").build();
agent.enterPlanMode(ctx);    // 等价于 LLM 调 plan_enter
agent.exitPlanMode(ctx);     // 等价于 plan_exit；程序入口不会触发 HITL
agent.isPlanModeActive(ctx);
```

如果用了 `agentscope-admin-spring-boot-starter`，还可以通过 admin HTTP 接口操作（`POST /v1/admin/sessions/{id}:enter-plan-mode` / `:exit-plan-mode` / `GET /v1/admin/sessions/{id}/plan`）。

## 与子 agent 的关系

⚠ 当前**已知缺口**：Plan Mode 期间通过 `agent_spawn` 启动的子 agent **不会自动继承只读限制**。如果希望子 agent 也只读：

- 在子 agent 的声明里把 `tools` 过滤到只读集合；或
- 在子 agent 的 builder 里也开 `enablePlanMode()` 并自行进入

未来版本会让 plan 阶段的限制按父→子自动传播。

## 与 `todo_write` 的协作

Plan Mode 与 `todo_write`（core 提供）是两个**独立但常常一起用**的概念：

- **Plan Mode** —— 阶段开关 + 计划文件 + HITL 退出
- **`todo_write`** —— 在执行阶段维护"当前要做什么"的结构化清单（全量替换，必须恰好一个 `in_progress`）

典型工作流：plan 阶段写完 `PLAN.md` → `plan_exit` → 执行阶段用 `todo_write` 把 PLAN 拆成 5–8 条 todo → 逐条推进。Agent 每轮推理前能看到 todos 的小提示，帮助保持聚焦。

⚠ 不要和子 agent 的**后台任务**（`task_output` / `task_cancel` / `task_list`）混淆——那是另一回事，详见 [子 Agent](./subagent)。

## 查看任务列表

任务列表保存在 `AgentState.tasksContext` 中，每次 `call()` 后自动持久化。在业务代码中读取：

```java
List<Task> tasks = agent.getAgentState(userId, sessionId)
        .getTasksContext()
        .getTasks();

for (Task t : tasks) {
    System.out.printf("[%s] %s%n", t.getState(), t.getSubject());
    // state: PENDING / IN_PROGRESS / COMPLETED
}
```

如果用了 `agentscope-admin-spring-boot-starter`，可以直接调 admin REST 接口：

```
GET /v1/admin/sessions/{sessionId}/tasks
```

返回每个任务的 subject、state、owner 和依赖关系（`blocks` / `blockedBy`）。

如果需要通过事件流实时感知任务变更，可以在 `streamEvents()` 中监听 `todo_write` 工具调用：

```java
agent.streamEvents(message)
    .filter(e -> e.getType() == AgentEventType.TOOL_RESULT_END)
    .filter(e -> "todo_write".equals(((ToolResultEndEvent) e).getToolCallName()))
    .doOnNext(e -> {
        // 从 state 中读取最新任务列表
        var tasks = agent.getAgentState(userId, sessionId)
                .getTasksContext().getTasks();
        updateUI(tasks);
    })
    .subscribe();
```

## 相关文档

- [工作区](./workspace) — `plans/` 目录的位置
- [子 Agent](./subagent) — `todo_write` ≠ subagent task，不要混淆
- [架构](./architecture) — Plan Mode 在 call() 时序中的位置
