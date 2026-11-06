---
title: "工具、MCP 与权限"
description: "在 Agent 定义中声明内置工具和 MCP 连接，保存配置后通过 Session 使用，并配置认证与调用权限。"
en_link: /v2/en/service/tools
---

工具让 Managed Agent 能够读取文件、执行命令和访问业务系统。要让某个 Agent 使用这些能力，需要把工具配置保存到它的 **Agent 定义**中。随后，应用使用这个 Agent 的 ID 创建 Session，Service 就会按照该 Session 选定的定义版本，为运行中的 HarnessAgent 装配工具。应用提交任务时无需重新声明工具。

本页说明如何完成这条配置路径。开始前，请先按[部署指南](/v2/zh/service/quickstart)准备 `BASE_URL`、`TOKEN`、`TENANT`、`NAMESPACE` 和 `ENVIRONMENT_ID`，并确认所选环境可用。只想先体验文件读写时，可以先完成[第一个托管 Agent](/v2/zh/service/create-managed-agent)；需要连接自己的业务系统时，再继续下面的 MCP 配置。

## 工具配置与 Agent 的关系

一个 Managed Agent 的 `definition` 中，`tools` 决定它能够使用哪些工具，以及调用时是否需要确认。其中，`agent_toolset` 配置 HarnessAgent 提供的内置工具，`mcp_toolset` 配置某个 MCP 服务提供的工具。两种工具集可以同时放在同一个 `tools` 数组中，因此一个 Agent 可以先查询业务系统，再用文件工具整理结果。

MCP 还需要 `definition.mcpServers` 描述如何连接服务。连接中的 `name` 是这个 Agent 内部的连接标识，`mcp_toolset.mcpServerName` 引用相同的名称，将工具选择和权限策略关联到该连接。例如，连接名为 `catalog` 时，对应工具集也应填写 `mcpServerName: "catalog"`。这两个字段都属于同一个 Agent 的定义，不需要先创建独立的 Tool 资源再按 ID 绑定。

在 API 中，创建 Agent 时将这些字段放在 `POST /api/v1/agents` 请求的 `definition` 内；修改已有 Agent 时，则通过 `PATCH /api/v1/agents/{agentId}/definition` 保存。工具目录和 MCP 目录可以帮助你选择配置，但查看目录或部署一个 MCP 服务本身，都不会改变某个 Agent 的定义。

下面的配置方式适用于 Service 托管的 Managed Agent。External Agent 的工具由外部应用管理，Hosted Agent 则需要遵循其运行时提供方支持的工具映射。当前 Managed 定义 API 接受 `agent_toolset` 和 `mcp_toolset` 两种工具集；如果希望它调用自己的业务函数，可以将函数暴露为 MCP 工具，再通过 `mcpServers` 连接。

## 准备内置工具与 MCP 配置

下面使用一个资料助手说明完整配置。它可以通过产品目录 MCP 查询产品，也可以在执行环境中读写文件。请先准备一个实际提供 `lookup_product` 工具的 MCP 服务，将下面的 URL 替换为其真实地址，并把 JSON 保存为终端当前目录中的 `agent-definition.json`。如果你的服务使用其他工具名，应同时修改 `configs` 中的名称和后面验证任务的要求。

这个文件保存 Agent 的行为定义，下一节会把它作为创建请求的 `definition` 提交给 Service。示例为两个工具集分别设置了 `defaultConfig.enabled: false`，然后显式启用需要的工具。其中读取文件和查询产品可以直接执行，写入文件则需要用户确认。

```json
{
  "name": "产品资料助手",
  "system": "使用产品目录中的实际数据回答问题。需要文件交付时，整理结果并在写入后读取核对；查询失败或信息缺失时如实说明。",
  "maxIters": 20,
  "mcpServers": [
    {
      "name": "catalog",
      "transport": "http",
      "url": "https://YOUR_MCP_HOST/mcp",
      "required": true,
      "initializationTimeout": "PT30S",
      "timeout": "PT30S"
    }
  ],
  "tools": [
    {
      "type": "agent_toolset",
      "defaultConfig": {"enabled": false},
      "configs": [
        {"name": "read", "enabled": true, "permissionPolicy": {"type": "always_allow"}},
        {"name": "write", "enabled": true, "permissionPolicy": {"type": "always_ask"}}
      ]
    },
    {
      "type": "mcp_toolset",
      "mcpServerName": "catalog",
      "defaultConfig": {"enabled": false},
      "configs": [
        {"name": "lookup_product", "enabled": true, "permissionPolicy": {"type": "always_allow"}}
      ]
    }
  ]
}
```

