---
title: "Memory：维护共享知识"
description: "创建知识 Store，将它绑定到 Managed Agent 或 Session，并验证只读工具访问和知识更新。"
en_link: /v2/en/service/memory
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Memory Store 保存多个任务可以复用的知识文档，例如产品术语、操作说明和已核对的事实。创建 Store 并写入内容后，还需要把它的 ID 加入 Agent 的 `defaultMemoryStoreIds`，或在创建 Session 时通过 `memoryStoreIds` 选择它。运行中的 Agent 通过 `memory_store_list` 和 `memory_store_read` 按需读取这些文档，正文不会自动出现在每次模型请求中。

共享知识与会话记录分别管理。当前 Managed HarnessAgent 将共享 Store 按只读方式挂载，文档的新增和修改由有权限的用户或业务后端通过管理 API 完成。Agent 在会话里形成的工作笔记、聊天历史和任务产物不会自动写回 Store；可复用的行为步骤则更适合放入 [Workspace / Skills](/v2/zh/service/workspaces)。

下面沿用[部署指南](/v2/zh/service/quickstart)中的平台身份变量和可用的 `ENVIRONMENT_ID`，先创建一个有明确来源的术语文档，再通过一个 Managed Agent 验证它确实被读取。

## 创建 Store 并写入知识

下面先创建“产品知识”Store，并把项目对 Lark 的定义写入 `product/glossary.md`。`expectedVersion: 0` 表示这个路径应当还不存在，能够避免重复练习时意外覆盖已有正文。保存响应中的 `STORE_ID`，后面用它建立资源关联。

```bash
STORE_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/memory-stores" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"产品知识","description":"已核对的术语与说明"}')
STORE_ID=$(printf '%s' "$STORE_JSON" | jq -er '.id')

curl --fail-with-body -sS -X PUT \
  "$BASE_URL/api/memory-stores/$STORE_ID/memories/product/glossary.md" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"content":"本项目将 Lark 定义为周报归档任务。来源：项目术语表。","expectedVersion":0}' \
  | jq '{id, path, headVersion}'
```

Store 和文档创建响应都是对象，ID 分别为 Store 的 `id` 和文档的 `id`。把已核对、可复用的结论放入共享知识，并记录来源；不要将未经确认的推测当作事实维护。

## 绑定与首次验证

