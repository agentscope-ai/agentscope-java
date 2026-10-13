---
title: "配置 Agent 与模型"
description: 创建和更新 Agent 定义，将工具与 MCP 绑定到 Agent，并为 Session 选择模型与资源。
en_link: /v2/en/service/managed-agent-configuration
---

完成[第一个 Managed Agent](/v2/zh/service/create-managed-agent)的验证后，可以根据任务需要继续完善它的能力。Agent 使用的模型、稳定指令和工具保存在定义中；需要访问外部系统时，按[工具与 MCP](/v2/zh/service/tools)声明连接并绑定工具，需要复用处理方法时，则通过[Workspace 与 Skills](/v2/zh/service/workspaces)提供指令和技能。保存后创建新的 Session，验证这些配置是否真正用于任务执行。

本页是“配置 Agent 与资源”的入口，集中说明 Agent 定义的字段、默认值和更新规则，并解释资源如何与 Agent 和 Session 关联。会话所需的运行资源可以继承 Agent 默认值，也可以在创建 Session 时显式选择；Dataplane 部署配置则由服务管理员维护。下面先说明如何读取和修改 Agent 定义，再介绍模型与资源配置。

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
| `tools` | 工具集数组 | `agent_toolset` 选择内置工具，`mcp_toolset` 选择 MCP 工具，并配置调用权限 |
| `mcpServers` | 连接数组 | 声明 MCP 地址和传输方式；由 `tools` 中的 `mcpServerName` 引用连接的 `name` |
| `skills` | 技能配置数组 | 选择可用技能；见 [Workspace / Skills](/v2/zh/service/workspaces) |
| `multiagent` | 结构化配置 | Agent 内部委派配置，与平台 Team 区分 |
| `version` | 正整数，仅更新使用 | 从最新 definition 读取；冲突后重新读取并审阅 |

