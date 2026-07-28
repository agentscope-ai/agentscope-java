# Managed Agents API 改造规划

> 目标：把对外 HTTP 面收敛成「可发布的控制面 + 数据面契约」，便于 CLI / SDK / Console 共用同一套资源模型。  
> 原则：**定义类配置进资源 body 并随版本快照；操作类 / 发现类 / 平台类动作保留子路径。**  
> **未发布产品：不做 deprecated / 双写 / 兼容别名。该删的删，该改的一次改到位（含前端）。**
>
> 对照：[Claude Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview)、现状清单 [MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md)、生产债 [FOLLOW_UP_PRODUCTION.md](FOLLOW_UP_PRODUCTION.md)。
>
> 最后更新：2026-07-21  
> 状态：**后端已落地**（Agent body 收拢、`/tools/config` / `/tools/active` 已删）。前端 Tools/Settings 已改为读写 Agent body（`system` / `tools` / `mcpServers` + `version` 乐观锁）。

---

## 1. 为什么要改

当前 Builder 的 Agent 配置真相分散在多处：

| 来源 | 存什么 |
|---|---|
| `POST/PUT /api/agents` body | name、sysPrompt、tools 列表、skillsAllow/Deny、permissionPolicies 等 |
| `/api/agents/{id}/tools/config` | 工具开关与权限策略（运行时真正吃的一份） |
| `/api/agents/{id}/skills/workspace*` | 已安装 skill 文件与元数据 |
| workspace / agentscope.json 等 | MCP、子代理、其它 harness 配置 |

后果：版本快照不完整、SDK/CLI 要打多次请求才配齐、与 Claude「一次 body 带齐」心智不一致。

改造要把 **Agent 定义** 收进单一 body；只保留真正属于操作 / 发现 / 平台的子路径。

---

## 2. 收拢原则

```text
进 Agent body（版本化）     留在附属 API
─────────────────────     ──────────────────────────────
model / system              workspace 文件读写、upload
tools（toolset + policy）   skills marketplace 浏览 / install 过程
mcp_servers + mcp_toolset   channels bindings
skills（引用 id/version）   shares / ACL
multiagent roster           activity / clone / draft
metadata                    tools/skills catalog（只读发现）
```

**「Agent 是什么」进 body；「怎么编辑磁盘 / 怎么发现 / 怎么接到 IM」走子路径。**

---

## 3. Agent Body 目标形状

对齐 Claude 语义；字段直接用目标名，不保留旧名并存。

```jsonc
{
  "id": "agent_...",
  "name": "Code Reviewer",
  "description": "...",
  "model": { "id": "..." },
  "system": "...",
  "tools": [
    {
      "type": "agent_toolset",
      "default_config": {
        "enabled": true,
        "permission_policy": { "type": "always_ask" }  // always_allow | always_ask | deny
      },
      "configs": [
        { "name": "bash", "enabled": true, "permission_policy": { "type": "always_ask" } }
      ]
    },
    {
      "type": "mcp_toolset",
      "mcp_server_name": "github",
      "default_config": { "enabled": true }
    }
  ],
  "mcp_servers": [
    { "name": "github", "type": "url", "url": "https://..." }
  ],
  "skills": [
    { "type": "workspace", "name": "pr-review" },
    { "type": "marketplace", "id": "...", "version": "1" }
  ],
  "multiagent": {
    "type": "coordinator",
    "agents": [{ "type": "agent", "id": "...", "version": 1 }]
  },
  "metadata": {},
  "version": 3,
  "archived_at": null
}
```

### 字段迁移（替换，不双写）

| 今日（删除） | 目标 |
|---|---|
| `sysPrompt` | `system` |
| `tools` / `toolsAllow` / `toolsDeny` 扁平列表 | `tools[]` toolset 结构 |
| `permissionPolicies` | `tools[].default_config` / per-tool `permission_policy` |
| MCP 散落在 tools.json / workspace | `mcp_servers` + `mcp_toolset`（权威在 version 快照；落盘仅为 harness 派生） |
| `skillsAllow` / `skillsDeny` | `skills[]` 引用 |
| subagents 若进 multiagent 模型 | `multiagent`；否则暂留 workspace 文件 API |

### 版本语义

- body 任一字段变更 → `headVersion++`，完整快照（含 tools / mcp / skills）。  
- Session 的 `agent_with_overrides` 只作用于当次会话，不写回 Agent。  
- 运行时以 **version 快照** 为权威；派生到 workspace 文件可以，但禁止文件侧成为第二真相。

---

