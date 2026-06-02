---
title: "计划模式（Plan Mode）"
description: "动手前先想清楚：只读阶段写计划文件，HITL 后再进入执行阶段"
---

## 作用

Plan Mode 让 agent 在动手前先"把意图想清楚 + 写下来"再执行。开启后 agent 进入一个**只读阶段**：

- 只能调用**只读工具**和 4 个白名单工具：`plan_enter` / `plan_write` / `plan_exit` / `todo_write`；
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

中间任意时刻调用非白名单工具（比如 `write_file` / `execute`）都会被即时拒绝并返回类似这样的结果给模型：

```text
[Tool denied — plan mode is active]
Only read-only tools and plan_enter / plan_write / plan_exit / todo_write are allowed.
```

模型看到拒绝信息会自然地切回"先写计划"。

## Plan 阶段的状态会被持久化

Plan Mode 是**运行时状态**，会随 `AgentState` 自动持久化——进程重启、节点切换、跨副本恢复后，**plan 阶段会一起恢复**。计划文件本身写到工作区的 `plans/` 下，跟着你选的文件系统模式（本机 / 沙箱 / 远端 KV）走，分布式可用。

## 程序化进出 Plan Mode

需要在业务代码里主动控制（例如管理台按钮）：

```java
agent.enterPlanMode();    // 等价于 LLM 调 plan_enter
agent.exitPlanMode();     // 等价于 plan_exit；程序入口不会触发 HITL
agent.isPlanModeActive();
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

## 相关文档

- [工作区](./workspace) — `plans/` 目录的位置
- [子 Agent](./subagent) — `todo_write` ≠ subagent task，不要混淆
- [架构](./architecture) — Plan Mode 在 call() 时序中的位置
