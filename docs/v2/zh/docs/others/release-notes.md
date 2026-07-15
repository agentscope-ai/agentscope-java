---
title: "Release Notes"
description: "AgentScope Java 各版本变更记录"
---

本页记录 AgentScope Java 2.0 各版本的具体变更。从 1.x 升级的整体迁移指南请见 [V1 迁移指南](../change-log.md)。

---

## 2.0.0 (GA)

> 发布日期：2026-07-10

AgentScope Java 2.0.0 正式发布（General Availability）。这是从 1.x 到 2.0 的首个正式版本，标志着 AgentScope Java 从"透明开发"迈向"系统工程"的里程碑。

**快速链接：** [快速开始](../quickstart.md) | [V1 迁移指南](../change-log.md) | [上线指南](going-to-production.md)

### 2.0 版本核心设计概要

AgentScope Java 2.0 围绕"让智能体稳定完成任务"这一目标进行了系统性升级，核心设计如下：

**双层 Agent 架构**

- **ReActAgent**：无状态的推理核心，提供"推理 → 工具调用 → 回复"的 ReAct 循环。2.0 中 Agent 实例完全无状态，所有 per-call 可变状态通过 Reactor Context 透传，同一实例可安全并发服务多个 `(userId, sessionId)` 组合
- **HarnessAgent**：在 ReActAgent 之上通过 Middleware 与 Toolkit 两个扩展通道，叠加工作区、记忆、沙箱、子 agent、技能与计划模式等工程化基础设施——核心推理循环原样保留，只叠加不替换

**消息与事件流**

统一的 ContentBlock 消息模型（TextBlock / DataBlock / ToolUseBlock / ToolResultBlock / HintBlock 等），配合 `streamEvents()` 返回的 28 种类型化 AgentEvent，让 Agent 的执行过程可展示、可交互、可干预。前端 UI 可实时跟随文本增量、工具调用、用户确认等全生命周期事件

**权限系统**

全新的 PermissionEngine 为工具调用建立"允许 / 用户审批 / 拒绝"三态决策机制。根据静态规则、工具类型和输入内容综合判断，敏感操作自动进入 HITL 审批流程

**Middleware 扩展机制**

五阶段洋葱 + 管道混合模型（`onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt`），在保持核心框架稳定的同时，为日志追踪、安全检查、业务策略、上下文注入等提供灵活的扩展点

**上下文工程**

结构化压缩保留任务目标、当前状态、关键发现与下一步计划；超大工具结果自动落盘，上下文仅保留占位符；文件读写内置缓存并强制"先读后改"策略

**Workspace 执行环境抽象**

将"Agent 做什么"与"在哪里执行"解耦。本地文件系统、Docker 容器、Kubernetes、E2B 云沙箱等执行后端统一到同一套接口。内置预热池机制，适配 RL rollout 等并行场景

**模型容错**

统一的 Credential + ModelRegistry 抽象，覆盖 Qwen / OpenAI / Anthropic / Gemini / DeepSeek / Ollama 等主流模型。可配置最大重试与备用模型，主模型不可用时自动切换

**企业级分布式部署**

`DistributedBackend` 一键配置（Redis / OSS / MySQL / PostgreSQL / COS），`AgentStateStore` 按 `(userId, sessionId)` 自动分桶持久化。Session 跨副本恢复、沙箱状态快照、子 agent 跨副本路由

**协议互通**

内置 A2A（Agent-to-Agent）与 MCP（Model Context Protocol）协议支持，以及 AG-UI 协议适配，覆盖智能体间通信与前端展示的标准化需求

**多智能体编排**

声明式子 agent 规格定义（YAML / Markdown），运行时按需 `agent_spawn` / `agent_send`，支持同步阻塞与后台委派两种模式。子 agent 事件流可实时转发到父 agent 的 `streamEvents()`

**技能系统**

四层 Skill 合成（Classpath / FileSystem / Nacos / Marketplace）+ SkillFilter 细粒度过滤 + 自学习闭环（propose → curate → promote）

---

### 自 RC5 以来的变更