## 4. API 增删

### 4.1 直接删除（定义类配置改走 Agent body）

| Path | 处理 |
|---|---|
| `GET/PUT /api/agents/{id}/tools/config` | **删除**；改 `GET/PUT /api/agents/{id}` |
| `GET /api/agents/{id}/tools/active` | **删除**；生效集由 `GET /api/agents/{id}`（或 versions）表达 |

前端 / 任何调用方同一 PR 改掉，不留转发层。

### 4.2 保留（操作 / 发现 / 平台）

| Path | 理由 |
|---|---|
| `/api/agents/{id}/workspace/*` | IDE 式文件操作 |
| `/api/agents/{id}/skills/workspace/install`、marketplace-install | 安装过程；成功后写入 `skills[]` 并 bump version |
| `/api/agents/{id}/skills/repositories*`、`/tools/catalog*` | 只读发现 |
| `/api/agents/{id}/shares*`、`/bindings*` | ACL / 渠道 |
| `/api/agents/{id}/activity`、`/clone`、`/draft` | 审计与生产力 |

Skill **文件** CRUD 可留在 workspace skills 路径；**启用哪些 skill** 只体现在 Agent `skills[]`。

### 4.3 CLI / Console 约定

```text
agents create --file agent.yaml     # 一次带齐 tools/mcp/skills
agents update AGENT_ID --file agent.yaml
agents skills install AGENT_ID ./skill   # 装文件 + 更新 skills[] + bump
```

不提供「单独改 tools 而不走 agents update」的对外 API。

---

## 5. 落地步骤（一次做完，不分期兼容）

同一迭代（或连续短迭代）内完成，**中间态不对外部承诺**：

1. **数据模型**：`AgentDefinition` / create·update request / version 快照改为目标 body；去掉旧字段。  
2. **写入路径**：唯一入口 `AgentSpecService.apply(spec)` → bump version → 派生 harness 文件。  
3. **删除** `tools/config`、`tools/active` 及旧扁平 tools 字段；Session 构建只读 version 快照。  
4. **前端**：Agent 编辑改为整表单一次保存；去掉对已删 API 的调用。  
5. **文档**：同步改 [MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md) 与 OpenAPI，只描述目标面。

### 可同迭代补的契约缺口（仍属 API 改造，非生产债）

| 项 | 说明 |
|---|---|
| Environment update | packages / networking |
| Session update（idle） | 会话级 tools/mcp |
| Files 一等资源 | `/api/files` + session resources |
| Vault credential update / oauth validate | 控制面补齐 |
| list 分页 cursor | 控制面 list |
| 事件 / Worker / 错误模型 | 见 [DATA_PLANE_CONTRACT.md](DATA_PLANE_CONTRACT.md) |

多副本 / Hands RPC 等归 [FOLLOW_UP_PRODUCTION.md](FOLLOW_UP_PRODUCTION.md)。数据面契约补齐归 [DATA_PLANE_CONTRACT.md](DATA_PLANE_CONTRACT.md)。

---

## 6. 风险

| 风险 | 处理 |
|---|---|
| 前后端不同步会短暂打挂 | 同 PR / 同合并火车改 API + UI |
| skill 文件与 `skills[]` 不一致 | install 成功才 bump；失败回滚文件或引用 |
| 全局 agent 不可 edit | 不变；收拢仅可版本化 user agent |
| 快照变大 | 大文件只存引用，不进 JSON 快照 |

---

## 7. 验收

- [ ] 仅 `POST/PUT /api/agents`（完整 body）即可得到可跑 Session 的 Agent。  
- [ ] `GET …/versions/{n}` 完整还原该版 tools / mcp / skills。  
- [ ] 仓库内无 `/tools/config`、`/tools/active` 路由与调用。  
- [ ] Agent DTO / 文档无 `sysPrompt`、`toolsAllow`、`toolsDeny`、`permissionPolicies` 等旧字段。  
- [ ] UI 用单次保存编辑 Agent；`agent.yaml` 可往返 create → get → update。

---

## 8. 相关文档

| 文档 | 职责 |
|---|---|
| [MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md) | 现状清单（改造完成后改为目标面描述） |
| **本文** | 怎么改（body 收拢；直接改造、无兼容负担） |
| [DATA_PLANE_CONTRACT.md](DATA_PLANE_CONTRACT.md) | 事件 / deltas / Worker / 统一错误模型 |
| [FOLLOW_UP_PRODUCTION.md](FOLLOW_UP_PRODUCTION.md) | 多副本 / Hands / 性能等生产后续 |
