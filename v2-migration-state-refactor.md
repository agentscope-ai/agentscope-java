# v2 迁移分析：AgentState 取代 Memory/Session 成为唯一状态容器

> 完成时间：2026-05-27

## 背景

v1 架构中，agent 运行时状态分散在多个组件中：
- `Memory`（InMemoryMemory）持有对话历史 `List<Msg>`
- `Session` 提供持久化存储（save/load 各种 State 对象）
- `StateModule` 接口让 agent 具备 saveTo/loadFrom 能力

v2 的目标是让 `AgentState` 成为单一状态容器，Memory 和 Session 降为兼容层。

## 迁移方案

### AgentState 字段清单

| 字段 | 类型 | 用途 |
|---|---|---|
| `sessionId` | String | 会话标识 |
| `summary` | String | 上下文压缩摘要 |
| `context` | List\<Msg\> | 对话历史（原 Memory 职责） |
| `replyId` | String | 当前回复标识 |
| `curIter` | int | 当前推理迭代计数 |
| `shutdownInterrupted` | boolean | 是否被 shutdown 中断 |
| `permissionContext` | PermissionContext | 工具权限上下文 |
| `toolContext` | ToolContext | 工具缓存 + 激活组 |
| `tasksContext` | TaskContext | 任务列表（替代 PlanNotebook） |

### 核心变更

1. **ReActAgent 核心循环** — 所有对话读写从 `memory.getMessages()` / `memory.add()` 改为 `state.contextMutable()`
2. **StateBackedMemory** — 新增适配器，让 deprecated `getMemory()` 透传到 `state.contextMutable()`，向后兼容
3. **LegacyStateLoader** — 工具类，支持从 v1 session 数据加载到 AgentState
4. **Memory/Session/StateModule** — 移入 `legacy/` 包，标 `@Deprecated(since = "2.0.0")`

### 关键设计决策

- `AgentState` 使用 Builder 模式构建，Jackson 序列化用 `@JsonProperty("snake_case")`
- `context` 通过 `contextMutable()` 暴露可变 handle，`getContext()` 返回防御性拷贝
- `AgentState implements State` — 保证能通过 deprecated `Session.save()` 路径存储
- `getAgentState()` 在 `AgentBase` 中是 default 方法返回 null，避免破坏 UserAgent、RemoteSubagentStub 等子类

### 影响的文件

- `AgentState.java` — 新增 `State` 接口实现 + `shutdownInterrupted` 字段
- `ReActAgent.java` — 构造函数创建 `StateBackedMemory`，核心循环全部改用 `state.contextMutable()`
- `AgentBase.java` — 新增 `getAgentState()` default 方法
- `StateBackedMemory.java` — 新文件，Memory 适配器
- `LegacyStateLoader.java` — 新文件，v1 数据加载
- `StateModule.java` — 移入 legacy 包

### 验证

- ReActAgentTest, ReActAgentStateTest, ReActAgentSummarizingTest, ReActAgentRuntimeContextTest, StateAndSessionTest 全部通过
- `mvn test -pl agentscope-core` 全量回归通过（4517 tests, 仅 1 个 pre-existing failure）
