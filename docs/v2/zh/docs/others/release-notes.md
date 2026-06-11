---
title: "Release Notes"
description: "AgentScope Java 各版本变更记录"
---

本页记录 AgentScope Java 2.0 各版本的具体变更。从 1.x 升级的整体迁移指南请见 [V1 迁移指南](../change-log.md)。

---

## 2.0.0-RC3

> 发布日期：2026-06-11

### 新增

- **`AgentResultEvent`** —— 新增事件类型，在 agent 调用完成后、`AgentEndEvent` 之前发出，携带最终 `Msg` 结果。`streamEvents()` 的消费方可直接从事件流中获取最终结果，无需额外订阅 `Mono<Msg>` 返回值
- **`CustomEvent`** —— 通用可扩展事件，用于中间件向前端推送应用级通知（状态变更、团队变更等），无需为每种业务场景新增 `AgentEventType`。内置 well-known name：`state_updated`、`team_updated`
- **`HintBlockEvent`** —— 一次性 hint block 事件，用于传递团队消息、后台工具结果、用户中断等完整内容，区别于需要流式拼接的 text/thinking block
- **`WorkspacePathNormalizer`** —— 文件路径归一化工具，将绝对路径转换为 workspace 相对路径。根据当前文件系统模式（本地 / 沙箱）注册前缀，避免跨模式误匹配
- **工具事件携带 `toolCallName`** —— `ToolCallDeltaEvent`、`ToolCallEndEvent`、`ToolResultDataDeltaEvent`、`ToolResultEndEvent`、`ToolResultTextDeltaEvent` 均新增 `toolCallName` 字段，消费端不再需要缓存 start 事件的名称映射

### 变更

- **`call()` 与 `streamEvents()` 共享执行核心** —— 新增内部 `buildAgentStream` 方法作为 `call()` 和 `streamEvents()` 的统一实现，确保 `onAgent` middleware 链在所有调用路径上一致触发。`call()` 从事件流中提取 `AgentResultEvent` 获得结果，移除了旧的独立 `agentImpl` 逻辑
- **分布式部署下 session 状态始终从 store 加载** —— `activateSlotForContext` 在配置了 `AgentStateStore` 时，每次调用开头从 store 重新加载状态和权限引擎，避免分布式环境中同一 sessionId 漂移到不同机器时读到过期本地缓存
- **`ToolResultEvictionMiddleware` 时机修正** —— 从 `onActing`（此时状态尚未写入，导致空操作）迁移到 `onReasoning`，确保工具结果已持久化后再执行淘汰
- **`LocalFilesystem` 路径解析简化** —— 重构路径解析逻辑，减少冗余代码

### 修复

- 修复 `RuntimeContext` 在测试中未设置 `userId` 导致用户隔离不准确的问题

---

## 2.0.0-RC2

> 发布日期：2026-06-09

### 新增

