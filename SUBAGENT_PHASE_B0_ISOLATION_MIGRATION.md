# Subagent State 隔离修复（Phase B-0）迁移指南

> 适用版本：`agentscope-core` 2.0.0-SNAPSHOT（含 B-0 修复）
> 关联文档：[SUBAGENT_GAP_VS_OPENCODE.md](./SUBAGENT_GAP_VS_OPENCODE.md)、实施 plan `~/.claude/plans/deep-mixing-adleman.md`

## 这次解决的问题

Phase B-0 之前，子 ReActAgent 的 AgentState 持久化分桶维度**只用 `declarationName`**：
- `sessionKey` 默认 `SimpleSessionKey.of(declName)` — 任何调用方按同 name 命中同一 SessionKey
- 任何 Session 实现（Workspace / Redis / InMemory / 自定义）按 SessionKey 分桶 store

结果：

| 场景 | 是否隔离 |
|---|---|
| 同 user、同父 session、多次 spawn 同 agentId | ❌ 共享（其实是期望的"续聊"） |
| 同 user、不同父 session、同 process | ❌ 共享 → **父 session A 的子 agent state 串到父 session B** |
| 不同 user、同 process（共享 HarnessAgent） | ❌ 共享 → **跨用户数据泄漏** |

要做后续的 Phase B-1（agent_key 稳定 + miss-then-rebuild）会让这个隐患从"潜伏"变"用户期望的就是隔离失败"。所以必须先修。

## 修复要点

在 **`SessionKey` 这个抽象层**做隔离 — 与具体 Session 实现解耦。所有 Session 实现都按 SessionKey 自然分桶 store，所以修 sessionKey 派生算法就够，不需要触碰任何 Session 实现内部。

子 ReActAgent 的 sessionKey 派生格式：

```
{declarationName}[@{parentSessionId}][#{userId}]
```

- ISOLATED 模式 + parent rc 含 sid/uid → **多桶**（按父 session / user 分隔）
- SHARED 模式 → 仍是 `{declarationName}` 单桶（共享父 state 是 SHARED 的语义）
- rc 不带 sid/uid → 落到 `{declarationName}` 单桶（兼容单租户 demo）

## API 变更（破坏性）

### `SubagentFactory.create()` → `create(RuntimeContext parentRc)`

```java
// 旧（已删除）
Agent create();

// 新
Agent create(RuntimeContext parentRc);
```

所有 caller 在编译期暴露。生产代码内 `ReActAgentBuilderSupport.buildDeclaredFactory` / `buildGeneralPurposeFactory` 已同步改造。自定义 SubagentFactory 实现需要加 rc 参数（多数情况下可忽略它，行为同前）。

### `DefaultAgentManager.createAgentIfPresent(String)` → `createAgentIfPresent(String, RuntimeContext)`

```java
// 旧（已删除）
Optional<Agent> createAgentIfPresent(String agentId);
Agent createAgent(String agentId);

// 新
Optional<Agent> createAgentIfPresent(String agentId, RuntimeContext parentRc);
Agent createAgent(String agentId, RuntimeContext parentRc);
```

调用方传当前 RuntimeContext；若无 rc 可显式传 `RuntimeContext.empty()` — 等价 pre-B-0 行为（单桶）。

### `WorkspaceMode` javadoc 更新

加了 SessionKey 多桶机制说明；`WorkspaceMode.ISOLATED` 现在不仅控制 workspace 路径，也通过派生 sessionKey 触发 (parent-session, user) 分桶。

## 行为变更

- **同 user、同父 session、多次 spawn 同 agentId** → 仍共享（同 sessionKey），符合 Phase B-1 续聊期望
- **同 user、不同父 session 同 process** → **现在真隔离**：sessionKey 含 `@<parentSessionId>` 段
- **不同 user 同 process** → **现在真隔离**：sessionKey 含 `#<userId>` 段
- **跨节点同 (user, parent-session)** → 仍依赖 distributed Session backend（如 RedisSession）
- **SHARED 模式 subagent** → 行为不变（仍单桶，"share parent's tree" 是其语义）

## 不变之处

- `Session` 接口：分毫不动
- `WorkspaceSession` 内部 path 派生：分毫不动（B-0 让 sessionKey 变了，path 自然按新 sessionKey 分目录）
- `TaskRepository` / `TaskRecord`（Phase B-3 改的能力）：分毫不动
- `Phase A` 的 declaration schema：分毫不动
- `HarnessAgent.builder()` 顶层启动：分毫不动 — B-0 仅修子 agent 派生

## 兼容性表

| 项目 | 影响 |
|---|---|
| 自定义 `SubagentFactory` 实现 | ⚠️ 编译破，需加 rc 参数；不需要的可忽略它 |
| 自定义 `DefaultAgentManager` 调用方 | ⚠️ 编译破，需补 rc 参数 |
| pre-B-0 已落盘的子 agent state | ⚠️ 旧文件仍在原 path 下，但新 sessionKey 不再读它 → **相当于全量丢失**（subagent state 是过程性数据，可接受） |
| 单进程单租户 demo（rc 不带 sid/uid） | ✅ 行为完全等价 pre-B-0（单桶） |
| 多用户部署（rc 带 userId） | ✅ 自动跨用户隔离 |
| `agentscope-extensions` / `agentscope-examples` | ✅ 已扫描，未发现需要改的生产 caller；测试 stub 已修 |

## 测试

### 验证脚本

```bash
mvn -pl agentscope-core test -Dspotless.check.skip=true \
    -Dtest='SubagentSessionKeyTest,DefaultAgentManagerRuntimeContextTest,SubagentIsolationIntegrationTest'
```

预期：17 cases 全绿。

### Phase A + B-3 + B-0 联测

```bash
mvn -pl agentscope-core test -Dspotless.check.skip=true \
    -Dtest='SubagentSessionKeyTest,DefaultAgentManagerRuntimeContextTest,SubagentIsolationIntegrationTest,SubagentDeliveryTest,WorkspaceTaskRepositoryDeliveryTest,SubagentDeclarationPhaseATest,SubagentModeHiddenTest,SubagentSpecGeneratorTest,AgentGenerateToolTest,HarnessAgentTest,HarnessAgentModelStringTest,HarnessAgentSubagentStreamTest,HarnessAgentDynamicHookBuilderTest'
```

## 下一步：Phase B-1

B-0 隔离修复上线后，Phase B-1 可以放心做：

- `agent_key` 稳定派生（agentId + label）取代 random UUID
- `agent_send` 在内存 miss 时按 stable key 重建活体 Agent
- `task_id` 作为 send 别名入口

B-0 之后，B-1 显式化的 "同 (agentId, label) → 同子 agent 续聊" 语义就有了**正确的隔离边界**，不会引入跨租户数据泄漏。

## 已知不解决的问题

- `WorkspaceSession.resolveContextDir` 内部仍用 `RuntimeContext.empty()` 解析 namespace（[WorkspaceSession.java:71](file:///Users/ken/agentscope-2/agentscope-java/agentscope-core/src/main/java/io/agentscope/harness/agent/session/WorkspaceSession.java#L71)）。docstring 自承"single-tenant default"是 pre-existing 设计选择。多租户场景仍应配置 RedisSession 等 distributed backend；本期 sessionKey 派生天然支持任何 backend。
- 自定义 factory 注册路径 `Function<String, Agent>`（`SubagentFactoryEntry`）签名只接 name，桥接到 B-0 时 rc 被忽略 — 用户若需要自定义 factory 也能 (user, parent-session) 隔离，应直接写 B-0 风格的 `SubagentFactory` lambda 注册到 `SubagentEntry`。
