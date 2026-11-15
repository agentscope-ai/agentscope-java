---
title: AgentScope 2.0 是什么？
description: 用 Harness 构建能持续完成任务的 Agent，并将它接入业务应用、运行环境与服务 API。
en_link: /dev/en/docs/index
---

<Note>
本页属于 dev 文档，描述当前开发版本，API 与示例可能不同于已发布的包。使用 AgentScope Java `2.0.4` 时，请参阅 [2.0 文档](/v2/zh/intro)。
</Note>


**AgentScope 2.0 是面向智能体应用的开源开发框架，帮助你把模型、工具和业务数据组织成能够持续执行任务的 Agent。** 你可以用它构建多轮对话助手，也可以让 Agent 检索资料、处理文件、运行代码、委派子任务，在需要时等待人的确认，再继续完成工作。

推荐在应用启动时定义共享 Builder，每次请求前用 `builder.build()` 创建新的 Agent，执行结束后关闭。相同会话通过稳定身份和持久存储延续，具体用法见[快速开始](/dev/zh/docs/quickstart)。

这份文档介绍 Java 版本。你可以将 Agent 直接嵌入现有 Java 或 Spring Boot 应用，从一次调用开始，逐步加入上下文管理、工作区、持久会话和多 Agent 协作。

## 用 AgentScope 能做什么

对于客服、知识问答和业务助理，你可以让 Agent 结合会话历史、检索结果和实时业务数据回答问题，并通过工具查询订单、更新工单或调用已有系统。前端既能展示回答，也能展示正在调用的工具和需要用户处理的待办。

对于调研、数据分析、代码修改等多步骤任务，Agent 可以在工作区中读取材料、制定计划、执行工具并生成文件；涉及不同专业能力时，再委派给子 Agent。长任务积累的上下文、用户中途补充的要求，以及暂停后继续所需的状态，都有对应的管理机制。

例如，一个“整理客户反馈并生成改进报告”的 Agent，可以先读取资料和团队规范，调用业务工具核对数据，让子 Agent 分别分析不同产品线，最后生成报告。用户可以在过程中补充关注点，在发送报告前确认，也可以离开页面后再回来查看进度。**Harness 将这些步骤需要的运行能力组合起来，让开发者集中定义业务目标、工具和执行边界。**

## Harness 如何支撑 Agent 持续工作

ReAct 的“推理、执行工具、观察结果”循环是 AgentScope Harness 的基础。`ReActAgent` 提供推理、工具、权限和中间件等能力，`HarnessAgent` 在同一循环上组合工作区、上下文与会话管理，围绕下面三个维度支撑持续执行。推荐从 `HarnessAgent` 开始，再按场景配置所需能力；具体组合见 [Harness 架构](/dev/zh/docs/harness/architecture)。

### 持续跟进长程任务

处理代码修复、调研等多步骤任务时，可以启用 Todo 跟踪进度，结合 Plan Mode 先调查、写计划，经用户确认后再执行；独立工作可委派给子 Agent，同步等待或在后台完成。计划、待办和子任务各有状态，后续执行能够继续跟进未完成的工作。有明确验收标准时，应用还可以记录任务要求与实际验证结果，为下一步提供依据。

Thread 日志保存原生消息、工具与交互事实，checkpoint 保存继续调用所需状态。后台任务由 [Durable](/dev/zh/docs/harness/durable) 受理、排队和恢复，前端通过公共快照与事件观察任务。

### 管理上下文，控制模型能看到什么

Harness 在每次请求模型前，重新组织稳定指令、对话历史、当前任务状态和参考材料。工作区提供项目规则、记忆、知识与 Skill，动态来源按轮次补充最新业务事实；应用可以配置内容来源、优先级和预算，让本轮输入围绕当前工作展开。记忆和外部材料作为参考资料，业务数据的读取权限仍由应用校验。

随着任务变长，Harness 将过大的工具结果保存到文件，按预算选择材料，并通过结构化压缩保留目标、关键发现和下一步。模型使用精简后的工作上下文，完整执行历史仍保留在 Thread 日志 中；跨会话的信息则由长期记忆承接。详见[上下文管理](/dev/zh/docs/harness/context)、[工作区](/dev/zh/docs/harness/workspace)和[记忆](/dev/zh/docs/harness/memory)。

### 让工具受控、安全地执行

Java 工具和 MCP 将模型的行动请求连接到文件、代码与业务系统。应用配置可用工具，权限系统再根据规则、运行模式和具体参数，决定允许、拒绝或请求用户确认。需要人工审批或外部系统执行的操作，可以保存待办、等待结果，再继续推理。

执行代码和命令时，可以配置 Docker 或远端沙箱，并选择按用户或会话隔离的范围；实际文件、网络和资源边界由工具实现与执行后端落实。工具调用、结果和审批过程通过类型化事件与会话日志供应用展示和追踪。详见[工具](/dev/zh/docs/building-blocks/tool)、[权限与人工介入](/dev/zh/docs/building-blocks/permission-system)和[沙箱](/dev/zh/docs/harness/sandbox)。

## 在业务应用中扩展和部署

AgentScope 通过模型扩展接入不同供应商，你可以按任务选择模型，并配置重试与备用模型策略。统一的消息结构承载文本、图片、文件和工具结果；业务能力可以通过 Java 工具或 MCP 接入。需要动态上下文、模型调用策略、审计或工具检查时，可以通过 Middleware 介入执行环节。详见[模型](/dev/zh/docs/building-blocks/model)、[工具](/dev/zh/docs/building-blocks/tool)和[Middleware](/dev/zh/docs/building-blocks/middleware)。

从本地开发走向多用户、多副本部署时，需要为用户和会话配置稳定身份，选择合适的工作区隔离范围，并使用各副本可访问的持久后端。Workspace 的 Filesystem 可以对接共享存储，Thread 日志 也支持替换后端；原生会话写入通过租约和写入隔离控制并发。沙箱生命周期与恢复策略则按所选执行后端配置。部署方式和存储选型见[上生产](/dev/zh/docs/others/going-to-production)。

## 从哪里开始

先用最贴近当前需求的入口，再逐步增加能力：

| 你的目标 | 建议入口 |
| --- | --- |
| 在 Java 应用中获得回复，或把 Agent 作为工作流节点 | 从[快速开始](/dev/zh/docs/quickstart)使用 `agent.call` |
| 在当前请求中展示回答和工具执行进度 | 使用 `agent.streamEvents`，参考[消息与事件](/dev/zh/docs/building-blocks/message-and-event) |
| 构建能后台执行、排队、交互和恢复的聊天应用 | 通过 `runtime` 操作 Durable Session/Thread/Turn，运行[可恢复聊天示例](/v2/zh/blogs/best-practices/session-chat) |

从 1.x 升级请阅读 [V1 迁移指南](/dev/zh/docs/change-log)；各版本的具体变化见 [Release Notes](/dev/zh/docs/others/release-notes)。
