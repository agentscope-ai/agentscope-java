---
title: "多 Agent 编排概览"
description: "通过 Subagent、Team 和 Workflow 组织协作，将 Managed、External 与 Hosted Agent 纳入同一编排。"
en_link: /v2/en/service/orchestration
---

当一项工作需要不同专业能力配合时，可以把它拆成职责和交付明确的部分，再让多个 Agent 共同推进。AgentScope Service 支持在托管任务内部委派子 Agent，也支持通过 Team 组织独立成员，或者通过 Workflow 规定步骤与依赖。选择哪种方式，取决于这项工作需要临时分工、持续协作，还是明确的执行流程。

参与协作的成员可以是 Service 运行的 Managed Agent，也可以是已经开发好的 External Agent，或通过 Runtime Host 连接的 Hosted Agent。它们进入同一个 Agent 目录后，可以按实际能力参与团队和流程编排，因此已有业务 Agent 和 Coding Agent 也能够与托管 Agent 配合完成工作。

首次使用时，可以先阅读本页，确定工作需要怎样的协作，再按[创建与运行 Team](/v2/zh/service/create-team)或[编排 Workflow](/v2/zh/service/workflows)完成一次执行。如果已有应用或 Coding Agent 需要参与，再展开“接入已有 Agent”，从注册 External Agent 或连接 Hosted Agent 的教程开始，遇到 SDK、运行协议或配置问题时查阅对应文档。协作能够正常执行后，再阅读“工作分派与自动化”，将工作接入业务验收、定时触发或消息平台。

## 选择协作层次

