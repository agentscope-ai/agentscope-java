---
title: Harness 架构
description: HarnessAgent 是什么、如何选择调用方式、组合业务能力并保存会话状态
en_link: /dev/en/docs/harness/architecture
---

`HarnessAgent` 将推理、工具与上下文管理，和工作区、长期记忆、技能、子 Agent、沙箱等能力组合在一起。先用 [Quick Start](/dev/zh/docs/quickstart) 跑通问答，再按业务需要增加能力。

`ReActAgent` 提供推理循环、工具、权限和会话上下文等基础 API；Harness 在这些能力上提供开箱即用的组合与默认配置。两者都支持 `call` 和 `streamEvents`。

推荐在应用启动时配置共享 `HarnessAgent.Builder`，每次直接调用前用 `builder.build()` 创建新实例，执行结束后关闭。会话通过稳定身份与同一日志后端延续；具体代码和共享实例的适用方式见[实例生命周期](/dev/zh/docs/building-blocks/agent#实例生命周期)。Durable adapter 工厂为受管调用创建原生 Agent，Runtime 统一管理持久任务调度。

## 核心工作原理

一次执行会读取当前会话状态，构建模型输入，按需调用工具，再保存结果。工作区、记忆和压缩等能力在这些步骤中生效；业务通过 builder、工具和 middleware 定制行为。

## 选择调用方式

- **获取回复或把 Agent 接入工作流**：使用 `call(input, ctx)`。
- **在当前请求内展示实时文本和工具进度**：使用 `streamEvents(input, ctx)`。
- **让任务在后台继续，支持排队、补充要求和恢复**：使用 [DurableRuntime](/dev/zh/docs/harness/durable)。

直接调用也支持多轮会话，并使用默认的 Thread 日志 和 checkpoint 保存状态。Durable 负责持久受理、Thread 队列和待办关联。Harness 经由 AgentScope adapter 提供同一套模型、工具和中间件能力，不再持有另一套任务调度器。

普通调用不创建持久 Turn。本地子 Agent 使用自己的 Run 和执行计数，通过父 Run/Step 关联来源；不继承父调用的控制对象或恢复位置。需要持久任务、后台接管与跨进程控制时，由 Durable 管理相应 Thread/Turn。

调用示例见[智能体](/dev/zh/docs/building-blocks/agent)，后台任务接入见 [Durable](/dev/zh/docs/harness/durable)。已有工作流调度器的应用可继续直接调用。

## 核心组件

按应用需要配置能力，或调用会话操作。

| 能力 | 解决什么问题 | 配置或操作 | 详细文档 |
|---|---|---|---|
| 工作区驱动的人格 | 人格 / 知识 / 子 agent / 技能 / MCP 白名单都以文件形式存在 | `.workspace(path)` | [工作区](/dev/zh/docs/harness/workspace) |
| 状态持久化与执行日志 | 完整事实、checkpoint、跨请求和跨节点恢复 | 默认日志模式；`.threadJournalStore(...)` 替换 | [会话日志](/dev/zh/docs/harness/session-log) |
| 持久任务 | 后台执行、排队、补充信息和恢复 | `DurableRuntime` | [Durable](/dev/zh/docs/harness/durable) |
| 双层长期记忆 | 长会话里有价值的事实自动沉淀到 `MEMORY.md` | 默认关闭，用 `.enableLongTermMemory()` 开启；`.memory(...)` 定制 prompt / 触发策略 | [记忆](/dev/zh/docs/harness/memory) |
| 对话压缩 | 上下文有界；模型真的溢出时强制重试 | `.compaction(...)` | [上下文管理](/dev/zh/docs/harness/context) |
| 大工具结果卸载 | 超 80K 字符的结果落盘 + 占位符 | `.toolResultEviction(...)` | [上下文管理](/dev/zh/docs/harness/context) |
| 工具重复失败 | 模型反复调用同一个失败工具时给一条短提醒 | 默认开启；`.disableRepeatedFailureGuard()` | 下文 |
| 子 agent 编排 | 委派给子 agent，支持同步或后台，自动反向通知 | `.subagent(...)` 或 `workspace/subagents/` | [子 Agent](/dev/zh/docs/harness/subagent) |
| 可插拔文件系统 | 本机 + shell / 共享存储 / 沙箱，不改代码切换 | `.filesystem(...)` | [文件系统](/dev/zh/docs/harness/filesystem) |
| 沙箱隔离 | 文件与命令隔离，跨调用恢复，多副本部署 | `.filesystem(new DockerFilesystemSpec()...)` | [沙箱](/dev/zh/docs/harness/sandbox) |
| 计划模式 | 只读思考阶段 + HITL 退出 | `.enablePlanMode()` | [计划模式](/dev/zh/docs/harness/plan-mode) |
| 技能装配 | 来自 Git / Nacos / MySQL / classpath / 工作区 | `.skillRepository(...)` | [技能](/dev/zh/docs/harness/skill) |
| MCP 集成与工具白名单 | 声明式 MCP server + 工具粒度允许 / 拒绝 | `workspace/tools.json` | [工作区](/dev/zh/docs/harness/workspace) |
| Channel 路由 | 会话管理、per-session 并发控制、多 agent 路由、流式事件 | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/dev/zh/docs/harness/channel) |

