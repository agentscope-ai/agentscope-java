# v2 迁移分析：Shutdown 子系统从 Session 迁移到 AgentState

> 完成时间：2026-05-27

## 背景

v1 的 graceful shutdown 机制完全依赖 Session 组件：
- `ShutdownSessionBinding(session, sessionKey)` 绑定 agent 与 Session
- shutdown 时通过 `session.save()` 持久化中断状态
- 恢复时通过 `session.get("shutdown_interrupted")` 读取

v2 需要将 shutdown 持久化从 Session 解耦，改为通过 `AgentState` 承载。

## 迁移方案

### 新增：ShutdownStateSaver 函数式接口

```java
@FunctionalInterface
public interface ShutdownStateSaver {
    void save(AgentState state);
}
```

替代 `ShutdownSessionBinding`，让调用方自行决定存储后端（Session、文件、数据库等）。

### GracefulShutdownManager 变更

| 变更点 | v1 | v2 |
|---|---|---|
| 绑定存储 | `ConcurrentHashMap<String, ShutdownSessionBinding>` | `ConcurrentHashMap<String, ShutdownStateSaver>` |
| 绑定方法 | `bindSession(Agent, Session, SessionKey)` | `bindStateSaver(Agent, ShutdownStateSaver)` |
| 中断检测 | `session.get("shutdown_interrupted")` | `agent.getAgentState().isShutdownInterrupted()` |
| 状态保存 | `session.save(key, "shutdown_interrupted", state)` | `saver.save(agentState)` |

### ActiveRequestContext 变更

| 变更点 | v1 | v2 |
|---|---|---|
| 构造参数 | `Session session, SessionKey sessionKey` | `ShutdownStateSaver saver` |
| 保存方法 | `saveToSession()` | `saveState()` |
| 保存逻辑 | 写 `ShutdownInterruptedState` 到 Session | 设置 `state.setShutdownInterrupted(true)` 后调 `saver.save(state)` |

### 向后兼容

- `bindSession(Agent, Session, SessionKey)` 保留为 `@Deprecated` 方法，内部转换为 lambda：
  ```java
  bindStateSaver(agent, s -> session.save(sessionKey, "agent_state", s));
  ```
- `ShutdownSessionBinding` 标记 `@Deprecated(since = "2.0.0", forRemoval = true)`
- `ShutdownInterruptedState` record 已删除（不再需要独立的中断状态类型）

### shutdownInterrupted 移入 AgentState

- `AgentState` 新增 `shutdownInterrupted` boolean 字段
- Jackson 序列化：`@JsonProperty("shutdown_interrupted")`
- Builder 支持：`builder.shutdownInterrupted(true)`
- `checkAndClearShutdownInterrupted()` 从 `AgentBase.getAgentState()` 读取

### 关键时序

1. **Agent 注册请求时** — `registerRequest()` 从 `stateSavers` map 获取已绑定的 saver
2. **Shutdown 超时时** — `enforceTimeoutAndInterrupt()` 对每个 active request 调 `ctx.saveState()` + `ctx.interruptForShutdown()`
3. **Agent 观察到 SYSTEM 中断时** — `saveOnInterruptObserved()` 再次保存（可能有新状态）
4. **恢复时** — `checkAndClearShutdownInterrupted()` 在 PreCallEvent 检测并清除标志

### 影响的文件

- `ShutdownStateSaver.java` — 新文件
- `GracefulShutdownManager.java` — 核心重构
- `ActiveRequestContext.java` — 构造函数 + saveState 重构
- `AgentState.java` — 新增 shutdownInterrupted 字段
- `AgentBase.java` — 新增 getAgentState() 方法
- `ShutdownSessionBinding.java` — 标记 deprecated
- `ShutdownInterruptedState.java` — 已删除
- `GracefulShutdownTest.java` — 完整重写

### 验证

- GracefulShutdownTest 58 个测试全部通过
- 新增测试覆盖：stateSaverAndInterruptedCheck, bindSessionCompat, checkInterruptedNoState, saveStatePersists, saveStateNoSaver, saveStateNoAgentState