<span id="配置内置工具"></span>

### 内置工具如何选择

`agent_toolset` 的 `defaultConfig` 为内置工具设置默认启用状态，`configs` 再覆盖指定工具。设置为 `false` 后，示例只在这个内置工具集中启用 `read` 和 `write`，不会因此关闭另一个 `mcp_toolset` 中的产品查询工具。如果希望使用 Shell 或查找文件，可以在同一组 `configs` 中增加相应条目。

| 配置中的工具名 | 执行记录中的工具名 | 用途 |
| --- | --- | --- |
| `read` / `write` / `edit` | `read_file` / `write_file` / `edit_file` | 读取、创建和修改文件 |
| `glob` / `grep` / `list_dir` | `glob_files` / `grep_files` / `list_files` | 查找文件和内容 |
| `bash` | `execute` | 在支持 Shell 的 Environment 中运行命令 |

工具启用后，还需要相应的执行资源。例如，文件工具操作的是所选 [Environment](/v2/zh/service/environments) 的工作目录；Local 在 Dataplane 内执行，Sandbox 和 self_hosted Worker 使用各自的文件与依赖，remote 文件环境不提供 Shell。Agent 指令中出现某个路径或命令，并不会自动挂载文件、安装程序或授予系统权限。

<span id="连接业务-mcp"></span>

### MCP 连接与工具名如何对应

示例中的 `catalog` 用来关联连接和工具集，`lookup_product` 则必须是 MCP 服务实际提供的工具名。`configs` 中填写服务返回的原始名称；运行时会为 MCP 工具加上连接名前缀，因此在执行记录中会看到 `catalog__lookup_product`。不要把这个带前缀的运行时名称再填回 `configs`。

同一个 Agent 的连接名称必须唯一，长度为 1–64 个字符，可使用字母、数字、连字符和下划线，但不能包含连续两个下划线。新增连接时，应同时为它配置匹配的 `mcp_toolset`；重命名或删除连接时，也要一起更新引用它的工具集。引用未声明连接的工具集会被拒绝。

当 `mcp_toolset.defaultConfig.enabled` 为 `false` 时，只有 `configs` 中明确启用的 MCP 工具可用。如果改为 `true`，该连接的工具将默认启用，包括服务以后增加的工具，再由单项配置排除不需要的工具。通常只需在工具集中维护这份选择；如果还设置了连接级 `enableTools` 或 `disableTools`，这些过滤条件也会限制最终可用的工具。

| Transport | 配置与执行条件 |
| --- | --- |
| `http` | 使用 Streamable HTTP，填写 Dataplane 能访问的 `url` |
| `sse` | 填写与服务端 SSE MCP 协议匹配的 `url` |
| `stdio` | 显式选择 Local Environment，并在 Dataplane 中准备 `command`、`args` 及程序依赖 |

`initializationTimeout` 控制连接初始化等待时间，`timeout` 控制调用等待时间，两者都使用 `PT30S` 这样的时长字符串。`required` 默认为 `true`，表示连接加载失败会阻止本轮任务继续执行；设为 `false` 后，Agent 可以在缺少该连接工具的情况下继续，连接错误仍会记录在会话事件中。保存定义时不会完成一次真实业务调用，因此仍需在 Session 中验证网络、认证和工具结果。

## 将配置保存到 Agent

准备好定义文件后，可以创建新的 Agent，也可以把工具配置应用到已有 Agent。两种方式最终都会将配置保存为该 Agent 的定义版本；请选择与你当前工作相符的方式。

### 创建新的 Agent