## 状态怎么流转

状态分三层，框架自动在层之间搬数据。

- **调用内状态** —— `AgentState`（对话上下文、权限规则、Plan Mode 状态、工具状态）加上 `RuntimeContext`（`sessionId`、`userId`、沙箱句柄、extra）。
- **跨调用状态** —— 默认从 Thread 日志 的 checkpoint 及后续可应用事实恢复；完整历史保留，JSONL 仅按需导出。快照模式 使用 AgentStateStore；文件、子任务及 sandbox 元数据仍由各自后端保存。
- **长期记忆** —— 跨 session 累积：`memory/YYYY-MM-DD.md` 只追加；后台节流任务把它周期合并到 `MEMORY.md`；`MEMORY.md` 每次 call 加载为参考上下文，不作为 System 指令。

三个值得记住的规律：

- 最终请求每轮重新构建，但工作区文件按 call 加载；修改 AGENTS.md 或 MEMORY.md 后下一次 call 生效，不需要重启。
- 压缩、记忆提炼、后台维护都被节流闸门管着，不会每轮都跑。
- `AgentState` 的持久化由 Core 执行生命周期管理：日志模式 写 checkpoint，快照模式写状态存储。Harness 配置默认日志后端。

## 工具重复失败

内置工具通过 `ToolResultBlock.metadata` 标注失败：

- `agentscope_error_kind` —— `ToolErrorKind` 值，如 `not_found`、`invalid_argument`、`permission_denied`、`timeout`。
- `agentscope_error_scope` —— 失败对象：工作区相对路径、host、thread id 等。
- `agentscope_retriable` —— 原样重试是否可能成功；缺省表示未知。

Core 也会分类工具查找、可用性和参数校验失败，scope 使用工具名。不存在与未获能力授权的工具共用 `not_found` 文本和元数据，不泄露工具存在性；未激活工具使用 `permission_denied`，畸形或不符合 schema 的参数使用 `invalid_argument`。这些控制面字段传递到 `ToolResultEndEvent`，不改变模型可见的错误文本。

自定义工具构造错误结果时可用 `ToolErrorMetadata.of(kind, scope)` 接入同一契约。

`HarnessAgent` 默认安装 `RepeatedFailureGuard`，在生产侧 `onAgentEvent` 通知这一单点观察工具事件，也包括执行前因畸形参数被拒绝的失败。观察不依赖 `ON_AGENT` 流消费者的 demand。父守卫过滤带子来源的转发事件；每个子 agent 自身的守卫在转发附加来源标签之前观察本地事件。最近 8 条已分类失败按工具、kind 和 scope 计数，出现 3 次时在下一次模型请求中附加短提醒（标记为可重试时 5 次）。成功只清除相同参数的调用对应的失败；参数不可用时按相同 scope 清除，不再清空同名工具的所有失败。`command_failed` 提醒要求先检查退出码及诊断输出，再修改命令。

