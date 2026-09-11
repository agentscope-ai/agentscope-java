---
title: "AgentScope Service 用户指南"
---

[English](/v2/en/service/index)

AgentScope Service 让你在一个控制台中与 Agent 对话、分派工作、组织多 Agent 协作，并将这些能力提供给应用。你既可以创建平台托管的 Agent，也可以接入本机 Coding Agent 或独立部署的 Agent 应用。

## 先完成第一项工作

已有服务账号时，从[第一次对话与交付](/v2/zh/service/first-session)开始；自行部署时，先完成[本地安装](/v2/zh/service/quickstart)。第一次成功的标准是：能登录、能收到回复、能找回历史，并能检查一项工作交付物。

## 按控制台学习

| 区域 | 你能完成的工作 |
| --- | --- |
| WORK | [Chat](/v2/zh/service/chat) 探索需求 → [Issues](/v2/zh/service/issues) 跟踪工作 → [Inbox](/v2/zh/service/inbox) 审批验收 → [Automations](/v2/zh/service/automation) 重复执行 |
| DESIGN | 配置 [Agents](/v2/zh/service/agents)、[Teams](/v2/zh/service/teams)、[Workflows](/v2/zh/service/workflows) 和 [Channels](/v2/zh/service/channels) |
| Resources | 管理 [Workspaces](/v2/zh/service/workspaces)、[Environments](/v2/zh/service/environments)、[Memory](/v2/zh/service/memory) 和 [Vault](/v2/zh/service/vault) |

Overview 是日常工作的总览；具体操作按上述页面展开。菜单和操作会按账号权限显示。Namespace 是资源与授权范围，Workspace 是文件与能力资源，二者不要混淆。

## 部署和接入

管理员可以用 [Docker Compose](/v2/zh/service/docker) 安装单机服务，或用 [Helm](/v2/zh/service/kubernetes) 安装到 Kubernetes。Gateway 提供统一入口，Control 管理目录和工作，Dataplane 执行托管会话，Scheduler 处理运行调度；PostgreSQL、Workspace 和 Artifact 存储保存持久数据。

选择运行方式时，阅读 [Managed Agent](/v2/zh/service/managed-agent)、[Hosted Agent](/v2/zh/service/hosted-agent) 或 [External Agent](/v2/zh/service/external-agent)。要把工作接入业务系统，使用 [Endpoint](/v2/zh/service/endpoints)；多个成员协作见 [Team 指南](/v2/zh/service/team-collaboration)。

服务安装和模型/执行能力配置是两个步骤。安装包不包含模型额度、外部系统账号或 Coding Agent provider 登录。

## 查阅与实践

[概念](/v2/zh/service/concepts)解释对象关系，[API 参考](/v2/zh/service/api-reference)提供调用入口，[运维](/v2/zh/service/operations)覆盖升级恢复，[排障](/v2/zh/service/troubleshooting)帮助定位失败。[场景案例](/v2/zh/service/usecases)列出后续完整教程的方向；已有操作步骤可直接从功能指南实践。