`tools` 和 `mcpServers` 保存在这个 Agent 的定义中，保存后就完成了工具与 Agent 的关联。应用随后使用该 Agent 的 ID 创建 Session，Service 会从选定的定义版本装配工具，无需在每次任务请求中重复声明。完整的声明、创建与更新示例见[将工具配置保存到 Agent](/v2/zh/service/tools#将配置保存到-agent)；MCP 认证通过 Agent 的默认 Vault 或 Session 的 `vaultIds` 提供。

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

<span id="managed-agent-capabilities"></span>
<span id="从-api-查询并配置能力"></span>
<span id="工具与-mcp"></span>
<span id="skills-与内部-subagents"></span>
<span id="知识凭据与入口"></span>

### 模型接入

| 接入方式 | 需要准备什么 |
| --- | --- |
| 标准 DashScope 默认模型 | 在 Dataplane 配置 `DASHSCOPE_API_KEY` 与默认模型 |
| 显式 DashScope 模型 | 使用 `dashscope:model-name`；部署内有 DashScope 扩展与凭据 |
| 其他 ModelProvider | 自定义 Dataplane 发行包包含对应模型扩展，并配置该 provider 的连接与认证 |
| 自定义模型对象 | 在自定义 Dataplane 中提供 `Model` bean 作为默认值，或用 `ModelRegistry` 注册命名模型/工厂 |

Java SDK 提供 OpenAI 及兼容接口、Anthropic、Gemini、Ollama 等[模型扩展](/v2/zh/integration/model/index)。它们属于可集成能力；标准 Service 镜像不因 SDK 存在扩展就自动包含全部 provider。模型需要支持任务使用的工具调用和输入类型。

## 资源如何关联到 Agent 和 Session

Workspace、Environment、Memory 和 Vault 都是可以复用的资源，但它们进入 Agent 运行过程的方式不同。Workspace 的发布内容被解析到 Agent 定义中，决定指令、技能和工具配置；Environment、Memory 和 Vault 则作为运行资源，由每个 Session 选择。把资源创建出来，只代表平台已经能够管理它，还需要完成下面的关联才能在任务中使用。

| 资源 | Agent 定义中的关联 | Session 中的选择 |
| --- | --- | --- |
| Workspace | `workspaceId` 与 `workspaceBinding.version` 选择发布版本 | 通过 `target` 选择 Agent 定义版本，间接取得其中的 Workspace 内容 |
| Environment | `defaultEnvironmentId` 指定默认执行环境 | `environmentId` 可以为本次会话选择其他环境 |
| Memory Store | `defaultMemoryStoreIds` 指定默认知识来源 | `memoryStoreIds` 可以替换本次会话的 Store 列表 |
| Vault | `defaultVaultIds` 指定默认工具凭据集合 | `vaultIds` 可以替换本次会话的 Vault 列表 |

Agent 的默认资源用于创建新会话，不会在修改定义后自动替换已有 Session 的选择。Session 保存的是资源 ID，不能因此把资源本身当成不可变快照：Memory 文档可以继续更新，Vault 凭据可以轮换，Environment 配置也可以被维护。只有 Workspace 发布内容随 Agent 定义版本固定下来。修改共享资源本身时，应同时考虑其他引用它的 Agent 和 Session。

### 设置 Agent 的默认资源

先在相应资源页面创建 Environment、Memory Store 或 Vault，并取得它们返回的 ID。下面示例将三类资源设为已有 Managed Agent 的默认值；把 JSON 中的占位值换成实际 ID，保存为 `resource-defaults.json`。如果只想修改其中一类，文件中只保留对应字段即可。省略的字段会由下面的命令保留原值；列表中的 ID 则表示完整的默认集合，空数组会清空该类默认绑定。

```json
{
  "defaultEnvironmentId": "YOUR_ENVIRONMENT_ID",
  "defaultMemoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "defaultVaultIds": ["YOUR_VAULT_ID"]
}
```

沿用[创建指南](/v2/zh/service/create-managed-agent)的 `AGENT_ID` 和平台身份变量。命令先读取完整定义，保留其他可写字段，再合入资源默认值并提交定义版本，因此不会因为只修改资源而清空工具或指令。

```bash
CURRENT=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$CURRENT" | jq --slurpfile resources resource-defaults.json '
  .definition | {
    name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
    workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
    defaultVaultIds, defaultMemoryStoreIds, version
  } | . + ($resources[0] | with_entries(select(
    .key == "defaultEnvironmentId" or .key == "defaultMemoryStoreIds" or .key == "defaultVaultIds"
  )))')
curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED" \
  | jq '.definition | {version, defaultEnvironmentId, defaultMemoryStoreIds, defaultVaultIds}'
```

保存成功后，检查返回的资源 ID 和新定义版本，再创建新的 Session。若返回 `409 Conflict`，说明定义在读取后又被修改，应重新读取并核对后再提交。默认绑定只决定运行时可以使用哪些资源；工具是否启用、是否需要确认，以及外部服务是否授予访问权限，仍按各自配置生效。

控制台中，可以在 Managed Agent 的 **Runtime configuration → Session defaults** 选择相同的默认资源。Workspace 则在 **Definition → Workspace** 中绑定发布版本。创建新会话时检查资源选择，便能确认本次使用默认值还是另行选择的资源。

## 会话资源如何选择

通过 `POST /api/v1/agent-sessions` 创建会话时，使用 `target` 指定要运行的 Agent，并按需选择本次会话的资源：

| 字段 | 用途 |
| --- | --- |
| `target` | 使用 `{type:"agent", id:"AGENT_ID"}` 选择 Agent；可通过 `version` 指定定义版本 |
| `environmentId` | 本次会话工具环境；省略时使用 Agent 默认绑定 |
| `memoryStoreIds` / `vaultIds` | 会话知识与凭据资源；省略继承默认，空数组表示不挂载该类默认资源 |

```json
{
  "target": {"type": "agent", "id": "YOUR_AGENT_ID"},
  "environmentId": "YOUR_ENVIRONMENT_ID",
  "memoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "vaultIds": []
}
```

创建响应包含 Session 的 `id`，随后可以向该 Session 提交 Turn。资源需对当前身份可用；不要把 Environment key 用作用户 Bearer token。输入、文件、动作、预算和恢复请求体见[会话指南](/v2/zh/service/session-event-log)，路径索引见 [API 参考](/v2/zh/service/api-reference)。

通过 Session API 创建会话时，Service 固定当时的 Agent 定义和运行配置。更新定义后创建新的 Session，已有会话继续使用自己的版本；需要调整当前会话的环境、Memory 或 Vault 时，使用该 Session 的 PATCH 接口，并遵守相应资源的访问权限。应用接入流程见[服务 API](/v2/zh/service/service-api)。

## 一次调整一个层次

先用默认模型验证文本请求，再调整职责和迭代上限；随后绑定 Workspace、Environment、Memory 和 Vault，逐项检查工具行为。模型解析错误应检查 provider 与部署，工具等待应检查 Environment 或待确认事项；增加 `maxIters` 不能修复连接故障。