提醒不会区分同名工具是不存在还是未获能力授权，也不会向模型输入增加工具目录。当真实结果报告 `permission_denied` 时，提醒要求模型停止当前操作，不得更换工具、身份或路径绕过拒绝。若要继续该操作，应通过应用的权限流程取得授权，而不是换一条路径实现同一效果。

另一个 8 条结果的窗口对相同参数和流式输出计算指纹，包括 `SUCCESS` / exit 0 和未分类错误。重复 3 次（标记为可重试时 5 次）会触发不暗示失败的中性提醒；结果变化时清除该调用的重复记录。Shell 结果带有 `agentscope_output_sha256` 元数据时，比较使用已捕获原始流的摘要，因此随机归档路径和展示提示不会掩盖重复输出。没有该摘要时，仍对结果做精确哈希。指纹只保留散列值，不保存原始参数或输出。两条检测路径每轮合计最多提醒 2 次，同一 signature 的第二次提醒须再出现 3 次。提醒不写入会话历史，新一轮或调用结束重置两个窗口。守卫不会自动重试、改写参数、中止运行，也不会从文本推断业务错误。用 `.disableRepeatedFailureGuard()` 关闭，或注册自己的 middleware 采用不同策略。

内置上下文约定还要求：副作用操作结果不确定时，先查当前状态再决定是否重试；精确引用前取回原文并逐字保留。这些是模型行为指引，不是运行时幂等保证或权限授权。

## 自己加 middleware 时要注意什么

要在不绕过 Harness 内置链路的前提下插入自定义行为：

- 用 `.middleware(...)`：你的 middleware 会跑在所有 Harness 内置之前。
- 通过 agent 上的 `RuntimeContext` 读当前调用的身份（`userId` / `sessionId`）。
- 读写工作区用 `harnessAgent.getWorkspaceManager()`，它会按当前文件系统模式（本机 / 沙箱 / 远端）正确路由。直接 `java.nio.Files` 在沙箱或远端模式下会写错地方。

## 相关文档

- [会话操作、事件与恢复](/dev/zh/docs/harness/session-log) — 后台任务、排队、交互和续做
- [可恢复聊天示例](/dev/zh/blogs/best-practices/session-chat) — 从提交任务到前端断线续传的完整应用
- [工作区](/dev/zh/docs/harness/workspace) — 目录结构、指令与参考材料的来源、`tools.json`
- [上下文与 AgentState](/dev/zh/docs/building-blocks/context) — `AgentState`、`RuntimeContext`、`AgentStateStore` 持久化、多用户隔离
- [记忆](/dev/zh/docs/harness/memory) — 两层记忆
- [上下文管理](/dev/zh/docs/harness/context) — 构建模型输入、跟进长程任务、压缩对话和卸载大结果
- [文件系统](/dev/zh/docs/harness/filesystem) — 本机 + shell / 共享存储 / 沙箱
- [沙箱](/dev/zh/docs/harness/sandbox) — 隔离执行、跨调用恢复、分布式
- [子 Agent](/dev/zh/docs/harness/subagent) — 声明、同步/后台、流式转发
- [技能](/dev/zh/docs/harness/skill) — 四层合成、自学习闭环
- [计划模式](/dev/zh/docs/harness/plan-mode) — 只读阶段 + HITL 退出
- [Channel](/dev/zh/docs/harness/channel) — 会话管理、多 agent 路由、流式 SSE

## 模型输入的统一构建

Harness 在最终模型调用前统一组织 System 指令、技能目录、对话、临时状态和参考材料。动态业务信息通过 `contextSource` 接入。配置、默认行为和限制见[上下文管理](/dev/zh/docs/harness/context)。