下面的命令将 `agent-definition.json` 放入请求的 `definition` 字段，并通过 `defaultEnvironmentId` 绑定已准备好的执行环境。`binding.kind: "managed"` 表示由 Service 使用 HarnessAgent 内核运行这个定义。命令会保存返回的 Agent ID 和定义版本，后面创建 Session 时将使用它们。

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --slurpfile definition agent-definition.json \
    --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"product-assistant", displayName:$definition[0].name,
      binding:{kind:"managed"},
      definition:($definition[0] + {defaultEnvironmentId:$env})
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
AGENT_VERSION=$(printf '%s' "$AGENT_JSON" | jq -er '.definition.version')
printf '%s' "$AGENT_JSON" | jq '{agentId:.agent.id, definition:.definition}'
```

响应的 `definition.tools` 和 `definition.mcpServers` 应包含刚才的配置。至此，工具已经关联到 `AGENT_ID` 对应的 Agent，但还没有开始执行。`agentKey` 是稳定的业务标识；如果这个标识已经存在，应更新已有 Agent，而不是反复调用创建接口来覆盖配置。

### 更新已有 Agent

如果要为已有 Agent 配置工具，请先将 `AGENT_ID` 设为它的平台 ID，再执行下面的命令。这里先读取当前定义，保留名称、指令、模型和资源绑定等可写字段，再用文件中的 `tools`、`mcpServers` 替换对应配置。文件中的名称和指令不会覆盖已有 Agent。

```bash
CURRENT=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$CURRENT" | jq --slurpfile config agent-definition.json '
  .definition | {
    name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
    workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
    defaultVaultIds, defaultMemoryStoreIds, version
  }
  | .tools = $config[0].tools
  | .mcpServers = $config[0].mcpServers
  | if (.workspaceId // "") != "" then
      .workspaceBinding.overrides =
        (((.workspaceBinding.overrides // []) + ["tools", "mcpServers"]) | unique)
    else . end')
SAVED=$(curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED")
AGENT_VERSION=$(printf '%s' "$SAVED" | jq -er '.definition.version')
printf '%s' "$SAVED" | jq '.definition | {version, tools, mcpServers, workspaceBinding}'
```

这个 PATCH 接口的定义字段直接位于请求顶层，不再包一层 `definition`。它也不是对任意字段进行局部合并，因此不能只发送一段 `tools` 配置而省略其他需要保留的字段。`tools` 和 `mcpServers` 都按完整列表保存；如果还要保留已有工具或连接，应先把它们合并到文件中的相应数组。

请求中的 `version` 使用读取到的 `definition.version`，用来检查定义是否被其他维护者修改。成功后会返回新版本；如果收到 `409 Conflict`，应重新读取当前定义，核对差异后再提交。它与目录身份中的 `agent.version` 不同，不能互相替代。

### 已经绑定 Workspace 时

Agent 可以从 [Workspace](/v2/zh/service/workspaces#发布并绑定-agent) 的发布版本继承工具与 MCP 配置。继承关系由 `workspaceBinding.overrides` 决定：其中没有 `tools` 或 `mcpServers` 时，相应字段使用 Workspace 的内容，即使请求中填写了 Agent 自己的配置，也会在解析定义时采用继承值。

因此，上面的更新命令在检测到 Workspace 绑定后，会为 `tools` 和 `mcpServers` 两项增加显式覆盖，让本次配置成为该 Agent 自己的工具配置，同时保留其他覆盖项。这样做后，这两项不会再随该 Agent 后续选择的 Workspace 版本继承更新。如果团队希望继续共享同一套工具，应改为更新并发布 Workspace，再让 Agent 选择新的发布版本。无论采用哪种方式，保存后都应检查返回的定义，确认实际使用的配置来源。

## 创建 Session 时提供运行资源和认证

保存 Agent 定义后，应用只需在创建 Session 时通过 `target` 引用这个 Agent。Service 会固定所选定义版本，其中也包括工具和 MCP 连接配置。下面显式使用刚才返回的 `AGENT_VERSION`；省略 `target.version` 时，会在创建时选择当前定义版本。已有 Session 会继续使用自己保存的版本，所以修改工具配置后，应创建新 Session 验证效果。

MCP 认证则通过 [Vault](/v2/zh/service/vault) 提供。对于示例中的 `catalog` 连接，可以在 Vault 中添加 `static_bearer` 凭据，将 `target` 设置为 `catalog`，或设置为该连接完整的 URL，并把外部服务签发的 token 保存到 `secret`。创建 Session 时通过 `vaultIds` 挂载这个 Vault，运行时才会为匹配连接设置 Bearer 请求头。仅创建 Vault 或保存凭据，不会自动把它提供给这个 Agent 的所有会话。

下面的命令假设你已按 Vault 指南取得 `VAULT_ID`。如果 MCP 服务不需要认证，可将 `VAULT_IDS_JSON` 设置为 `[]`。如果希望后续 Session 默认使用同一组 Vault，也可以把 ID 列表保存在 Agent 定义的 `defaultVaultIds` 中，并省略会话请求中的 `vaultIds`；显式传入 `[]` 则表示本次不挂载默认 Vault。

```bash
VAULT_IDS_JSON=$(jq -n --arg id "$VAULT_ID" '[$id]')
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" --argjson version "$AGENT_VERSION" \
    --argjson vaults "$VAULT_IDS_JSON" '{
      target:{type:"agent", id:$agent, version:$version}, vaultIds:$vaults
    }')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

如果连接需要自定义 Header，可以在 `mcpServers[].headers` 中使用 `${VARIABLE}` 引用已挂载 Vault 中的 `environment_variable` 凭据；OAuth 的授权与保存流程也见 Vault 指南。连接中的 `env` 只传给本地 stdio 进程，不会变成远程 HTTP 认证，也不是 Session 的 Environment 资源。工具凭据与应用调用 Session API 使用的平台 token 或 Application key 分别承担不同的认证职责。

## 在控制台中配置同一个 Agent

在控制台打开目标 Agent，进入 **Definition → Tools & MCP**，即可编辑该 Agent 的工具配置。内置工具区域用于选择工具及其调用权限；在 **MCP connections → Add connection** 中填写连接名称、Transport 和 URL 后，再配置默认启用状态与逐项工具规则。点击 **Save connection** 会同时保存 MCP 连接和对应的工具集，因此后续无需再执行一次单独的“绑定 Agent”操作。

如果页面提示工具来自 Workspace，应先确认是修改共享 Workspace，还是为当前 Agent 设置覆盖；修改权限和继承关系都会影响页面是否允许编辑。配置保存后，通过新建 Session 验证；需要 OAuth 认证时，完成 **Connect account** 或 **Connect GitHub** 流程，并确认对应 Vault 已被会话挂载。工具目录中的条目只是配置起点，仍需具备实际可用的 MCP 服务和认证信息。

<span id="tool-permissions"></span>
<span id="三层不同的权限"></span>
<span id="在-managed-原生会话处理确认"></span>
<span id="发布后的业务应用"></span>
<span id="验收一次确认流程"></span>

## 权限与人工确认

平台访问权限决定谁可以修改 Agent、使用 Environment 或挂载 Vault；工具集中的 `enabled` 决定 Agent 能否选择某个工具；`permissionPolicy` 则决定选中工具后能否执行。这些控制分别生效。例如，Agent 已启用文件写入工具，但它的策略为 `always_ask` 时，仍然需要先取得用户确认。

`always_allow` 允许直接调用，`always_ask` 要求确认，`deny` 拒绝执行。可以在工具集的 `defaultConfig.permissionPolicy` 中设置默认策略，再由 `configs` 中的单项策略覆盖。未显式配置时，内置工具的默认策略为允许，MCP 工具集的默认策略为询问；对业务操作建议像本页示例一样明确配置，便于维护者理解 Agent 的行为。

当示例 Agent 尝试写入文件时，Session 快照的 `required_actions` 会出现确认请求。应用应向用户说明实际工具和参数，再使用返回的 `request_id`，向 `POST /api/v1/agent-sessions/{sessionId}/turns/{turnId}/actions` 提交决定，并跟踪命令回执和任务状态。完整请求见[回答 required action](/v2/zh/service/session-event-log#回答-required-action)。具有交互 scope 的应用密钥不能冒充指定的人工审批人。

工具确认允许的是这次操作，不会扩大操作系统或业务系统中的权限，也不同于 Workflow 步骤审批和 Issue 结果验收。拒绝或取消之后，应检查已经发生的外部操作；取消任务不能保证撤销这些操作。

<span id="验证真实调用"></span>

## 验证关联是否生效

创建 Session 后，可以提交一个范围明确的任务，例如要求查询目录中一个已知产品，并把查询结果整理到 `product-summary.md` 后读回核对。把下面的 `KNOWN_PRODUCT_ID` 替换为你能核验的产品标识；它只是验证输入，不是预置的演示数据。

```bash
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: catalog-check-001' \
  --data '{"message":"请使用产品目录查询 KNOWN_PRODUCT_ID，根据实际结果生成 product-summary.md，写入后读回核对。如果查询失败，请说明原因，不要编造产品信息。"}')
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN" | jq '{tools, required_actions, turns}'
```

任务提交后在后台执行，一次快照可能还没有工具记录。按[会话指南](/v2/zh/service/session-event-log)继续读取快照或订阅事件，确认实际出现 `catalog__lookup_product` 的调用、参数与返回数据；写文件前应出现待确认请求，允许后才继续写入和读回。最终还需核对产品信息与来源是否一致，不能仅凭模型声称“已查询”就判断接入成功。网络重试应沿用同一个幂等键和消息，新任务才使用新的 key。

如果预期工具没有出现，先检查 Agent 返回的定义是否包含工具集、连接名是否对应，以及 Session 是否使用了更新后的版本；如果工具已经尝试连接但报错，再检查 Dataplane 的网络、所挂载的 Vault 和外部系统权限。`required: false` 的连接失败时，Agent 仍可能完成其他工作，因此应同时查看工具结果和会话错误事件。文件写入成功后，如需提供可下载的交付物，再按[文件与产物](/v2/zh/service/files)发布相应引用。
