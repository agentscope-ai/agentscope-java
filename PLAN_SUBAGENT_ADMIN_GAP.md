# Plan 模式与子智能体任务管理：Admin API 能力盘点

> 创建：2026-05-31
> 涉及代码：`agentscope-extensions/agentscope-spring-boot-starters/agentscope-admin-spring-boot-starter/`
> 关联：[ADMIN_OPS_API_PLAN.md](./ADMIN_OPS_API_PLAN.md)、[OPENCODE_OPS_COMMANDS_REFERENCE.md](./OPENCODE_OPS_COMMANDS_REFERENCE.md)

## 0. TL;DR

| 能力 | core 已有 | admin starter 已暴露 | 端点 |
|---|---|---|---|
| Plan 模式开关 / 读取 | ✅ | ✅ Phase 3b | `GET/POST /v1/admin/sessions/{id}/plan` `:enter-plan-mode` `:exit-plan-mode` |
| Agent 任务列表（in-`AgentState`） | ✅ | ✅ Phase 3c | `GET /v1/admin/sessions/{id}/tasks` |
| 子智能体声明清单 | ✅ | ✅ Phase 3d | `GET /actuator/agentscope-subagents` |
| 子智能体后台任务（`BackgroundTask`） | ✅ | ✅ Phase 3d | `GET/DELETE /v1/admin/sessions/{id}/subagent-tasks[/{taskId}]` |
| 子智能体事件流 (`SubagentEventBus`) | ✅ | ⏳ TODO（SSE） | — |
| Agent 任务 CRUD（写） | 部分 | ❌ 暂未暴露写 | — |
| 子智能体动态注册 | 部分 | ❌ 暂未暴露 | — |

---

## 1. core 已有的能力（盘点）

### 1.1 Plan 模式
- `io.agentscope.core.state.PlanModeContextState`
  - `boolean planActive`、`String currentPlanFile`
  - 持久化在 `AgentState.planModeContext`，可跨节点恢复。
- `io.agentscope.core.ReActAgent`
  - `enterPlanMode()` / `exitPlanMode()` / `isPlanModeActive()` — 编程入口。
  - `Builder.enablePlanMode()` — 构建期为 agent 装上 `PlanModeMiddleware`（拦截 mutating 工具）+ `PlanModeTools`（plan_enter / plan_exit 给模型调）。
- `io.agentscope.harness.agent.middleware.PlanModeMiddleware` — 真正执行"只读 / 设计先行"的中间件。
- `io.agentscope.harness.agent.workspace.plan.PlanModeManager` — 管理 markdown 蓝图文件（默认 `DEFAULT_PLAN_DIR`）。

**两套语义并存**：
- **AgentState 中的 `planActive` 标志**：是否处于 plan 模式（持久化）。
- **`PlanModeMiddleware` 是否被装入**：决定 plan 模式是否被**实际执行**。
  没装中间件的话，flag 翻成 true 也没人拦截 mutating 调用。

### 1.2 Agent 任务（in-`AgentState`，TodoWrite 类）
- `io.agentscope.core.state.TaskContextState` — `List<Task>`，存在 `AgentState.tasksContext`。
- `io.agentscope.core.state.Task`：
  - `subject` / `description` / `state` (PENDING/IN_PROGRESS/COMPLETED) / `owner` / `blocks` / `blockedBy` / `metadata` / `createdAt` / `id`。
  - Builder 模式构造，序列化用 `@JsonProperty("created_at")` 等下划线 wire 名。
- 谁会写入？由 Agent 内部的 TodoWrite 类工具 / 元工具调用 `tasksMutable().add(...)`。

### 1.3 子智能体声明
- `io.agentscope.harness.agent.subagent.SubagentDeclaration` — 富元数据：`name` / `description` / `workspaceMode`（SHARED/ISOLATED）/ `workspacePath` / `inlineAgentsBody` / `model` / `maxIters` / `toolAllowlist`。
- `io.agentscope.harness.agent.subagent.WorkspaceMode` — 5 行决策表（详见枚举注释）。
- `io.agentscope.harness.agent.middleware.SubagentEntry` — 运行时形态（name + description + factory），存在 `SubagentsMiddleware.entries` 私有列表。
- `io.agentscope.harness.agent.middleware.SubagentsMiddleware` — 把声明注入系统提示词、暴露 task 工具给模型。

> **坑**：`SubagentsMiddleware` 没有公开访问器把 `entries` 暴露出来，admin 端无法从一个已构造的 `HarnessAgent` 里反向读出子智能体列表。这是为什么 admin starter 走了 SPI（`SubagentInventory`）+ Spring bean 扫描 的路子。