Store 绑定和工具配置需要一起生效：绑定决定 Agent 可以访问哪份知识，工具决定它通过什么操作读取内容。下面创建一个专门查询知识的 Managed Agent，把 `STORE_ID` 设为默认知识来源，并在定义中显式启用两个只读工具。若要复用已有 Agent，可按[默认资源配置](/v2/zh/service/managed-agent-configuration#设置-agent-的默认资源)更新 `defaultMemoryStoreIds`，再按[工具指南](/v2/zh/service/tools#将配置保存到-agent)保留其他工具并启用这两个操作，无需另建 Agent。

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" --arg store "$STORE_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"knowledge-assistant", displayName:"知识查询助手",
      binding:{kind:"managed"},
      definition:{name:"知识查询助手", defaultEnvironmentId:$env,
        defaultMemoryStoreIds:[$store],
        system:"使用已绑定知识文档回答问题。先查找并读取相关来源，回答时注明文档路径；未找到时明确说明，不根据名称猜测含义。",
        tools:[{type:"agent_toolset", defaultConfig:{enabled:false}, configs:[
          {name:"memory_store_list",enabled:true,permissionPolicy:{type:"always_allow"}},
          {name:"memory_store_read",enabled:true,permissionPolicy:{type:"always_allow"}}
        ]}]}
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '.definition | {version, defaultMemoryStoreIds, tools}'
```

创建成功后，`definition.defaultMemoryStoreIds` 应包含这个 Store。`agentKey` 为 `knowledge-assistant`；重复练习时可以保留已返回的 `AGENT_ID`，或更换 key 创建另一个 Agent。接着创建 Session，省略 `memoryStoreIds`，让它继承刚才的默认绑定。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" '{target:{type:"agent",id:$agent}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf '%s' "$SESSION_JSON" | jq '{id, target, memoryStoreIds}'
```

检查响应中的 `memoryStoreIds` 是否包含实际的 `STORE_ID`，再提交“请读取项目术语表，解释 Lark 并注明来源路径”的任务。按照[会话指南](/v2/zh/service/session-event-log)观察 `$SESSION_URL` 的快照和事件，应能看到知识工具读取 `product/glossary.md`，并在最终答案中保留“周报归档任务”这一事实。只有模型回答了相似内容，却没有相应读取记录，不能证明它使用了 Store。

如果只希望某一次会话使用这个 Store，也可以在创建 Session 的请求中显式传入 `memoryStoreIds: ["实际的 Store ID"]`。这个数组替换该次会话的完整知识列表，不会追加到 Agent 的默认列表；需要同时使用多个 Store 时，应传入所有需要的 ID。省略字段时继承 Agent 默认值，`[]` 则表示本次不挂载默认 Store。已有 Session 不会因为 Agent 默认列表被修改而自动改变自己的选择。

## 读取与写入权限

当前 Managed HarnessAgent 运行路径将共享知识挂载为 `read_only`，适合在多次任务中查询受维护的资料。即使工具目录中存在 `memory_store_write` 或 `memory_store_edit`，启用这些工具也不会突破挂载的只读限制。Environment 的 `config.memoryAccess` 接受 `read_only` 和 `read_write`，但将它改为 `read_write` 不会让当前 Managed 路径获得共享知识写入能力。

知识维护使用本页的管理 API，并要求相应资源编辑权限。这与 Agent 的运行时只读访问是两种操作：业务后端可以先核对资料，再执行文档 PUT；Agent 负责在任务中读取。若希望把任务产出纳入共享知识，应由应用明确完成审核和写入，而不是把模型讨论过的内容自动当成已保存的知识。

`memory_store_list` 和 `memory_store_read` 通过平台访问已绑定的 Store。即使文件和 Shell 工具运行在 self_hosted Worker 中，这些知识读取也不会变成 Worker 本机目录访问。只在指令里写出 Store 名称、磁盘路径，或把读取工具打开，都不能代替通过资源 ID 建立绑定。

## 更新与移除

修改文档时，先读取当前正文和 `headVersion`，再用文档 PUT 提交新内容及相同的 `expectedVersion`。版本不匹配时重新读取并合并，避免覆盖其他维护者的修改。需要清除历史敏感内容时使用 Redact；归档会使后续资源解析或读取无法继续使用这个 Store，删除则会移除文档及历史。维护前应检查消费者并按数据保留要求备份。

Session 固定的是 Store ID，并未把共享正文冻结成会话快照。管理 API 更新文档后，可以在同一 Session 中提交新任务，明确要求再次读取相同路径，核对工具是否返回了新内容。此前已经引用到消息或产物中的旧文本不会自动重写，因此应区分“新一次读取结果”和“会话里已有的结论”。

如果 Agent 未读取预期知识，检查 Store 绑定、是否归档、内容路径和工具能力，再用新会话验证。仅在指令中写出 Store 名称不会建立资源绑定。

下一步：[Managed Agent](/v2/zh/service/index#managed-agent) · [Vault](/v2/zh/service/vault)。

动手练习：先用[CRM 方案交付](/v2/zh/service/cases/in-product-delivery)的内联资料验证来源引用与缺失信息处理，再把三份来源迁移到 Memory，验证授权读取和资料更新。

## 控制台查看

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/memory.png" alt="Memory Store 与其中的记忆文件" />
</Frame>

在 **Resources → Memory** 维护 Store 和文档，再到 Managed Agent 的 **Runtime configuration → Session defaults → Default memory stores** 选择默认知识来源。创建新会话时检查 Memory 选择；资源维护页面中的文档存在，不代表所有 Agent 都已经绑定了它。

## 管理 API 与版本

请求使用平台用户 Bearer token 和 `X-AgentScope-Tenant`、`X-AgentScope-Namespace`，变量准备见[部署准备](/v2/zh/service/quickstart)。读取需要资源 inspect 权限，修改需要 edit，创建需要空间资源创建权限；列表只返回当前身份可检查的 Store。

下表 `path` 是 Store 内的文档路径，例如 `product/glossary.md`。URL 按路径段编码，保留目录分隔符。`versions/` 是读取历史的保留前缀，不应用作文档路径前缀。

| 操作 | API | 参数与响应 |
| --- | --- | --- |
| 列出、创建 Store | `GET/POST /api/memory-stores` | GET 返回数组；POST 传 `name`、可选 `description`，返回 `id`、名称、说明与时间戳 |
| 读取、删除 Store | `GET/DELETE /api/memory-stores/{id}` | GET 返回资源对象；DELETE 返回 204 并删除其文档 |
| 归档 | `POST /api/memory-stores/{id}/archive` | 返回 `id`、`archivedAt` |
| 列出文档 | `GET /api/memory-stores/{id}/memories` | 返回包含 `path`、`content`、`headVersion` 的文档数组 |
| 读取、写入文档 | `GET/PUT /api/memory-stores/{id}/memories/{path}` | PUT 传 `content`、可选 `expectedVersion`；返回文档及新 `headVersion` |
| 版本历史 | `GET /api/memory-stores/{id}/memories/versions/{path}` | 返回按版本倒序排列的 `memoryId`、`version`、`content`、`createdAt` 数组 |
| 删除文档 | `DELETE /api/memory-stores/{id}/memories/{path}` | 删除正文与版本历史，返回 204 |
| 脱敏 | `POST /api/memory-stores/{id}/redact` | `path`、可选 `replacement`；替换正文并清除旧历史，默认替换为 `[REDACTED]` |

Store 列表支持 `limit`（1–500）、`offset`（须与 limit 一起使用），总数在 `X-Total-Count`。当前没有 Store 名称或描述的 PATCH 接口。

文档每次 PUT 都生成新版本。建议新建时传 `expectedVersion:0`，更新时先读取并传入当前 `headVersion`；版本已变化则返回 409，重新读取后再合并。省略条件表示允许覆盖当前正文。普通更新保留旧版本，Redact 会移除旧版本中的敏感内容，但不会自动修改此前已经复制到会话、产物或其他系统的文本。
