---
title: "Vault：为工具提供凭据"
description: "保存工具凭据，通过 Agent 默认值或 Session 挂载 Vault，并验证 MCP 认证与凭据轮换。"
en_link: /v2/en/service/vault
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Vault 保存 Agent 访问外部工具时使用的凭据。要让某次执行使用它，先把凭据保存到 Vault，再把 Vault ID 加入 Agent 的 `defaultVaultIds`，或在创建 Session 时通过 `vaultIds` 指定。运行时解析这些已挂载的凭据，并将它们提供给匹配的 MCP 连接。创建 Vault 或保存 secret 本身，不会自动为某个 Agent 建立认证。

Agent 定义说明连接地址和可用工具，Vault 则保存认证内容，便于同一个 Agent 在不同 Session 中使用不同的授权。它与调用 Service API 的用户 `TOKEN` 或 Application key 分开管理：前者认证 Agent 访问外部系统，后者认证应用访问 Service。Secret 写入后，公开资源接口只返回类型、标签和目标等元数据，不重新返回明文。

下面沿用[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)中的平台身份变量。验证前还需要一个已按[工具指南](/v2/zh/service/tools)配置 MCP 连接的 Managed Agent，并保留它的 `AGENT_ID`。本页使用连接名 `reports` 和变量名 `REPORTS_TOKEN`；请按实际连接调整这些名称和服务 URL。

## 创建 Vault 并添加凭据

先创建一个保存报告服务凭据的 Vault，并保留响应中的 `VAULT_ID`。随后添加的凭据属于这个集合；创建 Session 时挂载的是 Vault ID，轮换某条凭据时使用的则是它自己的 credential ID。

```bash
VAULT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/vaults" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"displayName":"报告服务凭据","metadata":{"purpose":"reports"}}')
VAULT_ID=$(printf '%s' "$VAULT_JSON" | jq -er '.id')
```

准备仅当前用户可读的 `credential.json`，将示例值替换为外部服务签发的 token；不要将真实文件提交到代码仓库：

```json
{
  "type": "environment_variable",
  "label": "Reports token",
  "target": "REPORTS_TOKEN",
  "secret": "YOUR_TOOL_TOKEN"
}
```

```bash
curl --fail-with-body -sS "$BASE_URL/api/vaults/$VAULT_ID/credentials" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data-binary @credential.json
```

保存响应的 credential `id`，后续用它更新、检查或删除。轮换通过 PATCH 同一个凭据的 `secret` 完成；请求不会替你在外部系统签发新 token。

## 配置一个连接

选择与 MCP 连接对应的凭据类型，再建立 Agent 资源绑定和连接配置，最后执行一次只读调用验证认证。

| 类型 | 使用方式 |
| --- | --- |
| Bearer / MCP OAuth | Target 匹配连接名或完整 endpoint URL，包含路径 |
| Environment variable | 只替换 MCP header、环境或 query 中明确引用的 `${VARIABLE}` |
| Generic secret | 只提供存储，不会自动注入任意工具 |

OAuth 内容需要 `access_token`，可按连接需要包含刷新信息。保存凭据本身不意味着外部服务已授予正确权限。

## 为 Managed Agent 绑定凭据

