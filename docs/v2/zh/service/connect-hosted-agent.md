---
title: "连接 Hosted Agent"
en_link: /v2/en/service/connect-hosted-agent
description: 连接 Runtime Host，通过 API 选择运行环境、创建 Hosted Agent 并分派任务
---

<Note>
本页使用 `2.1.0-BETA1` 预发布版本。
</Note>

Hosted Agent 将电脑或服务器上的 Coding Agent 接入平台。Runtime Host 负责启动 provider、准备工作目录和回报执行结果。应用通过 Session API 调用这个 Agent，也可以把它作为 Team 或 Workflow 的执行成员。

主机接入只需准备一次。之后创建多个 Hosted Agent、设置职责和分派工作，都可以通过 API 完成，不需要每个 Agent 单独安装一个 Host。

Hosted 可以独立调用，也可以加入 Managed Lead 协调的团队。Runtime Host 运行 Coding Agent provider；它不是 Managed Agent 的 self_hosted 工具 Worker，也不等于部署整个 Service。先准备[平台](/v2/zh/service/quickstart)，职责边界见[自托管架构](/v2/zh/service/kubernetes#self-hosting)，组合方式见[多 Agent 协作](/v2/zh/service/orchestration)。

## 先让执行主机上线

在目标主机安装并登录要使用的 provider，确认它本身能够完成一次请求，再使用 Go 1.26 或更新版本，通过 `go install` 安装 `v2.1.0-BETA1` 的 CLI 与 Runtime Host，按 [Runtime Host 安装指南](/v2/zh/service/runtime-host) 配置 PATH，然后连接：

```bash
as connect https://agentscope.example.com
as runtime status
as runtime probe
```

CLI 帮你完成身份交换、保存本机配置和启动守护进程。需要自动化接入服务器时，可由有权限的平台账户调用 `POST /api/v1/runtime-host-enrollment-tokens`，传入 `tenant`、`namespace` 获取短期 `enrollmentToken`，交给目标主机的 `AGENTSCOPE_ENROLLMENT_TOKEN` 后再执行 connect。主机身份交换和运行协议也有 API，普通业务应用无需自行实现这些守护进程逻辑。

## 查询可用运行环境

以下示例使用有权管理目标 namespace 的平台账户令牌 `TOKEN`。将 Service 地址和范围替换为你的实际配置：

```bash
export SERVICE_URL="http://localhost:8081"

curl -sS "$SERVICE_URL/api/v1/agents/runtime-options?tenant=default&namespace=default" \
  -H "Authorization: Bearer $TOKEN"
```

响应中的 `runtimes` 是可选运行环境，每项包含 `provider`、`runtimeProfileId`、`runtimePoolId`、`hostCount` 和 provider 声明的能力。选择符合任务要求的一项，保存两个 ID。`profiles` 和 `pools` 同时返回配置详情，分别表示如何启动 provider、以及到哪组主机执行。

如果 `runtimes` 为空，先检查 Host 是否在线、provider 是否被成功探测。也可以通过下面的接口查看主机状态：

```bash
curl -sS "$SERVICE_URL/api/v1/runtime-hosts?tenant=default&namespace=default" \
  -H "Authorization: Bearer $TOKEN"
```

支持的 provider 及 Workspace、工具和恢复能力差异，见 [Provider 参考](/v2/zh/service/hosted-agent-configuration#hosted-agent-providers)。

## 创建 Hosted Agent

创建 Agent 时使用统一的 `POST /api/v1/agents`，将运行绑定设为 `hosted-runtime`。`definition` 描述名称、指令等 Agent 能力；profile 和 pool 决定它如何、在哪里执行。

将请求中的两个占位值替换为上一步返回的 UUID：

```bash
curl -sS "$SERVICE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "tenant": "default",
    "namespace": "default",
    "agentKey": "code-reviewer",
    "displayName": "代码审查助手",
    "binding": {
      "kind": "hosted-runtime",
      "configuration": {
        "runtimeProfileId": "<runtime-profile-id>",
        "runtimePoolId": "<runtime-pool-id>"
      }
    },
    "definition": {
      "name": "代码审查助手",
      "system": "依据提供的材料进行代码审查，说明证据和建议，不擅自修改文件。"
    }
  }' > hosted-agent.json
```

响应返回 `agent`、`binding`、`policy` 和 `definition`。后续使用 `agent.id` 引用该 Agent。示例未指定 model，交给 provider 的默认配置；其他定义字段是否能够应用到原生 provider，应以它的能力声明为准。

```bash
AGENT_ID=$(jq -r '.agent.id' hosted-agent.json)

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/bindings" \
  -H "Authorization: Bearer $TOKEN"
```

需要调整指令时使用 `PATCH /api/v1/agents/{agentId}/definition`；provider 执行选项位于 `GET/PATCH /api/v1/agents/{agentId}/hosted-settings`。更新前读取当前配置及版本，字段说明见 [Hosted Agent 参考](/v2/zh/service/connect-hosted-agent#hosted-agent)。

## 分派工作并读取结果

先通过 [Issue API](/v2/zh/service/issues) 创建一项只读任务，设置 `assigneeType: "agent"`、`assigneeRef: agent.id`。例如在描述中提供 README 文本，请 Agent 给出三条文档改进建议。平台会创建 AgentTask 并异步派发，不需要业务代码直接调用 Host 的领取接口。

从 Issue、任务和 ExecutionAttempt 查询执行状态，读取结果评论与 Artifact。任务工作目录由 Host 准备，本机已经打开的 Git 仓库不会自动成为任务输入；需要读取项目时，要显式关联 provider 支持的 Workspace，或提供资料。

单任务验证通过后，可以将 Agent 加入 [Team](/v2/zh/service/create-team)，也可以直接创建以它为目标的 Session。Hosted 对话支持的交互由 provider 与平台适配决定，不能假定它具有 Managed 的全部输入、审批或 checkpoint 能力。调用前读取 Session 和 Turn 的 capabilities，接入方式见[服务 API](/v2/zh/service/service-api)。

控制台入口与操作流程见 [Console：Agent 管理](/v2/zh/service/console/index#console-agents)。将 Coding Agent 发布为业务能力的示例见 [故障修复服务](/v2/zh/service/cases/incident-to-pr)。

<span id="hosted-agent"></span>
<span id="本章节"></span>
<span id="准备主机"></span>
<span id="交付一个小任务"></span>
<span id="扩展能力"></span>
<span id="中断与恢复"></span>
<span id="作为服务对外调用"></span>

## 工作目录与交付边界

任务目录由 Runtime Host 管理，不会默认使用你正在编辑的 Git checkout。仓库、分支和输入资料需要明确准备；共享结果应上传为 Artifact。Host 在线不代表 provider 已登录、工具获准或任务能够执行。

Workspace 的指令、Skills 与工具按 provider 能力映射；工具依赖和第三方登录仍在目标主机准备。原生 provider 会话恢复与统一 Turn 的恢复命令不同，显示交互操作前读取调用的 capabilities。
