---
title: "Harness 架构"
description: "HarnessAgent 是什么、各能力如何协作、状态如何在一次 call() 中流转"
---

`HarnessAgent` 是 `ReActAgent` 的一层薄包装，把长期运行 agent 必备的工程能力打包进单一 builder：工作区驱动的人格、长期记忆、子 agent 编排、沙箱隔离、技能装配、计划模式、Channel 路由。

裸的 `ReActAgent` 只解决"一次请求 → 推理 → 工具 → 回复"。Harness 要回答的是另一组问题：下一轮怎么接着上一轮、上下文如何保持有界、多用户如何隔离、危险操作如何先 review 再执行、可复用能力如何沉淀。

> 安装、依赖、跑通第一个 `HarnessAgent` 的端到端示例见 [快速开始](../quickstart.md)。本页只讲架构。

## 核心工作原理

理解 Harness 只需要记住三件事：

**1. 能力是叠加在推理循环关键时机上的，不是改写循环。**
工作区注入、压缩、子 agent、沙箱、Plan Mode —— 每个能力都钩在 ReAct 循环的关键时机。core 的算法本身没动，Harness 只往里加东西。

**2. 能力之间互不依赖，只通过共享对象通信。**
每个能力只做自己的事，互相不感知。它们之间靠三个共享对象交流：

- **`RuntimeContext`** —— 这次 `call()` 是谁在说话：`sessionId`、`userId`、自定义 extra。不持久化。
- **工作区** —— 谁读写哪些文件。物理落到本机、沙箱还是 KV 存储是配置决定。
- **`AgentStateStore`** —— 跨调用怎么恢复运行时状态。

**3. 内置 middleware 注册顺序固定，你自己加的跑在最前面。**
Harness 在构建期按固定顺序串起所有内置 middleware。你通过 `.middleware(...)` 加的会跑在 Harness 内置之前。

## 核心组件

每个能力对应一个问题，按需在 builder 上打开。

| 能力 | 解决什么问题 | Builder 入口 | 详细文档 |
|---|---|---|---|
| 工作区驱动的人格 | 人格 / 知识 / 子 agent / 技能 / MCP 白名单都以文件形式存在 | `.workspace(path)` | [工作区](./workspace) |
| 状态持久化 | 同 `(userId, sessionId)` 跨请求、跨进程、跨副本恢复 | 默认开启；`.stateStore(...)` 替换实现 | [上下文与 AgentState](../building-blocks/context) |
| 双层长期记忆 | 长会话里有价值的事实自动沉淀到 `MEMORY.md` | 默认开启；`.memory(...)` 定制 prompt / 触发策略 | [记忆](./memory) |
| 对话压缩 | 上下文有界；模型真的溢出时强制重试 | `.compaction(...)` | [上下文压缩](./compaction) |
| 大工具结果卸载 | 超 80K 字符的结果落盘 + 占位符 | `.toolResultEviction(...)` | [上下文压缩](./compaction) |
| 子 agent 编排 | 委派给子 agent，支持同步或后台，自动反向通知 | `.subagent(...)` 或 `workspace/subagents/` | [子 Agent](./subagent) |
| 可插拔文件系统 | 本机 + shell / 共享存储 / 沙箱，不改代码切换 | `.filesystem(...)` | [文件系统](./filesystem) |
| 沙箱隔离 | 文件与命令隔离，跨调用恢复，多副本部署 | `.filesystem(new DockerFilesystemSpec()...)` | [沙箱](./sandbox) |
| 计划模式 | 只读思考阶段 + HITL 退出 | `.enablePlanMode()` | [计划模式](./plan-mode) |
| 技能装配 | 来自 Git / Nacos / MySQL / classpath / 工作区 | `.skillRepository(...)` | [技能](./skill) |
| MCP 集成与工具白名单 | 声明式 MCP server + 工具粒度允许 / 拒绝 | `workspace/tools.json` | [工作区](./workspace) |
| Channel 路由 | 会话管理、per-session 并发控制、多 agent 路由、流式事件 | `agent.channel(...)` / `GatewayBootstrap` | [Channel](./channel) |

## 状态怎么流转

状态分三层，框架自动在层之间搬数据。

- **调用内状态** —— `AgentState`（对话上下文、权限规则、Plan Mode 状态、工具状态）加上 `RuntimeContext`（`sessionId`、`userId`、沙箱句柄、extra）。
- **跨调用状态** —— 每次 `call()` 结束自动写盘、下次自动加载：存在 `AgentStateStore`（默认 `~/.agentscope/state/<agentId>/`，按 `(userId, sessionId)` 寻址）里的 `AgentState` 运行时快照、`sessions/<sessionId>.log.jsonl` 的永不压缩对话日志、子任务记录、沙箱元数据。
- **长期记忆** —— 跨 session 累积：`memory/YYYY-MM-DD.md` 只追加；后台节流任务把它周期合并到 `MEMORY.md`；`MEMORY.md` 每轮推理被注入 system prompt。

三个值得记住的规律：

- system prompt 每轮重新拼，所以你改 `AGENTS.md` 或 `MEMORY.md` 立刻生效，不需要重启。
- 压缩、记忆提炼、后台维护都被节流闸门管着，不会每轮都跑。
- `AgentState` 由 core 的 `ReActAgent` + `AgentStateStore` 自动持久化。Harness 不再额外做这件事。

## 自己加 middleware 时要注意什么

要在不绕过 Harness 内置链路的前提下插入自定义行为：

- 用 `.middleware(...)`：你的 middleware 会跑在所有 Harness 内置之前。
- 通过 agent 上的 `RuntimeContext` 读当前调用的身份（`userId` / `sessionId`）。
- 读写工作区用 `harnessAgent.getWorkspaceManager()`，它会按当前文件系统模式（本机 / 沙箱 / 远端）正确路由。直接 `java.nio.Files` 在沙箱或远端模式下会写错地方。

## 相关文档

- [工作区](./workspace) — 目录结构、注入到 system prompt 的内容、`tools.json`
- [上下文与 AgentState](../building-blocks/context) — `AgentState`、`RuntimeContext`、`AgentStateStore` 持久化、多用户隔离
- [记忆](./memory) — 两层记忆
- [上下文压缩](./compaction) — 摘要压缩、大结果卸载、溢出兜底
- [文件系统](./filesystem) — 本机 + shell / 共享存储 / 沙箱
- [沙箱](./sandbox) — 隔离执行、跨调用恢复、分布式
- [子 Agent](./subagent) — 声明、同步/后台、流式转发
- [技能](./skill) — 四层合成、自学习闭环
- [计划模式](./plan-mode) — 只读阶段 + HITL 退出
- [Channel](./channel) — 会话管理、多 agent 路由、流式 SSE
