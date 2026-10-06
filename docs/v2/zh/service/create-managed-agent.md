---
title: "Managed Agent：通过 API 创建与测试"
description: 创建托管 Agent、选择执行资源，并通过原生会话 API 验证一次推理。
en_link: /v2/en/service/create-managed-agent
---

Managed Agent 由 Service 运行。应用提交职责和资源配置后，无需自己启动 Agent 进程，就能通过会话 API 使用它，或把它发布为业务服务。本页完成创建和一次文本请求；完整的发布流程见 [API 快速开始](/v2/zh/service/first-session)。页面操作另见 [Console](/v2/zh/service/console/agents)。

## 准备执行资源

管理员应已部署 Service 并配置可用模型。按[身份和空间配置](/v2/zh/service/agents#准备身份和空间)设置 `BASE_URL`、`TOKEN`、`TENANT`、`NAMESPACE`。本页命令使用 `curl` 和 `jq`。

先列出当前身份可使用的 Environment：

```bash
curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  | jq '.[] | {id, name, type}'

export ENVIRONMENT_ID='CHOSEN_ENVIRONMENT_ID'
```

Environment 决定工具在哪里执行，资源配置见 [Environment](/v2/zh/service/environments)。没有指定默认 Environment 时，平台尝试按部署策略选用或准备默认环境；不允许本地执行且没有可运行环境时，创建会失败。生产配置建议明确指定。

## 创建统一 Agent 身份与托管定义

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"notes-assistant", displayName:"资料助手",
      binding:{kind:"managed"},
      definition:{name:"资料助手", maxIters:20, defaultEnvironmentId:$env,
        system:"根据材料整理任务、负责人、期限和待确认事项。缺失的信息标为待确认，不虚构事实。"}
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '{agent, binding}'
```

响应包含 `agent`、`binding`、`policy` 和 `definition`。保存 `agent.id`；`agentKey` 是稳定业务标识，重复创建已有身份不会代替更新定义。这里省略 `definition.model`，使用部署的默认模型；需要覆盖时填写部署支持的模型标识。

只发送名称而不提供运行绑定，不足以得到一个可以推理的 Agent。上述请求同时建立 Managed 绑定和行为定义，创建成功后再验证实际执行。

## 创建会话并提交一轮任务

使用同一个用户身份创建 Managed 原生 session。创建只准备会话，不会自动向模型发送消息：

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" '{agent:$agent}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')

curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H 'Idempotency-Key: notes-check-001' \
  --data '{"message":"小李周五完成安装说明，下周一评审，时间待确认。请整理待办。"}'
```

提交返回 `202` 和 turn ID，只表示后台已接收。使用 `GET /api/v1/agent-sessions/{sessionId}/snapshot` 读取内容和进度，通过 `events/stream` 观察后续事件。检查结果是否保留“评审时间待确认”，并以该 turn 的终态判断任务是否完成。完整快照、SSE 和错误处理代码见 [Managed 会话 API](/v2/zh/service/session-event-log)。

网络重试时沿用同一幂等键和消息；用户提出新任务时换新键。页面刷新只读取已有会话，不要再次发送测试输入。

## 增加能力与发布

纯文本请求通过后，再逐步绑定 Workspace、Memory、Vault 和工具。用 `GET /api/v1/agents/{id}/definition` 读取定义，再带当前 `definition.version` 更新 `PATCH /api/v1/agents/{id}/definition`；保留未修改字段，以免清空其他配置。详细字段见[配置参考](/v2/zh/service/managed-agent-configuration)。

将该 Agent 交给业务应用时，按[发布 Endpoint](/v2/zh/service/endpoints)建立契约。需要分派带验收要求的工作时，使用 [Issue API](/v2/zh/service/issues)；需要其他 Agent 协作时，将它加入 [Team](/v2/zh/service/create-team)。Managed 原生 API 是该运行方式的额外能力，统一调用入口同样支持已就绪的 External 和 Hosted Agent。
