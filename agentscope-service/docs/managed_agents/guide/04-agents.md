# Agents · 控制面定义

[← Quickstart](03-quickstart.md) · [回目录](README.md) · [下一页：Environments →](05-environments.md)

---

**Agent** 是可版本化的配置资源：模型、`system` 提示、工具集、MCP、skills 等。Session 运行时以 **版本快照** 为权威，而不是随时漂移的草稿磁盘状态。

基路径：`/api/agents`。

## 资源模型（Agent body）

创建/更新请求主要字段（`AgentCreateRequest`）：

| 字段 | 说明 |
|---|---|
| `id` | 可选；不传则服务端生成 |
| `name` / `description` | 展示信息 |
| `system` | 系统提示（不再使用旧字段 `sysPrompt`） |
| `model` | 模型名；可回落到全局默认 |
| `maxIters` | 最大迭代 |
| `tools` | `List<AgentToolset>`，见下 |
| `mcpServers` | MCP 服务声明 |
| `skills` | `SkillRef` 列表（workspace / marketplace） |
| `multiagent` / `metadata` | 可选扩展 |
| `templateId` | 从模板脚手架创建 |
| `workspacePath` | 可选覆盖工作区路径 |

### tools[]

每个 toolset：

```json
{
  "type": "agent_toolset",
  "defaultConfig": {
    "enabled": true,
    "permissionPolicy": { "type": "always_allow" }
  },
  "configs": [
    {
      "name": "write_file",
      "enabled": true,
      "permissionPolicy": { "type": "always_ask" }
    }
  ]
}
```

| `type` | 含义 |
|---|---|
| `agent_toolset` | 内置 / 工作区工具 |
| `mcp_toolset` | 绑定某个 MCP（`mcpServerName`） |

`permissionPolicy.type`：`always_allow` | `always_ask` | `deny`。  
`always_ask` 会在运行时把 Session 置为 `requires_action`，等待 [Events](07-events.md) 中的 `user.tool_confirmation`。

写入 Agent 时，服务端会把 toolsets **派生**到工作区 `tools.json`；**版本快照**才是 Session 的权威来源。设计说明见 [API_REFACTOR.md](../WIP/API_REFACTOR.md)。

> 已删除：`GET/PUT /api/agents/{id}/tools/config`、`GET …/tools/active`。请改 Agent body，勿再调附属配置 API。

## 主要 API

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/api/agents` | 列表（含可见分享） |
| `POST` | `/api/agents` | 创建（生成 version） |
| `GET` | `/api/agents/{id}` | 详情 |
| `PUT` | `/api/agents/{id}` | 更新（新 version） |
| `POST` | `/api/agents/{id}/archive` | 归档 |
| `DELETE` | `/api/agents/{id}` | 删除 |
| `GET` | `/api/agents/{id}/versions` | 版本列表 |
| `GET` | `/api/agents/{id}/versions/{version}` | 版本快照详情 |

工具发现（非配置写入）：

- `GET /api/agents/{id}/tools/catalog/builtins`
- `GET /api/agents/{id}/tools/catalog/mcp-servers`

Skills / workspace / shares：`/api/agents/{id}/skills/*`、`…/workspace/*`、`…/shares`（见 [MANAGED_AGENTS_API.md](../WIP/MANAGED_AGENTS_API.md)）。

## 版本与 Session

- 每次成功 create/update 会固化一版快照。
- 创建 Session 时可：
  - `"agent": "<id>"` → 通常跟最新版；
  - `"agent": { "id": "<id>", "version": 3 }` → pin；
  - 带 overrides 的对象形态（部署 / IM 桥接也会用）。

运行时按 `(owner, agent, version, environment, mounts)` 解析 HarnessAgent，因此 pin 的版本与 Environment 会真实影响提示词与文件系统拓扑。

## HITL 与权限

1. 在 `tools[].configs[].permissionPolicy` 设为 `always_ask`。
2. Turn 中工具触发确认 → Session `requires_action`，事件含待确认信息。
3. 客户端 `POST …/events`：`user.tool_confirmation`（推荐字段 `tool_use_id`）。
4. 确认后会话继续 `running`。

中断进行中的 turn：投递 `user.interrupt`。
