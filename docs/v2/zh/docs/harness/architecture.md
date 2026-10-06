---
title: Harness 架构
description: HarnessAgent 是什么、如何选择调用方式、组合业务能力并保存会话状态
en_link: /v2/en/docs/harness/architecture
---

`HarnessAgent` 将推理、工具与上下文管理，和工作区、长期记忆、技能、子 Agent、沙箱等能力组合在一起。先用 [Quick Start](/v2/zh/docs/quickstart) 跑通问答，再按业务需要增加能力。

`ReActAgent` 提供推理循环、工具、权限和会话上下文等基础 API；Harness 在这些能力上提供开箱即用的组合与默认配置。两者都支持 `call` 和 `streamEvents`。

推荐在应用启动时配置共享 `HarnessAgent.Builder`，每次直接调用前用 `builder.build()` 创建新实例，执行结束后关闭。会话通过稳定身份与同一日志后端延续；具体代码和共享实例的适用方式见[实例生命周期](/v2/zh/docs/building-blocks/agent#实例生命周期)。后台 `AgentSession` 的实例则由会话管理器持有到后台执行结束。

## 核心工作原理

一次执行会读取当前会话状态，构建模型输入，按需调用工具，再保存结果。工作区、记忆和压缩等能力在这些步骤中生效；业务通过 builder、工具和 middleware 定制行为。

## 选择调用方式

- **获取回复或把 Agent 接入工作流**：使用 `call(input, ctx)`。
- **在当前请求内展示实时文本和工具进度**：使用 `streamEvents(input, ctx)`。
- **让任务在后台继续，支持排队、补充要求和中断恢复**：使用 `agent.session(ctx)` 提供的 `AgentSession`。

直接调用也支持多轮会话，并使用默认的 Session Log 和 checkpoint 保存状态。AgentSession 进一步负责持久接收任务、安排执行和关联待办；业务无需为这些操作自行管理执行订阅。两种方式使用同一个 Agent 的模型、工具和中间件配置。

调用示例见[智能体](/v2/zh/docs/building-blocks/agent)，完整的后台会话流程见[会话操作、事件与恢复](/v2/zh/docs/harness/session-log)。已有工作流调度器的应用可继续直接调用；托管 HTTP 接入使用 [Service Agent API](/v2/zh/service/session-event-log)。

## 核心组件

按应用需要配置能力，或调用会话操作。

| 能力 | 解决什么问题 | 配置或操作 | 详细文档 |
|---|---|---|---|
| 工作区驱动的人格 | 人格 / 知识 / 子 agent / 技能 / MCP 白名单都以文件形式存在 | `.workspace(path)` | [工作区](/v2/zh/docs/harness/workspace) |
| 状态持久化与执行日志 | 完整事实、checkpoint、跨请求和跨节点恢复 | 默认 EVENT_LOG；`.sessionLogStore(...)` 替换 | [会话日志](/v2/zh/docs/harness/session-log) |
| 会话任务管理 | 后台执行、持久排队、补充信息和恢复 | `agent.session(ctx)` | [会话操作](/v2/zh/docs/harness/session-log) |
| 双层长期记忆 | 长会话里有价值的事实自动沉淀到 `MEMORY.md` | 默认开启；`.memory(...)` 定制 prompt / 触发策略 | [记忆](/v2/zh/docs/harness/memory) |
| 对话压缩 | 上下文有界；模型真的溢出时强制重试 | `.compaction(...)` | [上下文管理](/v2/zh/docs/harness/context) |
| 大工具结果卸载 | 超 80K 字符的结果落盘 + 占位符 | `.toolResultEviction(...)` | [上下文管理](/v2/zh/docs/harness/context) |
| 子 agent 编排 | 委派给子 agent，支持同步或后台，自动反向通知 | `.subagent(...)` 或 `workspace/subagents/` | [子 Agent](/v2/zh/docs/harness/subagent) |
| 可插拔文件系统 | 本机 + shell / 共享存储 / 沙箱，不改代码切换 | `.filesystem(...)` | [文件系统](/v2/zh/docs/harness/filesystem) |
| 沙箱隔离 | 文件与命令隔离，跨调用恢复，多副本部署 | `.filesystem(new DockerFilesystemSpec()...)` | [沙箱](/v2/zh/docs/harness/sandbox) |
| 计划模式 | 只读思考阶段 + HITL 退出 | `.enablePlanMode()` | [计划模式](/v2/zh/docs/harness/plan-mode) |
| 技能装配 | 来自 Git / Nacos / MySQL / classpath / 工作区 | `.skillRepository(...)` | [技能](/v2/zh/docs/harness/skill) |
| MCP 集成与工具白名单 | 声明式 MCP server + 工具粒度允许 / 拒绝 | `workspace/tools.json` | [工作区](/v2/zh/docs/harness/workspace) |
| Channel 路由 | 会话管理、per-session 并发控制、多 agent 路由、流式事件 | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/v2/zh/docs/harness/channel) |

