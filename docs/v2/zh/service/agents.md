---
title: "创建、注册与管理 Agent"
description: 用 API 建立统一 Agent 身份、连接运行时，并确认任务和服务能力。
en_link: /v2/en/service/agents
---

Agent 是可以被业务调用、任务分派和团队编排复用的能力定义。先把它接入统一目录，再确认运行时是否就绪，最后提交工作。创建定义本身不会执行模型推理。

本组文档以 API 为主。希望通过页面完成操作时，阅读[控制台中的 Agent 管理](/v2/zh/service/console/agents)。

## 选择如何接入

| 运行方式 | 谁运行 Agent | API 操作 |
| --- | --- | --- |
| Managed | Service 的 Harness 与 Dataplane | `POST /api/v1/agents`，提供 `binding.kind=managed` 和 `definition` |
| External | 自己部署的应用 | SDK 或 `POST /api/v1/agent-registrations` 注册实例；应用提供执行适配 |
| Hosted | Runtime Host 上的 Coding Agent | 查询 `GET /api/v1/agents/runtime-options`，再用 `POST /api/v1/agents` 绑定 `hosted-runtime` |

三种方式共用 Agent 身份和目录。Managed 适合让平台负责推理与工具循环，External 适合保留代码应用，Hosted 适合复用已安装的 Coding Agent。按你的情况继续[创建 Managed](/v2/zh/service/create-managed-agent)、[注册 External](/v2/zh/service/register-agentscope-agent)或[接入 Hosted](/v2/zh/service/connect-hosted-agent)。

## 准备身份和空间

管理 API 使用平台用户 Bearer token。以下示例使用 `curl` 和 `jq`；`BASE_URL` 是 Gateway 地址，`TENANT` 和 `NAMESPACE` 使用账号已获授权的空间，不要将示例值当成平台预设资源。登录方式见 [API 认证](/v2/zh/service/api-reference#认证与范围)。

```bash
export BASE_URL='https://YOUR_SERVICE_HOST'
export TOKEN='YOUR_PLATFORM_USER_TOKEN'
export TENANT='YOUR_TENANT'
export NAMESPACE='YOUR_NAMESPACE'
```

创建请求中的空间字段、查询参数和 `X-AgentScope-Tenant` / `X-AgentScope-Namespace` 请求头应保持一致。调用已发布 Endpoint 的 API key、Runtime Host 凭据和应用注册信息各有用途，不能替代平台管理身份。

## 查询 Agent 并保存稳定 ID

```bash
curl --fail-with-body -sS -G "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE"
```

结果位于 `items`。`agentKey` 是空间中的稳定业务标识，`displayName` 用于展示，`id` 是后续 API 使用的 Agent ID。保存创建或注册响应中的 `agent.id`，不要用显示名称代替它。

目录记录描述身份和生命周期，绑定描述执行位置；一个身份可有多个绑定和实例。创建成功之后，再读取绑定确认接入的是预期运行方式：

```bash
export AGENT_ID='RETURNED_AGENT_ID'
curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/bindings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE"
```

Agent 为 `active` 只说明目录和配置已建立，不保证此刻所有模型、凭据、工具和执行资源都可用。发布前检查 Endpoint readiness，再用一个只读的小任务确认实际执行路径。

## 修改配置与管理生命周期

| 需要修改的内容 | 操作 |
| --- | --- |
| 名称、描述、目录状态 | `GET /api/v1/agents/{id}` → `PATCH /api/v1/agents/{id}` |
| Managed / Hosted 的行为定义 | `GET /api/v1/agents/{id}/definition` → `PATCH /api/v1/agents/{id}/definition` |
| 运行绑定 | `GET/POST /api/v1/agents/{id}/bindings`；从列表中读取目标绑定，再用 `PATCH /api/v1/agents/{id}/bindings/{bindingId}` 更新 |
| 多运行位置的选择策略 | `GET/PUT /api/v1/agent-runtime-policies/{id}` |
| 归档 Agent | `PATCH /api/v1/agents/{id}`，`{"version": CURRENT_VERSION, "status":"archived"}` |

更新前先读取当前版本，按对应接口传入版本条件。行为定义更新应保留未修改的字段；缺省值可能覆盖已有工具、技能或资源绑定。完整配置见各运行方式的参考手册，首次接入无需手工配置复杂路由策略。

绑定更新需要当前绑定的 `version`，并完整提交 `configuration`、`priority` 和 `enabled`。这些字段会整体替换，遗漏 `enabled` 会将绑定停用；仅想修改一项时，也应保留其余字段的原值。

External 的业务行为主要由应用代码维护，不要把修改平台定义当成热更新外部进程。修改已发布能力时，还应更新对应 Endpoint release；已有调用继续遵循自己的发布契约。

## 接入后怎样使用

需要业务系统直接调用时，按[发布 Endpoint](/v2/zh/service/endpoints)建立稳定服务入口，然后通过[统一服务 API](/v2/zh/service/service-api)提交工作、读取快照和事件、处理交互。需要带负责人、讨论和验收的工作时，通过 [Issue API](/v2/zh/service/issues)分派。

多个 Agent 的协作从[创建 Team](/v2/zh/service/create-team)开始；固定流程使用 [Workflow](/v2/zh/service/workflows)。这些操作都引用已有 Agent ID，不需要重新注册一份 Agent。Managed 的会话、文件和 checkpoint 等额外能力见[原生会话 API](/v2/zh/service/session-event-log)。