Agent 定义中的 `mcpServers` 声明连接，`tools` 中匹配的 `mcp_toolset` 选择这个连接提供的工具；Vault 则为运行时连接提供认证信息。保存凭据不会自动增加 Agent 的工具，也不会替你建立 MCP 连接。完整的声明与绑定步骤见[工具与 Agent 的关系](/v2/zh/service/tools#工具配置与-agent-的关系)。

如果希望该 Agent 的新会话默认使用这个 Vault，可以将 `defaultVaultIds` 设为包含 `VAULT_ID` 的列表，并按[设置 Agent 默认资源](/v2/zh/service/managed-agent-configuration#设置-agent-的默认资源)中的完整 GET/PATCH 步骤保存。该步骤保留其他定义字段，并携带当前定义版本。列表表示完整的默认集合，需要保留其他 Vault 时应一并包含它们；修改这个默认值不会改变已有 Session 的挂载。

凭据类型对应 API 值 `static_bearer`、`mcp_oauth`、`environment_variable` 和 `api_key`。其中 `api_key` 是通用存储类型，不会自动配置模型认证或注入工具。环境变量类型也不会全局导出到 Dataplane 或任意 Shell 进程。

例如创建 `environment_variable` 凭据，Target 填 `REPORTS_TOKEN`，Secret 填外部服务签发的值。在 MCP 连接中显式引用；下面是连接字段片段，URL 需要替换成你的服务地址：

```json
{
  "name": "reports",
  "url": "https://reports.example.com/mcp",
  "headers": {
    "Authorization": "Bearer ${REPORTS_TOKEN}"
  }
}
```

将这些字段放入该 Agent 的 `mcpServers` 连接，并选择匹配的 HTTP transport。在同一定义的 `tools` 中，使用 `mcpServerName: "reports"` 关联对应的 `mcp_toolset`，并启用所需工具。保存后创建挂载此 Vault 的新 Session，再调用一次只读工具验证。若使用 `static_bearer`，则将 Target 设为连接名 `reports` 或完整 endpoint，由解析器设置 Bearer header，无需再配置同名占位符。一个连接只选一种清晰的认证方式，避免多个凭据竞争同一目标。

## 在 Session 中选择 Vault

Agent 已保存匹配的 MCP 配置后，就可以创建挂载此 Vault 的 Session。下面显式传入 `vaultIds`，因此只影响这次会话，不会修改 Agent 默认值。如果 Agent 已经设置了 `defaultVaultIds`，也可以省略这个字段来继承默认集合；显式传入 `[]` 则表示不挂载默认 Vault。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" --arg vault "$VAULT_ID" \
    '{target:{type:"agent",id:$agent},vaultIds:[$vault]}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf '%s' "$SESSION_JSON" | jq '{id, target, vaultIds}'
```

返回的 `vaultIds` 应包含刚创建的 Vault ID。这只能证明会话已经选择了资源，还需要按[提交一轮任务](/v2/zh/service/service-api#提交一轮任务)中的方法发起真实的只读 MCP 查询来验证认证。按[工具调用验证](/v2/zh/service/tools#验证关联是否生效)检查工具调用、返回数据及会话错误；若外部系统拒绝访问，应检查连接名称或 URL、凭据类型、Header 占位符与实际授权范围。

`vaultIds` 会替换本次会话的完整列表，而不是在 Agent 默认列表上追加。需要组合多个 Vault 时，应传入全部 ID，并避免多个凭据同时匹配同一 MCP 连接或变量名。创建请求中的每个 Vault 都必须是当前身份有权使用的资源。

## OAuth 连接

需要用户在提供商页面授权时，使用 Vault 的 OAuth connection API 管理授权流程。以下路径相对 `/api/vaults/{vaultId}`：

| 操作 | API 与字段 |
| --- | --- |
| 创建、列出连接 | `POST/GET /oauth-connections`；通用连接传 `serverName`、`endpoint`、`authorizationEndpoint`、`tokenEndpoint`、`clientId`、`authMethod`、`scope`，按提供商要求传 `clientSecret`、`resource` 等 |
| 更新配置 | `PATCH /oauth-connections/{connectionId}` |
| 开始授权 | `POST /oauth-connections/{connectionId}/authorize`；返回 `flowId`、`authorizationUrl`、`expiresAt` |
| 查询授权状态 | `GET /oauth-connections/{connectionId}/flows/{flowId}` |
| 确认保存 | `POST /oauth-connections/{connectionId}/flows/{flowId}/complete`；返回 `connected`、`vaultId`、`credentialId` |
| 取消授权、断开连接 | `POST .../flows/{flowId}/cancel`、`POST /oauth-connections/{connectionId}/disconnect` |

浏览器打开返回的 `authorizationUrl` 并完成提供商授权。回调成功后还需由授权发起人调用 complete，把凭据保存进 Vault；创建连接本身不会完成授权。流程使用浏览器绑定 cookie 和发起人身份校验，不应把它改造成无用户确认的后台 token 导入。GitHub 连接使用 `provider:"github"` 并依赖管理员配置的集成，详见 [Integrations](/v2/zh/service/api-reference#integrations)。

## 验证和轮换

`validate` 做本地解密检查，并对 HTTP(S) Target 尝试有时限的可达性探测；不会携带 secret 验证提供商权限。`ok:true` 不代表外部授权有效，还需查看 checks 并完成实际工具调用。轮换前确认外部系统中的新凭据有效，再 PATCH 对应 credential 的 `secret`。Session 保留的是 Vault ID，后续运行时解析会读取可用的凭据；它不会把创建时的 secret 永久冻结到 Agent 定义中。轮换后应提交新的只读调用验证结果，已经发送出去的请求不会因此撤回。删除前查看消费者，避免同时中断多个 Agent。

不把 secret 写进 Instructions、AGENTS.md、聊天或公开示例。Vault 加密数据依赖部署的 master key；管理员备份必须同时保存数据库和原密钥，单独恢复数据库不足以恢复连接。

失败时检查 Target 是否完整匹配、变量是否明确引用、Vault 是否绑定以及外部权限是否有效。不要通过在聊天里直接粘贴 secret 来排障。

下一步：[工具配置](/v2/zh/service/tools) · [备份恢复](/v2/zh/service/operations)。

## 控制台查看

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/vault.png" alt="Vault 与凭据元数据列表" />
</Frame>

在 **Resources → Vault** 创建集合并维护凭据，再到 Managed Agent 的 **Runtime configuration → Session defaults → Default vaults** 选择默认集合。新建会话时还可以调整 Vault 选择。截图只展示演示凭据元数据；资源列表中出现某个 Vault，并不代表当前 Agent 或 Session 已经挂载它。

## 管理 API

请求使用平台用户 Bearer token 和 `X-AgentScope-Tenant`、`X-AgentScope-Namespace`，变量准备见[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)。资源读取需要 inspect、修改需要 edit，创建需要空间资源创建权限。列表按可检查资源过滤，绑定到 Agent 时还会检查依赖访问权限。

| 操作 | API | 参数与响应 |
| --- | --- | --- |
| 列表、创建 | `GET/POST /api/vaults` | GET 返回数组；POST 传 `displayName`、可选 `metadata`，返回 Vault 对象 |
| 读取、修改 | `GET/PATCH /api/vaults/{id}` | `id`、`displayName`、`metadata`、`ownerId`、时间戳；PATCH 修改名称或整体替换 metadata |
| 归档、删除 | `POST /api/vaults/{id}/archive`、`DELETE /api/vaults/{id}` | 归档返回 `id`、`archivedAt`；删除返回 204 |
| 列出、添加凭据 | `GET/POST /api/vaults/{id}/credentials` | POST 传 `type`、`label`、`target`、非空 `secret`；响应不含 secret |
| 更新或轮换凭据 | `PATCH /api/vaults/{id}/credentials/{credentialId}` | 可选 `label`、`target`、`secret`；非空 secret 替换旧值，type 不可修改 |
| 删除凭据 | `DELETE /api/vaults/{id}/credentials/{credentialId}` | 返回 204 |
| 检查凭据 | `POST /api/vaults/{id}/credentials/{credentialId}/validate` | 返回 `ok`、`checks`、`checkedAt` |

Vault 列表支持 `limit`（1–500）和 `offset`（须与 limit 同时提供），总数在 `X-Total-Count`。Vault 与静态凭据 PATCH 当前没有版本条件参数；更新前检查现有元数据，避免并发覆盖。返回的凭据字段为 `id`、`type`、`label`、`target`、`createdAt`。
