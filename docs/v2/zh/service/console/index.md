---
title: "控制台概览"
en_link: /v2/en/service/console/index
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

控制台是 AgentScope Service 的图形化操作入口。你可以在这里配置 Agent、试用对话、分派工作、编排团队，并处理需要人工决定的事项。这些操作使用平台的 API，和业务系统管理的是同一批 Agent、Issue、执行记录与资源：通过 API 创建的工作可以在控制台跟进，控制台创建的 Agent 也可以通过 API 调用。

业务应用集成从[快速开始](/v2/zh/service/first-session)和[Service API](/v2/zh/service/service-api)入手。本模块介绍人在控制台中如何操作；完整字段、认证和调用契约见 [API 参考](/v2/zh/service/api-reference)。

## 从哪里开始

登录后先确认当前 Namespace。侧栏按实际工作分为三组，账号权限决定哪些菜单与操作可见。

| 区域 | 入口 | 用途 |
| --- | --- | --- |
| Work | Chat、Issues、Inbox、Automations | 对话、分派与跟进任务、处理反馈、安排自动触发 |
| Design | Agents、Teams、Workflows、Channels | 管理执行者、配置协作与流程、连接消息平台 |
| Resources | Workspaces、Environments、Memory、Vault | 配置文件与能力来源、运行环境、记忆和凭据 |

Overview 汇总当前工作。底部的 Access settings 和 Profile 用于查看账号及访问设置。找不到预期资源时，先核对 Namespace 和访问权限。

## 完成一项工作的路径

先进入 **Design → Agents** 创建或查看一个可用 Agent，用 **Work → Chat** 发一个小请求，确认模型、工具和运行环境可用。需要把请求变成有明确交付要求的工作时，到 **Work → Issues** 填写目标、资料和验收标准，再选择 Agent 或 Team 执行。

工作开始后，在 Issue 中阅读讨论、文件和 Executions；需要人工验收或审批时，进入 **Work → Inbox** 作出决定。若工作需要多人分工，先配置 Team；若步骤、依赖和审批关口已经确定，则配置 Workflow。这个过程使用的资源、状态和结果也可以通过 API 获取。

| 想完成的事情 | 控制台指南 | 对应 API 指南 |
| --- | --- | --- |
| 创建、接入和测试 Agent | [管理 Agent](/v2/zh/service/console/agents) | [Agent 创建与注册](/v2/zh/service/agents) |
| 对话、任务分派与人工反馈 | [对话、任务与反馈](/v2/zh/service/console/tasks) | [Issue](/v2/zh/service/issues) · [Inbox](/v2/zh/service/inbox) |
| 配置 Team、Workflow 并发布服务 | [团队与流程编排](/v2/zh/service/console/orchestration) | [Team](/v2/zh/service/create-team) · [Workflow](/v2/zh/service/workflows) |
| 定时执行、接收 Webhook 或消息 | [自动化与消息渠道](/v2/zh/service/console/automation) | [Automation](/v2/zh/service/automation) · [Channel](/v2/zh/service/channels) |

## 为 Agent 准备资源

在 **Resources** 中按需要准备资源，再在 Agent 配置中关联。Workspace 保存规则、技能、工具等能力来源；Environment 决定工具在哪里执行；Memory 用于跨会话记忆；Vault 保存调用外部服务所需的凭据。它们的使用条件与配置字段分别见 [Workspace](/v2/zh/service/workspaces)、[Environment](/v2/zh/service/environments)、[Memory](/v2/zh/service/memory) 和 [Vault](/v2/zh/service/vault)。

浏览器所在电脑的路径不会自动成为 Agent 可读的文件。为任务提供文件时，使用目标 Agent 能访问的资源，或上传为工作附件。控制台中的资源绑定也需要与所选运行时的实际能力相匹配。

控制台提供常用操作表单；应用注册、Runtime Host 连接和部分高级配置仍通过 SDK、CLI 或 API 完成。后面的指南会在对应步骤说明入口。