### 1.4 子智能体后台任务
- `io.agentscope.harness.agent.subagent.task.TaskRepository` — 接口，session-scoped。
  - `getTask` / `putTask` / `removeTask` / `listTasks(sessionId, TaskStatus)` / `cancelTask`。
- 默认实现：`DefaultTaskRepository`（内存）/ `WorkspaceTaskRepository`（workspace 持久化，多节点共享）。
- `BackgroundTask` — `taskId` / `agentId` / `createdAt` / `lastCheckedAt` / `getTaskStatus()` / `getStatus()` / `getResult()` / `getError()` / `cancel()` / `waitForCompletion(timeout)`。
- `TaskStatus` 枚举 + `isTerminal()`。
- `HarnessAgent.Builder.taskRepository(TaskRepository)` 注入。

### 1.5 子智能体事件
- `io.agentscope.core.agent.SubagentEventBus` — 通过 Reactor Context（key `agentscope.subagent.event.bus`）注入；同步子智能体把自身 `AgentEvent` 推到父流。

---

## 2. admin starter 这一轮新增的端点（Phase 3）

### 2.1 数据面（per-session REST，base `/v1/admin`）
- **Plan 模式**
  - `GET  /sessions/{id}/plan` → `PlanModeView{ planActive, currentPlanFile, planMiddlewareEnabled }`
  - `POST /sessions/{id}:enter-plan-mode` → 调 `ReActAgent.enterPlanMode()` + persist；写操作走 `WriteGuard`；自动 snapshot 进 `SnapshotStore`（可 undo）
  - `POST /sessions/{id}:exit-plan-mode` → 反之
- **Agent 任务（in-AgentState）**
  - `GET /sessions/{id}/tasks` → `List<AgentTaskView>`（只读；含 blocks/blockedBy/metadata）
- **子智能体后台任务**
  - `GET    /sessions/{id}/subagent-tasks[?status=RUNNING]` → `List<SubagentTaskView>`
  - `GET    /sessions/{id}/subagent-tasks/{taskId}`
  - `DELETE /sessions/{id}/subagent-tasks/{taskId}` → `cancelTask`（写）

### 2.2 控制面（Actuator）
- `GET /actuator/agentscope-subagents` → `List<SubagentDescriptor>`（spring bean / declaration 合并去重）

### 2.3 命令注册
新增 9 条 `AdminCommand`：`session.plan`、`session.enter_plan_mode`、`session.exit_plan_mode`、`session.agent_tasks`、`subagent.task.list`、`subagent.task.get`、`subagent.task.cancel`、`system.subagents`。
内置命令总数：**26**（15 数据面 + 11 控制面），全部出现在 `GET /actuator/agentscope-commands` 自描述里。

---

## 3. 关键设计决策

### 3.1 子智能体清单走 SPI，不从 HarnessAgent 反射
`SubagentsMiddleware.entries` 私有，没 getter。两条路：
- 反射 → 跨版本脆弱，被否决。
- 在 admin starter 暴露 `SubagentInventory` SPI，默认实现 `SpringSubagentInventory` 扫 Spring 里的 `SubagentEntry` / `SubagentDeclaration` beans。

**对接方式**：用户在 Spring 配置中把 `SubagentDeclaration` 暴露成 bean（不少 starter 就是这么 hooking 子智能体的），admin 端自动看到。`SubagentDeclaration` 比 `SubagentEntry` 元数据更丰富，所以同名时 declaration 胜出。

如果用户没暴露 bean，`/actuator/agentscope-subagents` 返回空 —— `agentscope-doctor` 可以将来加一个 check 标黄提示。

### 3.2 `TaskRepository` 设计为可选
- `TaskRepository` 是用户业务侧 bean（典型由 `HarnessAgent.Builder` 配置），admin starter 通过 `ObjectProvider<TaskRepository>` 读，**没有就走 fail-soft 空响应**。
- `SubagentTaskOperations.isConfigured()` 用于 `/actuator/agentscope-doctor` 后续将其纳入自检（这一轮还没做）。

### 3.3 Plan 模式写操作走 SnapshotStore
`:enter-plan-mode` / `:exit-plan-mode` 都触发 `snapshots.push(state.toJson())` —— `:undo` 能反悔。这与 `:compact` 是同一套机制。

### 3.4 Agent 任务暂不暴露写
`Task` 的 write 路径（添加 / 状态推进 / 依赖编辑）目前在 core 是通过**模型的工具调用**写进 `tasksContext`。给 admin 暴露写会绕过工具校验，影响一致性。如果未来要给 SRE 一个手工纠错口，需要 core 提供 `TaskOps` SPI 或者直接 `tasksMutable()` 锁机制。