如果只是当前托管任务中的一部分需要独立分析或复核，可以使用 HarnessAgent 的 Subagent。主 Agent 在自己的执行过程中发起委派，通过子会话取得结果，再继续完成原任务。子 Agent 的配置与观察方式见[Skills 与子 Agent](/v2/zh/service/workspaces#skills)和[会话、任务与预算](/v2/zh/service/session-event-log)。这种委派属于当前 Agent 的运行过程，不会自动创建一个平台 Team。

如果几个成员需要围绕同一个目标持续分工、沟通并交付，可以创建 Service Team。Lead 根据任务安排成员，成员通过持久的任务与讨论记录协作，结果也可以进入业务验收。Team 的成员可以使用不同的运行方式，因而适合将托管助手、专用业务 Agent 和 Coding Agent 组织在一起。角色与成员的配置见[创建与运行 Team](/v2/zh/service/create-team)。

如果工作的步骤和推进条件已经明确，可以使用 Workflow，把依赖、分支和人工审批表达为执行图。流程中的节点可以调用 Agent 或 Team：Team 负责在某一步中组织成员完成目标，Workflow 则决定这一步何时开始，以及满足什么条件后继续。定义和发布流程的方法见[编排 Workflow](/v2/zh/service/workflows)。

## 先建立一个纯 Managed Team

首次验证团队协作时，可以先创建 Lead、Researcher 和 Reviewer 三个 Managed Agent，分别确认模型、工具与资源访问正常。为每个角色约定清楚的交付：Researcher 提供来源和事实，Reviewer 检查结论是否有依据，Lead 根据成员结果完成汇总，并保留尚未解决的问题。

随后按[创建与运行 Team](/v2/zh/service/create-team)添加成员和角色，再提交一个规模较小的任务。检查 Issue 中的讨论和 Run graph，可以确认 Lead 是否实际分派了工作、成员是否返回了结果，以及汇总是否使用了这些交付。需要人工验收时，由有权限的人检查并决定接受或退回。

团队验证通过后，再根据工作需要增加成员或并发。如果多个成员需要修改同一份材料，应先约定文件所有权和同步方式，让每个成员知道自己能够修改什么，以及如何把结果交给下一位成员。

## 需要已有能力时接入其他 Agent

已有使用 AgentScope SDK 开发的应用，可以按[注册 External Agent](/v2/zh/service/register-agentscope-agent)接入平台，保留自己的进程和业务逻辑，并作为 External Agent 接收分派。其他框架或已有业务服务则按[External SDK 与运行协议](/v2/zh/service/external-agent)适配任务领取、事件、结果与取消。接入后，团队引用平台中的 Agent ID，由运行绑定将工作交给原来的应用执行。

如果希望复用已安装的 Coding Agent provider，可以按[连接 Hosted Agent](/v2/zh/service/connect-hosted-agent)准备运行环境，再由[Runtime Host](/v2/zh/service/runtime-host)启动 provider、管理工作目录并回报执行记录。模型和启动选项等设置见[Hosted Agent 配置](/v2/zh/service/hosted-agent-configuration)。这样，负责代码修改的成员可以继续使用自己的运行方式，同时向团队交付结果。

注册完成后，应先单独执行一个小任务，检查成员是否支持团队所需的任务派发、协作和控制操作，再把它加入 Team 或配置为 Workflow 的执行目标。目录中能够看到 Agent，或者它能够回答聊天问题，都不能证明这些协作能力已经具备。验证方法见[接入检查](/v2/zh/service/api-reference#integrations)。

External 和 Hosted Agent 也可以直接作为 Session 的 Agent 目标执行任务。组成团队时，Managed Lead 可以负责整体协调，External 成员提供内部业务能力，Hosted 成员完成代码工作；应用仍通过 Session API 提交工作并读取结果。

<span id="发布与观察协作服务"></span>

## 通过 Session API 调用与观察协作

验证协作后，应用直接选择 Team 或 Workflow 创建 Session。Workflow 需要先发布 revision，Session 会固定这个版本；Team 会固定创建时的协作配置。应用随后以 Turn 为单位提交工作、读取进度和处理待办。每个 Turn 对应独立的协作或流程任务，具体调用方式见[通过 Session API 接入应用](/v2/zh/service/service-api)。

查看一个任务时，应用先恢复快照，再持续接收事件。如果需要了解某个成员如何处理分派，可以进一步查看内部 Issue 和 Run；需要检查交付时，则读取实际产物与业务验收记录。事件展示和文件交付分别见[SSE 与事件续传](/v2/zh/service/sse-events)和[文件与产物](/v2/zh/service/files)。

成员不自动共享同一个文件系统，应通过可访问的 Artifact 传递结果。取消或重试也不会撤销已经发生的外部操作；更换运行方式前，需要检查成员状态和已完成的工作。具体约束见[Team 协作](/v2/zh/service/create-team#team-collaboration)与[执行参考](/v2/zh/service/sessions)。

## 让编排融入业务工作

如果工作需要负责人、讨论和人工验收，可以用[工作分派、审批与验收](/v2/zh/service/issues)中的 Issue 保存目标和交付标准，并交给 Agent 或 Team 执行。这样，成员的执行记录与业务的接受或退回决定能够关联到同一项工作。

需要定期执行或由外部事件发起工作时，可以配置[计划与事件触发](/v2/zh/service/automation)，在指定时间或收到 Webhook 时把任务交给 Agent 或 Team。规则负责决定工作何时开始，执行目标的配置决定工作如何完成。当前 Automation 不能直接以 Workflow 为执行目标；如果需要运行固定流程，应用应通过 Session API 调用已发布的 Workflow revision。

如果用户从消息平台提出需求，可以按[接入消息渠道](/v2/zh/service/channels)配置消息接收、目标路由和结果回传。当前飞书支持将外部消息关联为 Issue，并交给 Agent 或 Team 执行；这一工作路由不接受 Workflow 目标。其他渠道适配器的能力应分别确认，不能仅凭连接成功就认定它们支持相同流程。消息渠道需要单独配置，不是 Automation 的一种触发方式；接入时应将外部对话、执行记录和验收结果关联起来，方便用户跟进工作。
