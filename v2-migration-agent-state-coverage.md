# v2 迁移分析：v1 持久化属性 vs AgentState 覆盖范围

> 完成时间：2026-05-27

## 背景

v1 的 `ReActAgent.saveTo(session, sessionKey)` 通过 Session 分别持久化了 4 组数据。迁移到 AgentState 后需要确认这些数据是否都有对应的承载位置，尤其是与 HITL、tool group 等运行时行为相关的状态。

## v1 saveTo 持久化的 4 组数据 vs AgentState 对应关系

### 1. `memory_messages` — 对话历史 (List\<Msg\>)

| v1 | v2 AgentState |
|---|---|
| `session.save(key, "memory_messages", msgs)` via `memory.saveTo()` | `AgentState.context` (List\<Msg\>) |

**结论**：✅ 已覆盖。`StateBackedMemory.saveTo` 写 `memory_messages`；AgentState 的 `context` 字段是主通道。Round-trip 无丢失。

### 2. `agent_meta` — Agent 元数据

| v1 | v2 AgentState |
|---|---|
| `session.save(key, "agent_meta", AgentMetaState(agentId, name, description, sysPrompt))` | 不在 AgentState 中 |

**结论**：❌ 不需要迁移。`AgentMetaState` 包含 agentId、name、description、sysPrompt，这些是**构建时不可变配置**，不是运行时状态。v2 中 agent 实例由 builder 创建时确定这些值，恢复时用同一个 builder 配置构建即可。v1 中保存它们主要是方便 debug/审计。

### 3. `toolkit_activeGroups` — 工具组激活状态

| v1 | v2 AgentState |
|---|---|
| `session.save(key, "toolkit_activeGroups", ToolkitState(toolkit.getActiveGroups()))` | `AgentState.toolContext.activatedGroups` (List\<String\>) |

**结论**：✅ 已覆盖（经修复）。字段已存在但**原始代码缺少双向同步**：
- v1 `saveTo()` 中：`toolkit.getActiveGroups()` → Session
- v1 `loadFrom()` 中：Session → `toolkit.setActiveGroups()`
- v2 原始状态：缺少同步，动态激活的工具组在序列化后会丢失

**已实施修复**：
- `ToolContext.java` — 新增 `setActivatedGroups(List<String>)` 方法
- `ReActAgent` 构造函数 — 从 `state.toolContext.activatedGroups` 恢复到 toolkit
- `ReActAgent.getState()/getAgentState()` — 调用 `syncToolkitToState()` 在返回前同步

### 4. `planNotebook_state` — 计划本状态

| v1 | v2 AgentState |
|---|---|
| `session.save(key, "planNotebook_state", PlanNotebookState(currentPlan))` via `planNotebook.saveTo()` | 不在 AgentState 中 |

**结论**：❌ 无需迁移。PlanNotebook 是 v1 的规划功能，v2 中整个 plan 包已标 `@Deprecated`，Python 2.0 也没有 PlanNotebook。AgentState 中的 `TaskContext` 是 v2 的替代品——更轻量、与 Python 对齐。

## HITL 相关状态分析

| 状态 | 覆盖 | 说明 |
|---|---|---|
| Pending Tool Calls | ✅ | 通过扫描 context 中 ToolUseBlock/ToolResultBlock 推导，不依赖额外字段 |
| shutdownInterrupted | ✅ | 已移入 AgentState（见 shutdown 迁移文档） |
| curIter | ✅ | 已在 AgentState 中 |
| replyId | ✅ | 已在 AgentState 中 |
| summary | ✅ | 已在 AgentState 中 |

## 最终结论

v1 saveTo/loadFrom 持久化的 4 组数据中：
- 2 组（memory_messages, toolkit_activeGroups）已由 AgentState 覆盖
- 2 组（agent_meta, planNotebook_state）不需要迁移（前者是不可变配置，后者是被替代的功能）
- HITL 相关的所有运行时状态均已覆盖，不存在功能丢失

唯一需要修复的 gap 是 toolkit activeGroups 的双向同步，已完成实施。