以下为 2.0.0-RC5（2026-07-07）至 GA 版本之间的增量变更。

#### 新增

- 当 HITL 拒绝所有工具调用时触发 `AllToolsDeniedEvent` hook，方便应用层监听和处理全拒绝场景 ([#2083](https://github.com/agentscope-ai/agentscope-java/pull/2083))
- `wait_async_results` 增加防护机制，防止反复长时间阻塞等待 ([#2093](https://github.com/agentscope-ai/agentscope-java/pull/2093))
- 新增 `PostgresDistributedStore`，支持 PostgreSQL 作为 HarnessAgent 的分布式后端 ([#2054](https://github.com/agentscope-ai/agentscope-java/pull/2054))
- Spring Boot Starter 新增 OpenAI、DashScope、Anthropic 模型的 builder customizer，简化自动配置 ([#2045](https://github.com/agentscope-ai/agentscope-java/pull/2045))

#### 修复

**核心 / Agent**

- `seedSystemMsg` 改为 reactive 实现，避免在 NIO 线程上调用 `block()` ([#2086](https://github.com/agentscope-ai/agentscope-java/pull/2086))
- PERMISSION_ASKING 状态的结果消息中正确包含 ASKING 状态的 ToolUseBlock ([#2082](https://github.com/agentscope-ai/agentscope-java/pull/2082))
- 通过 `activateOnSkill` 字段正确激活 SkillToolGroup ([#2057](https://github.com/agentscope-ai/agentscope-java/pull/2057))
- 用户中断时保存 agent 状态，防止会话丢失 ([#1970](https://github.com/agentscope-ai/agentscope-java/pull/1970))

**模型提供商**

- Anthropic：将并行 tool calls 拆分为交替排列的消息，符合 API 要求 ([#2090](https://github.com/agentscope-ai/agentscope-java/pull/2090))
- OpenAI：`nativeStructuredOutput` 改为可配置 ([#2069](https://github.com/agentscope-ai/agentscope-java/pull/2069))

**Harness / 工具 / 沙箱**

- 外部工具执行现在正确产生 suspended 结果 ([#2071](https://github.com/agentscope-ai/agentscope-java/pull/2071))
- Plan Mode 下允许 SkillLoadTool，通过将 `isReadOnly` 提升到 AgentTool 接口实现 ([#2067](https://github.com/agentscope-ai/agentscope-java/pull/2067))
- 中断孤儿子 agent：当 AgentSpawnTool 的父订阅取消时正确中断子 agent ([#2064](https://github.com/agentscope-ai/agentscope-java/pull/2064))
- MemoryFlushMiddleware 移除不必要的 ReActAgent 类型限制 ([#2078](https://github.com/agentscope-ai/agentscope-java/pull/2078))
- ROOTED 模式下将以 `/` 开头的路径解析为相对于 workspace ([#2049](https://github.com/agentscope-ai/agentscope-java/pull/2049))
- workspace projection 前预先部署 marketplace 技能 ([#2059](https://github.com/agentscope-ai/agentscope-java/pull/2059))
- Kubernetes `hydrateWithArchive` 中 null exit code 视为成功 ([#1915](https://github.com/agentscope-ai/agentscope-java/pull/1915))
- 恢复持久化状态时使用更新后的 WorkspaceSpec ([#1928](https://github.com/agentscope-ai/agentscope-java/pull/1928))
- AgentRun MCP 响应支持嵌套 JSON 和 banner 前缀 ([#1930](https://github.com/agentscope-ai/agentscope-java/pull/1930))
- Docker workspaceRoot 使用解析后的 workingDir ([#2033](https://github.com/agentscope-ai/agentscope-java/pull/2033))

**Channel**

- OutboundAddress 中包含 PeerKind，修复群组消息路由 ([#2060](https://github.com/agentscope-ai/agentscope-java/pull/2060))

**A2A**

- 合并流式文本 chunk，避免碎片化 ([#2058](https://github.com/agentscope-ai/agentscope-java/pull/2058))

---

## 2.0.0-RC5

> 发布日期：2026-07-07

### 重大变更

- **模型提供商模块化** —— OpenAI、Gemini、Anthropic、DashScope、Ollama 模型提供商从 `agentscope-core` 拆分为独立的 `agentscope-extensions-model-*` 扩展模块。应用需添加对应扩展依赖 ([#1890](https://github.com/agentscope-ai/agentscope-java/pull/1890), [#1916](https://github.com/agentscope-ai/agentscope-java/pull/1916), [#1947](https://github.com/agentscope-ai/agentscope-java/pull/1947), [#1972](https://github.com/agentscope-ai/agentscope-java/pull/1972))

### 新增

- 所有模型提供商（OpenAI、DashScope、Gemini、Anthropic）统一支持 `DataBlock` 多模态内容，覆盖单 agent、多 agent 和工具结果路径 ([#1933](https://github.com/agentscope-ai/agentscope-java/pull/1933))
- 原生结构化输出（Structured Output）与工具调用协同工作 —— 支持模型原生 JSON Schema 约束 ([#1904](https://github.com/agentscope-ai/agentscope-java/pull/1904))
- DashScope 模型支持原生结构化输出 ([#1935](https://github.com/agentscope-ai/agentscope-java/pull/1935))
- `McpClientBuilder` 新增 `httpRequestCustomizer`，支持动态 token 注入（如 OAuth 刷新）([#1992](https://github.com/agentscope-ai/agentscope-java/pull/1992))
- `AguiEvent` 对齐 AG-UI 协议规范，补全缺失事件类型 ([#1862](https://github.com/agentscope-ai/agentscope-java/pull/1862))
- 子 agent 可选技能白名单过滤 ([#1873](https://github.com/agentscope-ai/agentscope-java/pull/1873))
- `NacosSkillRepository` 支持 `knownSkillNames` ([#1853](https://github.com/agentscope-ai/agentscope-java/pull/1853))
- 新增 `CosAgentStateStore`、`CosBaseStore`、`CosDistributedStore`，支持腾讯云 COS 状态持久化 ([#1857](https://github.com/agentscope-ai/agentscope-java/pull/1857))
- `ChatUsage` 暴露 cached prompt tokens ([#1868](https://github.com/agentscope-ai/agentscope-java/pull/1868))

### 修复

**核心 / Agent**

- 用户中断恢复时持久化 agent 状态 ([#2008](https://github.com/agentscope-ai/agentscope-java/pull/2008))
- 正确连线 fallback model 到 `ReActAgent` ([#1851](https://github.com/agentscope-ai/agentscope-java/pull/1851))
- 修复 `ReActAgent` 流式事件 block end 顺序 ([#1829](https://github.com/agentscope-ai/agentscope-java/pull/1829))
- 在加入 agent 上下文前更新 `ToolResultBlock` 状态 ([#1886](https://github.com/agentscope-ai/agentscope-java/pull/1886))
- 复用 classpath skill JAR 文件系统，避免资源泄漏 ([#1981](https://github.com/agentscope-ai/agentscope-java/pull/1981))
- 修复 `serializeOnKey` 在 `Flux.create` 回调中的 gate 泄漏 ([#1796](https://github.com/agentscope-ai/agentscope-java/pull/1796))

**模型提供商**

- 将 `thinkingBudget` 映射到 OpenAI 兼容 API 请求 ([#2028](https://github.com/agentscope-ai/agentscope-java/pull/2028))
- 修复 Anthropic 流式 thinking event 处理 ([#1943](https://github.com/agentscope-ai/agentscope-java/pull/1943))
- 保留 `OllamaOptions` `fromOptions`/`toBuilder` 中的 `executionConfig` ([#2011](https://github.com/agentscope-ai/agentscope-java/pull/2011))
- DashScope thinking 模式下降级强制 tool choice ([#1882](https://github.com/agentscope-ai/agentscope-java/pull/1882))

**Harness / 沙箱**

- 修复远程快照状态反序列化 —— Jackson 往返后重新注入 `RemoteSnapshotClient` ([#2013](https://github.com/agentscope-ai/agentscope-java/pull/2013))
- 修复 THROTTLED 记忆保存模式在每次请求新建实例时失效 ([#1788](https://github.com/agentscope-ai/agentscope-java/pull/1788))
- 通过 wakeup dispatch 传播 `userId` ([#2001](https://github.com/agentscope-ai/agentscope-java/pull/2001))
- message bus 心跳改用 `boundedElastic` 调度器 ([#1974](https://github.com/agentscope-ai/agentscope-java/pull/1974))
- 避免 `fromAgent` 重复注册 `GracefulShutdownMiddleware` ([#1952](https://github.com/agentscope-ai/agentscope-java/pull/1952))
- 转义 `ShellPathPolicy` 返回路径中的空格 ([#2031](https://github.com/agentscope-ai/agentscope-java/pull/2031))
- YAML 解析失败时回退到简单键值提取 ([#2027](https://github.com/agentscope-ai/agentscope-java/pull/2027))
- `ls` 命令报告沙箱文件大小 ([#1838](https://github.com/agentscope-ai/agentscope-java/pull/1838))
- 归一化 Windows `list_files` 路径 ([#1892](https://github.com/agentscope-ai/agentscope-java/pull/1892))
- `LocalFilesystem.edit()` 将 `\r\n` 归一化为 `\n` ([#2020](https://github.com/agentscope-ai/agentscope-java/pull/2020))
- `CompositeFilesystem` 将 `"."` 视为根路径 ([#1830](https://github.com/agentscope-ai/agentscope-java/pull/1830))
- 校验 `working_directory` 防止命名空间逃逸 ([#1834](https://github.com/agentscope-ai/agentscope-java/pull/1834))
- 未配置分布式 `AgentStateStore` 时回退到 `LocalFilesystemSpec` ([#1841](https://github.com/agentscope-ai/agentscope-java/pull/1841))
- 修复 Kubernetes `hydrateWithArchive` WebSocket 竞态导致 `exit=null` ([#1903](https://github.com/agentscope-ai/agentscope-java/pull/1903))
- 容忍 wrapped sandbox base64 下载 ([#1866](https://github.com/agentscope-ai/agentscope-java/pull/1866))
- 移除 `AgentRun` sandbox API 版本前缀 ([#1891](https://github.com/agentscope-ai/agentscope-java/pull/1891))
- E2B sandbox 新增 connect JSON codec 支持 ([#1844](https://github.com/agentscope-ai/agentscope-java/pull/1844))

**链路追踪 / 可观测性**

- 从 Reactor `ContextView` 读取父 OTel Context，修复 `OtelTracingMiddleware` 孤立 span ([#1940](https://github.com/agentscope-ai/agentscope-java/pull/1940))
- 修复 `OtelTracingMiddleware` 子 span 看不到正确父 span ([#1909](https://github.com/agentscope-ai/agentscope-java/pull/1909))
- 将 Reactor context 传播到 chunk event hooks ([#1923](https://github.com/agentscope-ai/agentscope-java/pull/1923))

**子 Agent**

- 将父 `RuntimeContext` 传播到子 agent ([#1833](https://github.com/agentscope-ai/agentscope-java/pull/1833))
- 将父 middleware 传播到子 agent ([#1843](https://github.com/agentscope-ai/agentscope-java/pull/1843))

**A2A**

- 处理流式背压 ([#1734](https://github.com/agentscope-ai/agentscope-java/pull/1734))
- A2A 转换保留 AgentScope 消息角色 ([#1995](https://github.com/agentscope-ai/agentscope-java/pull/1995))

**AG-UI**

- 传播 run input 和 frontend tools ([#1895](https://github.com/agentscope-ai/agentscope-java/pull/1895))

**其他**

- Middleware `doFlush` 包装 `Mono.defer` 防止提前求值 ([#1880](https://github.com/agentscope-ai/agentscope-java/pull/1880))
- Nacos auto-configurations 改为 opt-in（`matchIfMissing=false`）并修复 A2A server-addr 覆盖 ([#1709](https://github.com/agentscope-ai/agentscope-java/pull/1709))
- DataAgent 补充 `ObjectMapper` bean ([#1993](https://github.com/agentscope-ai/agentscope-java/pull/1993))

### 文档

- 明确流式事件 `blockId` 语义 ([#2016](https://github.com/agentscope-ai/agentscope-java/pull/2016))
- 改进模型提供商文档 ([#1986](https://github.com/agentscope-ai/agentscope-java/pull/1986))
- 移除无效的 `ChatResponse.isLast` 引用 ([#1921](https://github.com/agentscope-ai/agentscope-java/pull/1921))
- 修复多副本 Redis 示例 —— 声明 jedis 依赖并补充 `stateStore` ([#1869](https://github.com/agentscope-ai/agentscope-java/pull/1869))
- 修复 `MemoryCompactionExample` 展示 memory 文件并触发 compaction ([#1978](https://github.com/agentscope-ai/agentscope-java/pull/1978))

---

## 2.0.0-RC4

> 发布日期：2026-06-18

### 新增

- Agent harness 支持异步工具执行和通知，包括 message bus、async tool registry 和 scheduled wakeup dispatching ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- Agent 调用新增 String/Message 便捷重载；所有 formatter 支持 `HintBlock` ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- 持久化 spawn registry 支持子 agent 跨副本路由和 session 恢复 ([#1817](https://github.com/agentscope-ai/agentscope-java/pull/1817))
- `DynamicSkillMiddleware` 实现 `ToolkitAware`，支持动态接收解析后的 toolkit ([#1828](https://github.com/agentscope-ai/agentscope-java/pull/1828))
- Kubernetes sandbox 支持向 Pod 注入环境变量 ([#1789](https://github.com/agentscope-ai/agentscope-java/pull/1789))

### 修复

- 修复 Kubernetes 文件上传中的 SIGKILL 竞态条件，使用两阶段 archive 策略 ([#1826](https://github.com/agentscope-ai/agentscope-java/pull/1826))
- 修复超时子 agent 未在重试时中断导致的资源泄漏 ([#1784](https://github.com/agentscope-ai/agentscope-java/pull/1784))
- 修复复制 `RuntimeContext` 时丢失 typed attributes ([#1813](https://github.com/agentscope-ai/agentscope-java/pull/1813))
- 修复 MySQL utf8mb4 字符集下 `JdbcStore` 表初始化失败 ([#1781](https://github.com/agentscope-ai/agentscope-java/pull/1781))
- Session JSONL offload 改为幂等，防止重复写入 ([#1774](https://github.com/agentscope-ai/agentscope-java/pull/1774))
- 修复 `TelemetryTracer` 中 OpenTelemetry context 传播 ([#1799](https://github.com/agentscope-ai/agentscope-java/pull/1799))
- 修复 `OllamaChatModel` 获取 tool choice 时 options 为 null 导致 NPE ([#1803](https://github.com/agentscope-ai/agentscope-java/pull/1803))
- 补充 `LocalSandboxSnapshot` 缺失的 Jackson 注解 ([#1825](https://github.com/agentscope-ai/agentscope-java/pull/1825))
- 修复 sandbox glob 不支持 `**/` 递归模式 ([#1684](https://github.com/agentscope-ai/agentscope-java/pull/1684))
- 修复 `SkillFilter` 使用 composite ID 而非 skill name 匹配 ([#1771](https://github.com/agentscope-ai/agentscope-java/pull/1771))
- 允许 `MultiModalTool` 使用自定义默认视觉模型 ([#1701](https://github.com/agentscope-ai/agentscope-java/pull/1701))

### 文档

- 修复 middleware 文档中错误的 hook 签名 ([#1835](https://github.com/agentscope-ai/agentscope-java/pull/1835))
- 修复文档示例中引用不存在的 `.sandboxContext()` ([#1792](https://github.com/agentscope-ai/agentscope-java/pull/1792))
- 修复 v2 文档中 `getToolName()` → `getToolCallName()` ([#1760](https://github.com/agentscope-ai/agentscope-java/pull/1760))
- 文档站点新增 AI 上下文菜单

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
