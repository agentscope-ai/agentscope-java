---
title: "注册 External Agent"
en_link: /v2/en/service/register-agentscope-agent
description: 通过 API 注册自行部署的 Agent，接入运行实例，再参与任务协作或通过 Session API 接受业务调用
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

如果你已经有一个运行中的 Agent 应用，可以把它注册为 **External Agent**。应用继续由你部署和运维，Service 为它分配统一的 `agentId`，用这个身份加入 Team、接受任务，或通过 [Session API](/v2/zh/service/service-api) 供业务调用。

接入分为两件事：**登记 Agent 和实例**，以及**让实例真正接收请求、执行任务并反馈结果**。单独完成注册请求不会把任意 HTTP 服务自动变成可调度的 Agent。

External 可以单独接受 Session 调用，也可以为 Managed 团队提供已有业务能力，无需先创建 Team。先完成[平台准备](/v2/zh/service/quickstart)，再按本页登记应用并验证运行接入；应用进程仍由你负责部署和运维。需要实现或检查接入适配时，可查阅[External SDK 与运行协议](/v2/zh/service/external-agent)。接入验证通过后，再按[创建与运行 Team](/v2/zh/service/create-team)将它加入协作。

## 登记应用与实例

准备 Service 地址、目标 `tenant` / `namespace`、应用名称 `agentKey` 和当前副本的 `instanceKey`。同一个应用的多个副本共用 `agentKey`，分别使用不同的 `instanceKey`；同一副本重启时保留自己的 key。

下面用 curl 展示注册协议。示例只登记身份，因此没有宣告执行能力；接入 SDK 后，应由适配器上报实际实现的能力。

```bash
export SERVICE_URL="http://localhost:8081"

curl -sS "$SERVICE_URL/api/v1/agent-registrations" \
  -H 'Content-Type: application/json' \
  -d '{
    "tenant": "default",
    "namespace": "default",
    "agentKey": "report-service",
    "displayName": "报告助手",
    "instanceKey": "replica-1",
    "framework": "agentscope-java",
    "routingKey": "http://report-agent:18090",
    "capacity": 1,
    "capabilities": []
  }' > registration.json
```

`routingKey` 是控制面能够访问的应用合约地址。容器里的 `localhost` 通常不能代表另一个容器中的应用。

响应包含 `agent`、`binding`、`instance` 和 `registrationCredential`。保存 `agent.id` 作为后续创建 Team 和 Session 的引用；SDK 建立运行连接时还需要 `binding.id`、`instance.id`、`instance.generation` 和注册凭据。不要把返回的凭据或完整响应放进前端页面。

<Note>
当前预览版本的注册接口不校验调用者身份；传入 Bearer token 也不会使这一步获得身份校验。部署时应在受控接入网络或网关内开放此接口。返回的 registration credential 用于后续运行连接，不能把它理解为注册入口已经具备访问控制。
</Note>

## 让实例接入运行协议

正式接入通常由 SDK 完成注册、心跳和运行协议交互，不需要应用每次启动都手写上面的 curl。

Java 应用使用 `agentscope-extensions-controlplane`，通过 `ControlPlane.instrument(agent, config)` 获得 `SessionBridge`。配置 Service HTTP 地址、范围、稳定的实例 key，以及可访问的应用合约地址。完整依赖和接入片段见 [External Agent 参考](/v2/zh/service/external-agent#java添加-http-注册与合约)。HTTP 注册与合约负责目录、查询和适配器支持的命令；接收平台派发的 AgentTask 还需要配置 `AgentTaskStarter`，并连接当前 Java SDK 支持的 ASDP 运行通道。退出应用时关闭 bridge。

Python 的 `agentscope_service.instrument()` 支持 HTTP 运行传输（`transport="http"`，使用 `control_plane_http`），也支持显式选择 gRPC。任务入口由框架适配器的 `handle_agent_task` 实现。选择适配器时先查看[框架与适配能力](/v2/zh/service/external-agent#external-agent-frameworks)，确认所需的命令、事件和任务能力确实可用。

注册请求中的 `capabilities` 是能力声明，不会替你实现执行逻辑。例如，`agent-task` 表示接收平台任务；Java 适配器配置任务 starter 后才声明它，Python 适配器则根据实际任务方法与运行传输配置判断。`session-abort` 表示能够中止当前执行，也必须有对应实现。

## 用 API 确认接入状态

下面的 `TOKEN` 是有权查看目标 namespace 的平台账户访问令牌，与上一步的注册凭据不同。

```bash
AGENT_ID=$(jq -r '.agent.id' registration.json)

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/instances" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/runtime-inventory" \
  -H "Authorization: Bearer $TOKEN"
```

Agent 记录说明逻辑身份已存在；实例记录反映实际副本及其能力。`runtime-inventory` 展示运行通道上报的信息，未上报时返回 `status: "not_reporting"`，不能仅凭注册成功判断实例可以执行任务。

## 验证执行并接入业务

先通过 [Issue API](/v2/zh/service/issues) 给这个 `agentId` 分派一个小任务，验证任务开始、进度、结果与失败回报。每次执行应使用隔离的 Agent 实例，并用任务范围凭据提交评论、交付物和完成状态；只生成一条最终消息不等于完成平台任务。

验证后，可以通过 [Team API](/v2/zh/service/create-team) 把它加入协作团队，或通过 [Session API](/v2/zh/service/service-api) 调用服务。业务应用使用统一的 [Agent API](/v2/zh/service/service-api) 读取结果与 SSE 事件，接入前查询 Session 的 capabilities，判断当前运行后端支持哪些交互操作。

需要通过界面检查 Agent 和实例时，参见 [Console：Agent 管理](/v2/zh/service/console/index#console-agents)。
