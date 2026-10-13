---
title: "Workspace、Skills 与子 Agent"
description: "创建共享能力定义，发布 Workspace 版本并绑定到 Agent，再通过新 Session 验证生效。"
en_link: /v2/en/service/workspaces
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Workspace 用来维护多个 Agent 可以复用的指令、Skills、工具连接和内部子 Agent 定义。先在 Workspace 中准备这些内容，再发布一个版本，并通过 Agent 定义中的 `workspaceId` 和 `workspaceBinding.version` 选择它。Service 会把选中版本的内容解析到该 Agent 的定义中，后续创建的 Session 才会使用这些能力。仅创建 Workspace 或编辑其中的文件，还没有完成与 Agent 的关联。

Workspace 提供的是“如何工作”的定义。[Environment](/v2/zh/service/environments) 提供实际执行文件和 Shell 工具的位置，[Memory](/v2/zh/service/memory) 提供可更新的共享知识，[Vault](/v2/zh/service/vault) 提供工具认证凭据。Session 的输入和输出文件按[文件与产物](/v2/zh/service/files)管理；它们不会因为使用了同一个 Workspace 就自动成为所有 Agent 的共享文件。

下面沿用[第一个托管 Agent](/v2/zh/service/create-managed-agent)的 `AGENT_ID`，为它增加共享的报告核验能力。继续使用 `BASE_URL`、`TOKEN`、`TENANT` 和 `NAMESPACE`，并确认你可以编辑目标 Agent、创建和发布 Workspace。API 的完整路径索引放在本页末尾。

## 创建并维护草稿

下面先创建“报告工作区”，再写入团队共同遵循的 `AGENTS.md`。响应中的 `id` 是后面发布和绑定时使用的 Workspace ID。此时维护的是草稿，修改不会直接替换已发布的版本。

```bash
WORKSPACE_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/workspaces" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"报告工作区","description":"共享报告约定"}')
WORKSPACE_ID=$(printf '%s' "$WORKSPACE_JSON" | jq -er '.id')

curl --fail-with-body -sS -X PUT "$BASE_URL/api/workspaces/$WORKSPACE_ID/file" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"path":"AGENTS.md","content":"事实必须可追溯到来源。输出分别列出来源和待确认事项。"}'
```

创建时会生成初始 `AGENTS.md`，你可以像上面一样更新正文，再添加技能、工具和子 Agent。这个创建请求没有指定 `tools`，平台会使用默认内置工具集；如果希望共享一组受限工具，应在发布前按[工具配置](/v2/zh/service/tools)准备 `tools` 和 `mcpServers`，通过 Workspace 的 `/tools` 接口保存。指令中提到的目录和输入资料仍需在实际执行环境中准备。

<span id="skills"></span>
<span id="添加一个报告核验-skill"></span>
<span id="验证是否实际使用"></span>
<span id="什么时候改用-team-或-workflow"></span>

## 添加 Skills 与子 Agent

使用刚才保存的 `WORKSPACE_ID`，可以把报告核验方法写成一个 Skill，并附上核验清单。下面的接口会保存 Skill 文件，同时把 `report-review` 加入这个 Workspace 的 `skills` 配置。发布后，继承该配置的 Agent 才会把它作为可用技能。

```bash
curl --fail-with-body -sS -X PUT \
  "$BASE_URL/api/workspaces/$WORKSPACE_ID/skills/report-review" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"markdown": "---\nname: report-review\ndescription: Review a report against its supplied evidence.\n---\n\nRead the report and its sources. List unsupported claims, missing dates, and open questions. Never invent sources.\n", "resources": {"checklist.md": "- Every factual claim has a source.\n- Unknown dates remain unconfirmed.\n- Distinguish evidence from suggestions.\n"}}'
```

Skill 名用于资源路径；正文中的 name、description 帮助 Agent 识别用途。`resources` 的 key 使用相对路径。脚本与参考资料可以随 Skill 保存，但所需解释器、程序和网络权限要在 Environment 中准备。

如果通过通用文件接口手动上传 `skills/report-review/SKILL.md`，还需要在 Workspace 的 `skills` 列表中选择它，例如 `{"type":"workspace","name":"report-review"}`。文件已经存在和技能已被选用是两个步骤；本页使用的 Skill 专用接口会同时完成这两项工作。Agent 若对 `skills` 设置了覆盖，则仍以自己的技能列表为准。

### 增加内部 Subagent

