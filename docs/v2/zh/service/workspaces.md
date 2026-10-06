---
title: "Workspaces：共享指令与能力文件"
en_link: /v2/en/service/workspaces
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Workspace 保存可复用的 Agent 资料：`AGENTS.md`、技能、工具和子 Agent 定义。应用通过 `/api/workspaces` 创建、维护并发布这些资源，再把发布版本绑定给 Agent。Workspace 是资源，不是账号的 Namespace，也不是一次执行的临时目录。

## API 与访问范围

请求使用平台用户 Bearer token，并通过 `X-AgentScope-Tenant`、`X-AgentScope-Namespace` 选择范围。读取、修改和发布分别受资源的 inspect、edit、publish 权限约束；创建还需要空间资源创建权限。配置变量见[API 快速开始](/v2/zh/service/first-session)。下文 `{id}` 为响应返回的 Workspace ID，URL 参数需编码。

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

## 创建并维护草稿

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

创建会生成初始 `AGENTS.md`，随后按项目约定更新正文，再逐项添加技能、工具和子 Agent。指令中的目录需要实际准备，写入文档不会自动创建输入资料。

## 发布并绑定 Agent

```bash
REVISION_JSON=$(curl --fail-with-body -sS -X POST \
  "$BASE_URL/api/workspaces/$WORKSPACE_ID/publish" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
printf '%s' "$REVISION_JSON" | jq '{version, draftVersion, digest}'
```

revision 的 `version` 是发布版本，`draftVersion` 是来源草稿版本，`digest` 标识发布内容。快照包含指令、工具和定义文件，不包含 `sessions`、`memory`、`logs`、`artifacts`、`inputs`、`outputs`、`.git` 等执行数据。

在 [Agent 定义 API](/v2/zh/service/managed-agent-configuration)中设置 `workspaceId` 和以下 `workspaceBinding`。更新已有定义时，先 GET 当前定义，保留其他字段，并携带当前定义的 `version`；下面是绑定字段片段：

```json
{
  "workspaceId": "WORKSPACE_ID",
  "workspaceBinding": {
    "version": 1,
    "overrides": [],
    "instructions": "本 Agent 负责周报，输出需包含待确认事项。"
  }
}
```

将 `version: 1` 替换为刚发布的版本。`overrides` 可包含 `tools`、`mcpServers`、`skills`，表示该项采用 Agent 自身定义；空数组表示继承。`instructions` 追加 Agent 专用要求。绑定版本为 0 表示显式发布并选择当前草稿，并不是持续跟随草稿；新建关联而省略绑定时也会解析为确定版本。

修改 Workspace 草稿和更新 Agent 绑定是两个步骤。已有 Session 使用解析后的定义快照，发布新 Workspace 版本不会自动改写它。多个 Agent 复用时分别更新绑定，再创建新会话验证指令、Skill 和工具是否生效。

## 控制台查看

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/workspaces.png" alt="共享 Workspace 列表" />
</Frame>

在 **Resources → Workspaces** 查看相同资源。控制台入口和 Agent 关联操作见 [Console](/v2/zh/service/console/agents)。

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
