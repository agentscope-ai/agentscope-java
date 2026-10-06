---
title: "Managed 定义与会话 API 参数"
description: 创建和更新 Agent 定义、选择模型与资源，配置原生会话的请求参数。
en_link: /v2/en/service/managed-agent-configuration
---

Managed 配置分为 Agent 定义、会话资源和 Dataplane 部署配置。API 创建流程见[创建与测试](/v2/zh/service/create-managed-agent)，本页集中说明请求字段、默认值和更新规则。

## 定义 API

| 操作 | API | 请求与响应 |
| --- | --- | --- |
| 创建身份、定义和运行绑定 | `POST /api/v1/agents` | 提供 `agentKey`、空间、`binding:{kind:"managed"}` 和 `definition`；返回 `agent,binding,policy,definition` |
| 读取定义 | `GET /api/v1/agents/{id}/definition` | 返回 `agentId` 与 `definition` |
| 更新定义 | `PATCH /api/v1/agents/{id}/definition` | 顶层传行为字段和当前定义的 `version`；`name` 必填；返回 `agent,definition` |
| 定义版本列表 / 单版 | `GET /api/v1/agents/{id}/versions`、`GET /api/v1/agents/{id}/versions/{version}` | 分别返回 `versions`、`version`，并包含 `agentId` |

使用平台 Bearer token 和已授权 Namespace。创建时的行为字段位于 `definition` 中；更新时直接放在请求顶层。目录 `agent.version` 与 `definition.version` 分别控制各自更新，不能混用。

## Agent 定义参数

| 字段 | 类型与默认 | 用途 |
| --- | --- | --- |
| `name` / `description` | string；创建可由 displayName 补 name，更新要求 name | 展示名称与职责说明 |
| `system` | string | 稳定职责与行为规则；不保存 secret |
| `model` | string，空值使用部署默认模型 | 模型注册名或 `provider:model` |
| `maxIters` | int，未配置或非正值时按 20 保存 | 推理/工具迭代上限；不是 token 预算，控制台表单范围不代表 API 校验范围 |
| `workspaceId` | string | 关联共享能力资源 ID |
| `workspaceBinding` | object | `version` 选择已发布版本，`overrides` 选择显式覆盖项，`instructions` 保存附加指令；见 [Workspace](/v2/zh/service/workspaces) |
| `workspacePath` | string | 显式工作区路径，是否可用取决于部署和运行环境 |
| `defaultEnvironmentId` | string | 新会话默认工具执行环境；创建 Managed 时按部署规则验证或准备环境 |
| `defaultMemoryStoreIds` | string[] | 新会话默认知识 Store ID |
| `defaultVaultIds` | string[] | 新会话默认工具凭据集合 ID |
| `tools` / `mcpServers` / `skills` | 结构化配置 | 工具策略、连接与技能；结构见[能力参考](/v2/zh/service/managed-agent-capabilities) |
| `multiagent` | 结构化配置 | Agent 内部委派配置，与平台 Team 区分 |
| `version` | 正整数，仅更新使用 | 从最新 definition 读取；冲突后重新读取并审阅 |

定义更新不是任意字段的局部合并。应读取原定义、保留未修改的可写字段，再发出 PATCH，避免清空其他人配置的工具或资源。以下例子沿用[创建指南](/v2/zh/service/create-managed-agent)中的环境变量，仅修改 system：

```bash
DEFINITION=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$DEFINITION" | jq '.definition | {
  name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
  workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
  defaultVaultIds, defaultMemoryStoreIds, version
} | .system = "Read supplied sources. Cite evidence and list open questions."')
curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED"
```

有 Workspace 绑定时，指令和工具覆盖还需遵循 `workspaceBinding.overrides` 与 `instructions` 的规则。Agent key 是目录中的稳定身份，不通过改显示名称来迁移。

## 默认模型与显式模型

标准 Dataplane 包含 DashScope 模型扩展。管理员在 Dataplane 进程或容器中提供 `DASHSCOPE_API_KEY`，通过 `BUILDER_MODEL_NAME` 选择默认模型；标准配置默认值是 `qwen-max`。修改部署变量后需要重启相应组件。

Agent 的 model 留空时使用默认 Model；显式 `dashscope:qwen-max` 通过模型注册表解析。其他 provider 需要发行包包含相应扩展并配置凭据，仅改模型名称不会安装扩展。模型连接、用户登录与 Vault 工具凭据各有用途。

## 会话资源如何选择

`POST /api/v1/agent-sessions` 创建原生 Managed 会话，接受以下常用字段：

| 字段 | 用途 |
| --- | --- |
| `agent` | 要运行的 Agent ID |
| `environmentId` | 本次会话工具环境；省略时使用 Agent 默认绑定 |
| `memoryStoreIds` / `vaultIds` | 会话知识与凭据资源；省略继承默认，空数组表示不挂载该类默认资源 |

```json
{
  "agent": "YOUR_AGENT_ID",
  "environmentId": "YOUR_ENVIRONMENT_ID",
  "memoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "vaultIds": []
}
```

创建响应包含 session `id`，随后提交 turn。资源需对当前身份可用；不要把 Environment key 用作用户 Bearer token。输入、文件、动作、预算和恢复请求体见[Managed 会话 API](/v2/zh/service/session-event-log)，路径索引见 [API 参考](/v2/zh/service/api-reference)。

普通 Chat、Issue 和 Endpoint 使用各自入口解析资源；不要把上述会话 JSON 原样发送给 Invocation。Endpoint release 固定发布契约，修改定义后需按[发布流程](/v2/zh/service/endpoints)更新服务入口，再用新工作验证。

## 一次调整一个层次

先用默认模型验证文本请求，再调整职责和迭代上限；随后绑定 Workspace、Environment、Memory 和 Vault，逐项检查工具行为。模型解析错误应检查 provider 与部署，工具等待应检查 Environment 或待确认事项；增加 `maxIters` 不能修复连接故障。