如果希望当前 Agent 将专项审阅委派给内部子 Agent，可以通过 `PUT /api/workspaces/{id}/subagents/{name}` 把职责说明和执行指令保存到 Workspace 草稿中。请求使用 `description` 和 `inlineBody`，还可以指定模型、工具和工作目录策略。它和 Skill 一样，需要经过 Workspace 发布、Agent 绑定以及新 Session 的创建，才会进入所选定义；保存成功本身不会启动子任务。

实际发生委派后，可以通过 `GET /api/v1/agent-sessions/{sessionId}/subagents` 查询关联子会话，再读取子会话的快照和事件检查执行结果。子会话有自己的事件游标，不能复用父会话的游标；应根据子会话的执行记录确认任务结束，再核对结果是否回到父任务。用量汇总方式见[预算指南](/v2/zh/service/session-event-log#budgets)。


## 发布并绑定 Agent

准备好草稿后，先发布 Workspace。发布返回的 `version` 是不可变的发布版本，`draftVersion` 则记录它来自哪个草稿版本。把发布版本保存为 `WORKSPACE_VERSION`，后面的 Agent 绑定要引用这个值。

```bash
REVISION_JSON=$(curl --fail-with-body -sS -X POST \
  "$BASE_URL/api/workspaces/$WORKSPACE_ID/publish" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
WORKSPACE_VERSION=$(printf '%s' "$REVISION_JSON" | jq -er '.version')
printf '%s' "$REVISION_JSON" | jq '{version, draftVersion, digest}'
```

接下来将这个发布版本绑定到已有的 `AGENT_ID`。下面的请求先读取并保留 Agent 的其他可写字段，再设置 `workspaceId` 和 `workspaceBinding`。本例将 `overrides` 设为空数组，因此工具、MCP 和 Skills 都继承 Workspace；已有 Agent 的专用指令保存在 `instructions` 中，追加到 Workspace 的共同指令后面。如果需要保留 Agent 自己的工具配置，应在保存前按下一段说明调整覆盖项。

```bash
CURRENT=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$CURRENT" | jq --arg workspace "$WORKSPACE_ID" \
  --argjson revision "$WORKSPACE_VERSION" '
  .definition | {
    name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
    workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
    defaultVaultIds, defaultMemoryStoreIds, version
  }
  | .workspaceId = $workspace
  | .workspaceBinding = {
      version:$revision, overrides:[],
      instructions:(if .workspaceBinding != null then
        (.workspaceBinding.instructions // "") else (.system // "") end)
    }')
SAVED=$(curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED")
AGENT_VERSION=$(printf '%s' "$SAVED" | jq -er '.definition.version')
printf '%s' "$SAVED" | jq '.definition | {version, workspaceId, workspaceBinding, skills, tools}'
```

保存响应中的 `definition.version` 是新的 Agent 定义版本，`workspaceBinding.version` 则是它所引用的 Workspace 发布版本，两者不要混用。PATCH 中的顶层 `version` 用于检查 Agent 定义并发更新；若返回 `409 Conflict`，应重新读取定义，审阅差异后再提交。

`workspaceBinding.overrides` 可以包含 `tools`、`mcpServers`、`skills`。某个字段出现在这个数组中，就使用 Agent 自己的完整配置；未出现时，使用所选 Workspace 版本的配置。例如，使用 `["tools","mcpServers"]` 可以保留该 Agent 的工具与连接，同时继承 Workspace 的 Skills。`instructions` 则始终用于追加这个 Agent 的专用要求。

绑定版本为 `0` 会发布并选择当前草稿，但不会持续跟随草稿。示例显式使用已发布版本，便于检查每个 Agent 采用了什么内容。Workspace 快照包含指令、工具和定义文件，不包含 `sessions`、`memory`、`logs`、`artifacts`、`inputs`、`outputs` 和 `.git` 等执行数据。

## 在新 Session 中验证

完成绑定后，使用返回的 Agent 定义版本创建 Session。Session 请求无需再传 `workspaceId`，因为该 Workspace 的发布内容已经成为所选 Agent 定义的一部分。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" --argjson version "$AGENT_VERSION" \
    '{target:{type:"agent",id:$agent,version:$version}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

随后按[会话指南](/v2/zh/service/session-event-log)向 `$SESSION_URL/turns` 提交报告与来源，并明确要求使用 `report-review` Skill。可以让来源中的某个日期保持“待确认”，检查 Agent 是否加载了核验步骤和清单、是否保留未知信息，以及结果是否遵循 `AGENTS.md` 中的共同约定。工具调用、读取结果与最终回复一起构成验证依据，单看文件存在或模型自述不能确认能力已经生效。

修改 Workspace 草稿后，需要再次发布，并更新 Agent 的绑定版本，再创建新 Session。仅发布新 Workspace 版本不会更新所有消费者，修改 Agent 也不会替换已有 Session 的定义。多人复用时，可先通过 `/api/workspaces/{id}/agents` 查看引用者，再分别安排版本更新。

## 控制台查看

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/workspaces.png" alt="共享 Workspace 列表" />
</Frame>

在 **Resources → Workspaces** 编辑并发布共享内容，然后进入目标 Agent 的 **Definition → Workspace**，选择 Workspace 和发布版本，检查覆盖项及专用指令，点击 **Save definition binding**。资源页面的发布按钮和 Agent 页面的绑定保存分别完成两个步骤；保存后应核对页面显示的 Workspace 版本与 Agent 版本，再创建新会话。

## 哪些内容应该放在这里

| 内容 | 用途 |
| --- | --- |
| AGENTS.md | 项目操作说明与共同约束 |
| Skills | 可复用任务步骤及辅助文件 |
| Tools / MCP 配置 | 声明外部能力连接 |
| Subagents | 专项委派定义 |

可长期共享的知识文档也可放入 [Memory](/v2/zh/service/memory)，密钥放入 [Vault](/v2/zh/service/vault)。工具连接中的凭据使用明确引用，避免提交明文。

## 与执行目录的关系

Managed Agent 通过所选 [Environment](/v2/zh/service/environments) 访问输入、临时文件与输出。Workspace 提供能力定义，Environment 提供实际文件与 Shell 执行位置；创建 Workspace 不会为 Agent 自动启动 Worker 或安装程序。先绑定定义，再在真实 Environment 中验证文件路径和依赖。

编辑前查看依赖此 Workspace 的 Agent；更新后用新任务验证。删除共享 Workspace 前先处理消费者引用。备份时同时保留数据库引用和 Workspace 存储。

## API 与访问范围

请求使用平台用户 Bearer token，并通过 `X-AgentScope-Tenant`、`X-AgentScope-Namespace` 选择范围。读取、修改和发布分别受资源的 inspect、edit、publish 权限约束；创建还需要空间资源创建权限。配置变量见[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)。下文 `{id}` 为响应返回的 Workspace ID，URL 参数需编码。

| 操作 | API | 请求或响应 |
| --- | --- | --- |
| 列表、创建 | `GET /api/workspaces`、`POST /api/workspaces` | GET 返回数组；创建传 `name`、可选 `description`、`tools`、`mcpServers`、`skills`，返回资源对象 |
| 读取、更新、删除 | `GET/PATCH/DELETE /api/workspaces/{id}` | PATCH 支持创建时的配置字段；返回 `id`、`version`、配置与时间戳；删除返回 204 |
| 文件目录 | `GET /api/workspaces/{id}/files` | 返回 `{files:[路径...]}` |
| 读取、删除文件 | `GET/DELETE /api/workspaces/{id}/file?path=AGENTS.md` | GET 返回 `path`、`content`；DELETE 返回 204 |
| 写入文件 | `PUT /api/workspaces/{id}/file` | `{path,content}`，返回 `path` |
| 工具配置 | `GET/PUT /api/workspaces/{id}/tools` | `tools` 与 `mcpServers`；PUT 替换两组配置 |
| Skill | `GET /api/workspaces/{id}/skills`；`GET/PUT/DELETE .../skills/{name}` | PUT 传 `markdown` 和可选 `resources:{相对路径:内容}` |
| 子 Agent | `GET /api/workspaces/{id}/subagents`；`PUT/DELETE .../subagents/{name}` | PUT 传 `description`、`inlineBody`，可选 `model`、`maxIters`、`tools`、`workspaceMode`、`workspacePath`、`sourceAgentId` |
| 发布、查询版本 | `POST /api/workspaces/{id}/publish`、`GET .../revisions` | 发布无需 body，返回 revision；版本列表为 `{items:[...]}` |
| 查看消费者 | `GET /api/workspaces/{id}/agents` | `{items:[{id,name,version}]}` |

Workspace 响应中的 `version` 是草稿版本；当前草稿 PATCH、文件和能力写入没有 `expectedVersion` 条件更新，应避免多个维护者同时覆盖同一配置。发布版本则是不可变快照，同一内容重复发布返回已有 revision。仍被 Agent 引用的 Workspace 删除时返回 409。
