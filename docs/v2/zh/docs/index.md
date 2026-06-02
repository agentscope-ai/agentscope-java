---
title: "AgentScope 2.0 是什么？"
description: "Harness 工程化、企业级分布式部署、底层框架重构。"
---

AgentScope Java 2.0 从"构建一个智能体"的工具箱，迈向**面向生产环境运行智能体**的完整平台。本次升级围绕三大主题展开，每一部分都对应一个具体要解决的问题。

:::{note}
AgentScope Java 2.0 版本尽量保持了对 1.x 版本的兼容，确保大部分用户的平滑升级；但同时 2.0 也带来了 API 层面的不兼容变更，并在核心抽象、API 和架构上均有大幅改进。完整迁移指南详见 [Changelog](./change-log.md)。
:::

## 1 · Harness 工程化 —— 长期运行、复杂任务的工程底座

裸的 ReAct 循环只解决"一次推理"。真实任务往往要跑数小时、积累大量状态、依赖可持续沉淀的能力。**Harness** 把这套工程基础设施一次给齐：核心推理循环原样保留，能力按需叠加，让智能体能稳定长跑、能力越用越强、能从容完成复杂作业。

::::{grid} 2

:::{grid-item-card} 自进化与技能仓库
:link: harness/skill.html

成功模式以 Markdown 技能形式自动沉淀到 `workspace/skills/`，每轮按需加载、跨会话共享 —— 智能体真正在每次运行之间累积 know-how。
:::

:::{grid-item-card} 分层记忆管理
:link: harness/memory.html

三层记忆：上下文中的对话、agent 自维护的 `MEMORY.md`、磁盘上的事实流水账。自动压缩控制 prompt 体量，`memory_*` 工具提供显式回忆能力。
:::

:::{grid-item-card} 子智能体
:link: harness/subagent.html

在 Markdown 里声明子 agent 规格，主 agent 运行时按需 `agent_spawn` / `agent_send`，支持同步阻塞与后台委派。后台任务终态通过 system-reminder 反向推送，无需轮询。
:::

:::{grid-item-card} 上下文自动管理
:link: harness/context.html

结构化压缩保留目标 / 状态 / 关键发现 / 下一步；超大工具结果落盘、上下文只留占位符；ContextOverflow 兜底重试构成最后防线。
:::

:::{grid-item-card} 复杂任务规划（Plan Mode）
:link: harness/plan-mode.html

切到只读规划态做长任务编排；产出的计划文件持久化到 `workspace/plans/`，与执行阶段解耦，让"想清楚"和"做出来"分阶段推进。
:::

:::{grid-item-card} Workspace 工程底座
:link: harness/workspace.html

人格、知识、技能、子 agent 规格、会话日志全部以磁盘上的 Markdown / JSON 表达，每轮推理自动注入 system prompt。
:::

::::

## 2 · 企业级分布式部署

生产环境的智能体要服务多租户、要安全运行不可信工具代码、要在滚动发布时不丢失在途上下文。AgentScope 2.0 天然面向**无状态水平扩展**：任意副本都能恢复任意用户的完整上下文，沙箱状态可跨进程恢复，权限闸门 + 多维隔离把每一个租户的数据严格分开。

::::{grid} 2

:::{grid-item-card} 多租户隔离
:link: harness/context.html

支持 `session` / `user` / `agent` / `org` 多维度状态隔离。`RuntimeContext` 的键统一贯穿工作区路径、KV 命名空间、沙箱状态槽。
:::

:::{grid-item-card} 安全沙箱执行
:link: harness/sandbox.html

工具执行限定在隔离环境内 —— 本地子进程 / Docker / 远端 AgentRun 集群任选 —— 支持快照与恢复，让长时间任务能在进程重启后继续。
:::

:::{grid-item-card} 工具权限管控
:link: building-blocks/permission-system.html

权限三态决策（允许 / 用户审批 / 拒绝）综合静态规则、工具类型、输入分析；敏感工具强制人工审批，HITL 是框架内生能力。
:::

:::{grid-item-card} 优雅上下线与会话恢复
:link: harness/context.html

同一 `sessionId` 跨进程恢复完整对话；`Session` 抽象（`InMemory` / `JsonSession` / MySQL / Redis）支撑零停机滚动发布与崩溃恢复。
:::

::::

## 3 · 底层框架升级 —— 更轻、更顺手的核心抽象

底层做了一次重构：消息、事件、扩展机制更小、更正交、更顺手；HITL 与事件流式不再是外挂层，而是框架运行的一部分。

::::{grid} 2

:::{grid-item-card} 事件流式输出原生支持
:link: building-blocks/message-and-event.html

智能体每一步动作 —— 模型调用、文本增量、工具执行、工具结果 —— 都以类型化事件的形式流出。订阅一次，前端 UI 就能实时跟上。
:::

:::{grid-item-card} 更简洁的消息模型
:link: building-blocks/message-and-event.html

文本、文件、图片、音视频、模型思考、工具结果统一收敛到一个 `ContentBlock` 抽象；按 role 严格校验，让非法消息在构造期就被拦下，而不是跑起来才报错。
:::

:::{grid-item-card} Middleware 取代松散 Hook
:link: building-blocks/middleware.html

`onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt` 五个清晰阶段取代 v1 的扁平 hook 列表。每个关注点各居其层，组合起来干净利落。
:::

:::{grid-item-card} HITL 一等公民
:link: building-blocks/permission-system.html

可在执行中确认工具参数、审批敏感操作，或把执行整个交给外部系统完成。智能体会在暂停点等待并精确恢复，无需自己搭脚手架。
:::

::::

---

正在评估升级时间表的开发者，可以查阅 [Changelog](./change-log.md) —— 拆成"必须迁移 / 推荐迁移"两层的迁移指南，加上新功能罗列，足以端到端规划一次升级。