### 3.5 RuntimeContext 用 `empty()` 占位
`TaskRepository` 每个方法都收 `RuntimeContext` 用于多租户命名空间隔离。admin starter 没有调用方的 `RuntimeContext`（HTTP 调用 ≠ agent 执行栈），所以传 `RuntimeContext.empty()`。
**影响**：基于 workspace 的 `WorkspaceTaskRepository` 在多用户场景下，admin 视角会落到 default namespace。这个设计取舍要在文档里讲清楚。

---

## 4. 已知缺口（下一步）

### 4.1 短期（不动 core）
- [ ] `agentscope-doctor` 加 check："`TaskRepository` 已配置？"
- [ ] SSE 流：`GET /v1/admin/sessions/{id}/events` 暴露 ReActAgent 的 `streamEvents` —— 让 console 实时看到子智能体事件
- [ ] OpenAPI `@Tag(name = "Plan")` 等装饰，让 Swagger UI 把 plan / subagent 端点分组更明显
- [ ] `SubagentTaskOperations.list` 加分页 + status 多值 filter
- [ ] WorkspaceTaskRepository 多租户透传：让 admin 接 header 指定 `x-user-id` → 构造对应 `RuntimeContext`

### 4.2 中期（需要 core 配合）
- [ ] **`SubagentsMiddleware.entries()` 公开 getter** —— 让 admin starter 也能从已构造的 `HarnessAgent` 反向读出子智能体（更准确，避免依赖 bean 注册习惯）
- [ ] **`AgentState.tasksContext` 写操作锁** —— 让 admin 安全地手工纠正 Task 状态而不和 model-driven 写竞争
- [ ] **plan 蓝图文件读写端点** —— `GET /v1/admin/sessions/{id}/plan/file` 返回 `PlanModeManager` 读出来的 markdown
- [ ] `TaskRepository.putTask` 暴露 → admin 可手工触发一个子智能体任务（结合 `SubagentInventory` 选 agent）

### 4.3 长期（需要新设计）
- [ ] Plan 蓝图编辑流：admin 端的 GUI 直接改 plan 文件，触发 agent 在 plan 模式下重新阅读
- [ ] 跨 agent 的任务图（dependency graph）可视化 + 拓扑 lint
- [ ] 子智能体执行预算：per-subagent token / 时长 cap，admin 可在线调

---

## 5. 测试与验证

```
mvn -pl agentscope-extensions/agentscope-spring-boot-starters/agentscope-admin-spring-boot-starter \
    test -Dspotless.check.skip=true -Dmaven.javadoc.skip=true
# Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
```

新增测试：
- `SpringSubagentInventoryTest` —— 空 / 单 entry / 同名去重
- `SubagentTaskOperationsTest` —— 无 repo 时 noop / status 过滤解析 / BackgroundTask 字段映射 / cancel 透传
- `AgentscopeAdminAutoConfigurationTest`：新增 `SubagentInventory` / `SubagentTaskOperations` / `AgentscopeSubagentsEndpoint` 装配校验 + 命令数 26 校验

---

## 6. 给业务方接入的样例配置

```java
// 1. 暴露子智能体声明给 admin
@Bean
SubagentDeclaration codeReviewerDecl() {
    return SubagentDeclaration.builder()
        .name("code-reviewer")
        .description("Reviews code for security/performance/readability.")
        .workspaceMode(WorkspaceMode.SHARED)
        .model("qwen-plus")
        .maxIters(8)
        .build();
}

// 2. 暴露 TaskRepository 给 admin（同时 HarnessAgent 也用它）
@Bean
TaskRepository taskRepository(WorkspaceManager ws, String parentAgentId) {
    return new WorkspaceTaskRepository(ws, parentAgentId);
}

// 3. 业务 HarnessAgent 构造时复用上面两个 bean
@Bean
HarnessAgent rootAgent(Model m, Toolkit tk, TaskRepository repo,
                       SubagentDeclaration reviewerDecl) {
    HarnessAgent agent = HarnessAgent.builder()
        .model(m)
        .toolkit(tk)
        .taskRepository(repo)
        .subagent(reviewerDecl)
        .enablePlanMode()  // ← 真正激活 plan 中间件
        .build();
    agentRegistry.register(agent);
    return agent;
}
```

之后：
- `GET /actuator/agentscope-subagents` 看到 `code-reviewer`
- `GET /v1/admin/sessions/{id}/plan` 看到 `planMiddlewareEnabled: true`
- `POST /v1/admin/sessions/{id}:enter-plan-mode` 强制切到只读
- `GET /v1/admin/sessions/{id}/subagent-tasks?status=RUNNING` 看到正在跑的 review 任务
- `DELETE /v1/admin/sessions/{id}/subagent-tasks/{id}` 中断它

---
