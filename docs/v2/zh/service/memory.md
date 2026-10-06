---
title: "Memory：维护共享知识"
en_link: /v2/en/service/memory
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Memory Store 管理可绑定给 Managed Agent 的共享知识文档，适合产品术语、操作说明和稳定事实。应用通过 `/api/memory-stores` 维护内容，Agent 通过挂载后的 memory 工具按需使用。它与 Chat 历史、Session 工作记忆及 Issue 评论不同。

## 管理 API 与版本

请求使用平台用户 Bearer token 和 `X-AgentScope-Tenant`、`X-AgentScope-Namespace`，变量准备见[API 快速开始](/v2/zh/service/first-session)。读取需要资源 inspect 权限，修改需要 edit，创建需要空间资源创建权限；列表只返回当前身份可检查的 Store。

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

## 创建 Store 并写入知识

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

## 控制台查看

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/memory.png" alt="Memory Store 与其中的记忆文件" />
</Frame>

相同资源可以在 **Resources → Memory** 中维护；页面入口见 [Console](/v2/zh/service/console/index)。

## 绑定与首次验证

通过 [Agent 定义 API](/v2/zh/service/managed-agent-configuration)把 Store ID 加入 `defaultMemoryStoreIds`。更新定义前先读取并保留其他字段，同时携带当前定义版本。然后新建 Session，要求列出可用知识文档并解释 Lark，检查 `memory_store_list` 与 `memory_store_read` 的工具记录和来源路径，确认读取了刚写入的内容。

Session API 使用 `memoryStoreIds` 覆盖默认列表；省略时继承默认值，`[]` 表示该会话不挂载默认 Store。仅在 Instructions 中提到一个 Store 名称不会建立绑定。

共享文档按需实时读取，不是把正文固定复制进每个 Session。更新测试事实后要求再次调用读取工具，确认新内容可见；已有对话中曾引用的旧内容不会因此自动改写。资源绑定发生变化时用新 Session 验证。

## 读取与写入权限

Managed Agent 按需要发现和读取已绑定文档；系统不会把整个 Store 自动塞进每次模型提示。Store 默认以 `read_write` 挂载：`memory_store_write` 新建文档，`memory_store_edit` 对已有文档做精确文本替换，并检查并发版本。会话里讨论一个结论不会自动写入共享知识，只有实际写工具或管理 API 调用才会改变内容。

对于只允许查询的产品知识，在该 Session 使用的 [Environment](/v2/zh/service/environments)配置中设置 `memoryAccess`：

```json
{"memoryAccess":{"STORE_ID":"read_only"}}
```

将 `STORE_ID` 换成实际 Store ID；其他配置字段须一并保留。`read_only` 会拒绝运行时写入，`read_write` 允许写入；它不限制有管理权限的业务后端通过上述 API 维护文档。挂载权限与资源绑定发生变化后，用新 Session 验证。

## 更新与移除

普通修改使用文档 PUT；需要删除历史敏感内容时使用 Redact。归档后 Store 不再进入活动挂载，删除整个 Store 会删除其中的文档。先检查消费者，并按你的数据保留要求备份。

如果 Agent 未读取预期知识，检查 Store 绑定、是否归档、内容路径和工具能力，再用新会话验证。仅在指令中写出 Store 名称不会建立资源绑定。

下一步：[Managed Agent](/v2/zh/service/managed-agent) · [Vault](/v2/zh/service/vault)。

动手练习：先用[CRM 方案交付](/v2/zh/service/cases/in-product-delivery)的内联资料验证来源引用与缺失信息处理，再把三份来源迁移到 Memory，验证授权读取和资料更新。