- **`projectWritable` 模式**（`LocalFilesystemSpec`）—— 开启后，agent 的文件写入按路径自动路由：工作区元数据（`MEMORY.md`、`agents/`、`skills/` 等）写到 workspace，其余文件（代码、配置等）直接落到项目目录。适合代码生成类 agent。详见 [文件系统 · 项目可写模式](../harness/filesystem.md#项目可写模式projectwritable)
- **Permission 系统运行时切换** —— 新增 `HarnessAgent.setPermissionMode()` / `getPermissionMode()`，支持在运行时按 session 动态调整权限模式
- **子 agent 事件流转发** —— `streamEvents()` 现在实时转发子 agent 的中间事件（`TextBlockDelta`、`ToolCallStart` 等），每个事件携带 `source` 路径标识来源
- **`AgentEvent.source` 来源标识** —— 所有 `AgentEvent` 新增 `source` 字段，在同一事件流中区分 main agent 事件（`source = null`）和 sub agent 事件（`source = "main/researcher"` 等路径格式），消费端无需额外状态即可分流处理
- **Compaction / Memory 定制 prompt 和 model** —— `CompactionConfig` 和 `MemoryConfig` 新增 `.model()` 和 `.prompt()` builder 方法，允许为上下文压缩和记忆提取指定独立的轻量模型和自定义 prompt，不再强制使用 agent 主模型
- **Qwen 3.7 模型支持** —— `ModelRegistry` 新增 `dashscope:qwen3.7-plus` 等 Qwen 3.7 系列模型的解析支持
- **直接与子 agent 对话** —— 支持通过 `agent_send` 直接向已声明的子 agent 发送消息并获取响应，无需经过父 agent 的推理循环
- **Channel 模块** —— 新增 `agentscope-extensions-channel` 系列模块，实现 IM 平台接入（钉钉、飞书、企业微信、GitHub、GitLab），内置 ChatUI 提供开箱即用的对话界面
- **`DistributedBackend` 统一接口** —— 新增 `DistributedBackend` 抽象，收敛分布式部署所需的所有存储组件（`AgentStateStore`、`BaseStore`、`SandboxSnapshotSpec`）为一键配置。内置 `RedisDistributedBackend`、`OssDistributedBackend`、`MysqlDistributedBackend` 等实现，通过 `HarnessAgent.builder().distributedBackend(backend)` 一行完成分布式后端接入，不再需要分别配置 stateStore、baseStore、snapshotSpec

### 变更

- **Agent 完全无状态改造** —— `ReActAgent` 不再持有任何可变的 per-session 状态，所有可变状态封装在内部 `CallExecution` 中通过 Reactor Context 透传。同一 agent 实例可安全并发服务多个 `(userId, sessionId)` 组合
- **Session 接口全面改为 `AgentStateStore`** —— 移除 `SessionManager`、`StatePersistence` 等旧接口，统一使用 `AgentStateStore`（内置 `InMemoryAgentStateStore`、`JsonFileAgentStateStore`、`RedisAgentStateStore`、`MysqlAgentStateStore`），按 `(userId, sessionId)` 二元组自动分桶持久化
- **`BaseStore` 接口包名迁移** —— `BaseStore` 及相关接口从旧包迁移到新包路径，使用旧 import 的代码需要更新
- **Extension 模块坐标整合** —— 部分扩展包 Maven 坐标调整，按职能重新归类。例如 `agentscope-extensions-session-redis` 合并为 `agentscope-extensions-redis`（同时包含 `RedisAgentStateStore`、`RedisStore`、`RedisSnapshotSpec` 等）。使用旧坐标的 pom 需要更新 `<artifactId>`
- **Sandbox 实现从 harness 内核拆出** —— Docker、Kubernetes、E2B、Daytona、AgentRun 等沙箱后端的实现从 `agentscope-harness` 内核移至独立扩展包（`agentscope-extensions-sandbox-*`）。harness 内核仅保留 `SandboxFilesystemSpec` 等抽象接口，不再传递具体实现依赖。如需使用沙箱需额外引入对应扩展包，例如 Docker 沙箱需添加 `agentscope-extensions-sandbox-docker`
- **Plan Mode 优化增强** —— 改进计划文件持久化与恢复机制，优化 `plan_enter` / `plan_write` / `plan_exit` 工具链的交互体验，增强 HITL 审批流程的稳定性
- **Skill 自进化增强** —— 优化技能提案（`ProposeSkillTool`）、审批（`SkillCurator`）与晋升（`SkillPromoter`）闭环，改进技能匹配精度与跨会话复用效果
- `DashScopeHttpClient` 请求超时与重试策略调整
- `ModelRegistry` 模型解析逻辑优化
- `AgentState` 序列化格式更新

### 修复

- 修复 `PermissionContextState` 在跨 session 恢复时的状态丢失问题
- 修复 `agentscope-all` 中缺失 4 个 sandbox 扩展模块（`sandbox-kubernetes`、`sandbox-agentrun`、`sandbox-daytona`、`sandbox-e2b`）的问题

---

## 2.0.0-RC1

> 发布日期：2025-05-28

首个 2.0 Release Candidate。包含从 1.x 的全部架构升级：

- Harness 工程化（workspace、记忆、技能、子 agent、Plan Mode、上下文压缩）
- 企业级分布式部署（多租户隔离、沙箱执行、权限管控、会话恢复）
- 底层框架重构（事件流、消息模型、Middleware、HITL）

完整的 1.x → 2.0 变更列表请见 [V1 迁移指南](../change-log.md)。
