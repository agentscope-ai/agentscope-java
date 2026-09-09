# AgentScope Service

AgentScope Service 为托管 Agent、已有 Agent 应用和 Coding Agent 提供统一的控制面。你可以创建 Agent 并运行会话，也可以把现有运行时接入平台，再通过 Issue、Team 和工作流组织协作。

![AgentScope Service 架构](../../../imgs/agentservice/agentscope-service-architecture.png)

## 从你的目标开始

| 你想做什么 | 从哪里开始 |
| --- | --- |
| 在自己的机器上体验 | [Docker 快速上手](quickstart.md) |
| 让第一个 Agent 完成任务 | [第一个 Session](first-session.md) |
| 理解平台中的对象 | [核心概念](concepts.md) |
| 让多个 Agent 协作 | [Issue 与 Team](teams.md) |
| 连接运行 Coding Agent 的电脑 | [Runtime Host](runtime-host.md) |
| 把已有应用接入控制面 | [SDK 与应用接入](integrations.md) |
| 在 Kubernetes 部署 | [Helm 部署](kubernetes.md) |
| 维护已有安装 | [备份、升级与恢复](operations.md) |

## 部署包含什么

Gateway 提供公共入口；Control Plane（`aistiod`）提供 API 和 Dashboard；Dataplane 执行托管 Harness 会话；Scheduler 处理渠道、定时和工作调度。PostgreSQL 保存持久状态，工作目录与产物使用持久卷。

服务本身不附带模型额度，也不替你安装外部 Coding Agent。部署与执行环境是两个步骤：服务启动后，还需要配置模型、环境或接入已有运行时。

本文档覆盖完整 Service 的独立 HTTP 部署模式。版本、镜像地址和已验证平台以相应 Release Notes 为准；仓库中的候选版本不代表制品已经公开发布。
