# v2 迁移：框架内部持久化流程全面切换到 AgentState + StorageBase

## 背景

v2 引入了 `AgentState` 作为唯一状态容器，`StorageBase` 提供 `saveAgentState/loadAgentState` 的 Mono API。本次迁移将所有内部框架持久化流程全面切换到 `AgentState` + `StorageBase`，**彻底移除 Session fallback 逻辑**。用户必须配置 `StorageBase`，否则报错。

## 设计原则

- 框架内部 **不保留** `session.saveTo` / `session.loadFrom` 的兼容逻辑
- 用户必须配置 `StorageBase`，未配置时报 `IllegalStateException`
- `Session` / `StateModule.saveTo/loadFrom` 仅作为 deprecated 的用户侧 API 保留在代码中
- 非 `ReActAgent` 的 agent 无状态可持久化，静默跳过

## 变更清单

### 1. ReActAgent — StorageBase 字段与 save/load 方法

- 新增 `private StorageBase storage` 字段（通过 Builder `.storage()` 或 `injectHarnessRuntime` 设置）
- 新增 `public StorageBase getStorage()` getter
- 新增 `public Mono<Void> saveStateToStorage()` — 用内部 storage 保存
- 新增 `Mono<Void> loadStateFromStorage(String sessionId)` — 用内部 storage 加载（package-private）
- 新增 `public Mono<Void> loadStateFromStorage(StorageBase, String)` — 接受外部 storage（供 SubAgentTool 使用）
- 新增 `public Mono<Void> saveStateToStorage(StorageBase, String)` — 接受外部 storage（供 SubAgentTool 使用）
- 新增 `private void applyLoadedState(AgentState loaded)` — 合并加载的状态到当前 state

### 2. call(msgs, RuntimeContext) — 仅 StorageBase 加载

orchestrated 模式下：
- 有 StorageBase → `loadStateFromStorage(sid).block()`
- 无 StorageBase → 不加载（**已移除 Session fallback**）

### 3. StatePersistenceHook — 纯 StorageBase 实现

`StatePersistenceHook` 不再实现 `RuntimeContextAware`，不再引用 Session：
- agent 是 ReActAgent 且有 storage → `ra.saveStateToStorage()`
- agent 是 ReActAgent 无 storage → debug 日志跳过
- 非 ReActAgent → 跳过

原 `SessionPersistenceHook` 标记 `@Deprecated(since = "2.0.0", forRemoval = true)`。

### 4. SubAgentTool — 纯 StorageBase，无 Session 逻辑

`loadAgentState` / `saveAgentState` 重写：
- 非 ReActAgent → 静默跳过（无状态可持久化）
- ReActAgent 且无 StorageBase → 抛 `IllegalStateException`
- ReActAgent 且有 StorageBase → 调用 `ra.loadStateFromStorage/saveStateToStorage`

已移除 `Session` 和 `StateModule` 的 import。

### 5. Shutdown Saver 自动绑定

`injectHarnessRuntime` 中，当 `storage != null` 时，自动注册基于 StorageBase 的 `ShutdownStateSaver`：
```java
shutdownManager.bindStateSaver(this, agentState ->
    storage.saveAgentState(agentState.getSessionId(), getAgentId(), agentState).block());
```

## 错误行为

| 场景 | 行为 |
|---|---|
| SubAgentTool 的 ReActAgent 子 agent 未配置 StorageBase | `IllegalStateException` |
| StatePersistenceHook 处理无 storage 的 ReActAgent | debug 日志跳过 |
| orchestrated call 无 storage | 不加载状态（不报错，agent 以空白 state 运行） |

## 用户侧 API（deprecated，保留但不参与框架内部流程）

- `ReActAgent.saveTo(Session, SessionKey)` — deprecated
- `ReActAgent.loadFrom(Session, SessionKey)` — deprecated
- `ReActAgent.loadIfExists(Session, SessionKey)` — deprecated

## 验证结果

- 编译：通过
- 测试：4517 tests, 1 failure（`ToolDangerousPathConstantsTest` — 变更前已存在）