## 状态怎么流转

状态分三层，框架自动在层之间搬数据。

- **调用内状态** —— `AgentState`（对话上下文、权限规则、Plan Mode 状态、工具状态）加上 `RuntimeContext`（`sessionId`、`userId`、沙箱句柄、extra）。
- **跨调用状态** —— 默认从 Session Log 的 checkpoint 及后续可应用事实恢复；完整历史保留，JSONL 仅按需导出。LEGACY 使用 AgentStateStore；文件、子任务及 sandbox 元数据仍由各自后端保存。
- **长期记忆** —— 跨 session 累积：`memory/YYYY-MM-DD.md` 只追加；后台节流任务把它周期合并到 `MEMORY.md`；`MEMORY.md` 每次 call 加载为参考上下文，不作为 System 指令。

三个值得记住的规律：

- 最终请求每轮重新构建，但工作区文件按 call 加载；修改 AGENTS.md 或 MEMORY.md 后下一次 call 生效，不需要重启。
- 压缩、记忆提炼、后台维护都被节流闸门管着，不会每轮都跑。
- `AgentState` 的持久化由 Core 执行生命周期管理：EVENT_LOG 写 checkpoint，LEGACY 写旧状态存储。Harness 配置默认日志后端。

## 自己加 middleware 时要注意什么

要在不绕过 Harness 内置链路的前提下插入自定义行为：

- 用 `.middleware(...)`：你的 middleware 会跑在所有 Harness 内置之前。
- 通过 agent 上的 `RuntimeContext` 读当前调用的身份（`userId` / `sessionId`）。
- 读写工作区用 `harnessAgent.getWorkspaceManager()`，它会按当前文件系统模式（本机 / 沙箱 / 远端）正确路由。直接 `java.nio.Files` 在沙箱或远端模式下会写错地方。

## 相关文档

- [会话操作、事件与恢复](/v2/zh/docs/harness/session-log) — 后台任务、排队、交互和续做
- [可恢复聊天示例](/v2/zh/blogs/best-practices/session-chat) — 从提交任务到前端断线续传的完整应用
- [工作区](/v2/zh/docs/harness/workspace) — 目录结构、指令与参考材料的来源、`tools.json`
- [上下文与 AgentState](/v2/zh/docs/building-blocks/context) — `AgentState`、`RuntimeContext`、`AgentStateStore` 持久化、多用户隔离
- [记忆](/v2/zh/docs/harness/memory) — 两层记忆
- [上下文管理](/v2/zh/docs/harness/context) — 构建模型输入、跟进长程任务、压缩对话和卸载大结果
- [文件系统](/v2/zh/docs/harness/filesystem) — 本机 + shell / 共享存储 / 沙箱
- [沙箱](/v2/zh/docs/harness/sandbox) — 隔离执行、跨调用恢复、分布式
- [子 Agent](/v2/zh/docs/harness/subagent) — 声明、同步/后台、流式转发
- [技能](/v2/zh/docs/harness/skill) — 四层合成、自学习闭环
- [计划模式](/v2/zh/docs/harness/plan-mode) — 只读阶段 + HITL 退出
- [Channel](/v2/zh/docs/harness/channel) — 会话管理、多 agent 路由、流式 SSE

## 模型输入的统一构建

Harness 在最终模型调用边界统一组织 System、对话、任务状态和参考材料；动态业务信息通过 contextSource 接入。配置、默认行为和限制见 [上下文管理](/v2/zh/docs/harness/context)。
