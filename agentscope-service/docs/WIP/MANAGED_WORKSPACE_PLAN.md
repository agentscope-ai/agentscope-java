# Managed Agents — Workspace-first 资源模型（M6）

> 状态：`approved for implementation`
> 范围：Build 控制面 + Console + 必要的数据面 resolve/build；**排除** BYO Operate
> 关联：[MANAGED_AGENTS_PRODUCT_PLAN.md](MANAGED_AGENTS_PRODUCT_PLAN.md) · [MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md)
> 策略：组织单元以 Workspace 为准；Claude Managed Agents 仅作配置项命名/形状参考

---

## 1. 设计立场

1. **Workspace 是作者态真相源**：skills、tools（builtin + mcp）、subagents、`AGENTS.md`、knowledge 均在 Workspace 内管理。
2. **Agent 关联 `workspaceId`（N:1）**：创建 Agent 时选择 Workspace；可选薄覆盖（关某 tool 等）。运行/pin 时物化进 Agent version snapshot，避免会话中漂移。
3. **Claude 仅作轻量参考**（[Create Agent](https://platform.claude.com/docs/en/api/beta/agents/create)）：
   - Builtin 主组命名：`bash` / `read` / `write` / `edit` / `glob` / `grep` / `web_fetch` / `web_search`（内部映射 Harness）
   - `default_config` + `configs` + `permission_policy`
   - `mcp_servers` + `mcp_toolset` 成对
   - Skill 的稳定 id + version 引用感
   - **不**采用「无 Workspace、一切只挂 Agent」的组织方式
4. **存储**：Workspace 内容经控制面 API 写入共享存储（DefinitionStore / DB），禁止「仅本机磁盘是真相」。

```
Marketplace(git|nacos) ──install──► Workspace.skills/
Builtin+MCP catalog ──select──► Workspace.tools / mcpServers
Agent ──workspaceId──► Workspace ──materialize──► Agent version snapshot
                              └──► DataPlane HarnessAgentBuild
```

---

## 2. 资源与 API 草图

### 2.1 Workspace

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/workspaces` | 列表 |
| POST | `/api/workspaces` | 创建 `{name, description?}` |
| GET | `/api/workspaces/:id` | 详情 + 摘要（agentsMd / skillCount / …） |
| PATCH | `/api/workspaces/:id` | 更新元数据 / tools / mcp / skills 引用 |
| DELETE | `/api/workspaces/:id` | 删除（无 Agent 引用时） |
| GET/PUT | `/api/workspaces/:id/files/*` | 文件树读写（AGENTS.md 等） |
| GET/PUT/DELETE | `/api/workspaces/:id/skills/*` | workspace skills |
| GET/PUT/DELETE | `/api/workspaces/:id/subagents/:name` | `subagents/<name>.md` + YAML frontmatter |
| GET | `/api/workspaces/:id/tools` | 当前 tools + mcpServers |
| PUT | `/api/workspaces/:id/tools` | 更新 tools 配置 |
| GET | `/api/toolsets/builtin` | 平台 builtin catalog（不绑 agent） |
| GET | `/api/toolsets/mcp-catalog` | 预置 MCP 目录 |

Workspace 行字段（示意）：

```
workspace_id, owner_id, name, description,
tools_json, mcp_servers_json, skills_json,
head_version, created_at, updated_at
```

文件类内容（AGENTS.md、skills 目录、subagents、knowledge）存 DefinitionStore 命名空间 `definitions/{ownerId}/ws_{workspaceId}/`。

### 2.2 Agent 关联

- `agents.workspace_id TEXT`（可空；空则兼容旧「每 Agent 私有目录」路径）
- Create/Update 接受 `workspaceId`
- Version snapshot 物化：从 Workspace 拷贝 `tools` / `mcpServers` / `skills` / `system←AGENTS.md`（Agent 字段非空则覆盖）

合并规则：**Workspace 为底，Agent 非空字段覆盖**。

### 2.3 Marketplace

| Method | Path | 说明 |
|--------|------|------|
| GET/POST | `/api/marketplaces` | Registry CRUD（type=`git`\|`nacos`） |
| GET | `/api/marketplaces/:id/skills` | browse |
| POST | `/api/workspaces/:id/skills/marketplace-install` | `{marketplaceId, skillName, version?}` → 写入 Workspace |

### 2.4 Subagent 文件约定（与 Harness 对齐）

路径：`subagents/<name>.md`

```markdown
---
description: Required short description.
workspace:
  mode: isolated   # isolated | shared
  path:            # optional
model:             # optional
maxIters: 10
tools: []
---

# Inline system prompt when workspace.path is absent
```

---

## 3. Builtin tool 映射（Claude 名 → Harness）

| Product id | Harness tool | Group | Status |
|------------|--------------|-------|--------|
| `bash` | shell / execute | filesystem | available |
| `read` | `read_file` | filesystem | available |
| `write` | `write_file` | filesystem | available |
| `edit` | `edit_file` | filesystem | available |
| `glob` | `glob_files` | filesystem | available |
| `grep` | `grep_files` | filesystem | available |
| `web_fetch` | `web_fetch` | web | native (P3) |
| `web_search` | `web_search` | web | native (P3) |

扩展组（可选）：`memory_*`、`session_*`、`agent_*`、`task_*`、`plan_*`、`skill_*` — group=`harness`。

---

## 4. 里程碑

| Phase | 内容 |
|-------|------|
| P0 | Subagent 路径/frontmatter；写入与 DP 同源；builtin catalog 对齐 |
| P1 | `workspaces` 表 + API + Console Workspace 栏 + `Agent.workspaceId` |
| P2 | git/nacos MarketplaceRegistry；install → Workspace；SkillRef resolve |
| P3 | Harness `web_*`；MCP catalog 去占位 + Vault 提示 |

不打断 M0–M5。本文件为 **M6**。

---

## 5. 附录：Claude 对照（非验收标准）

| Claude | 我们 |
|--------|------|
| tools 挂 Agent | tools 编辑在 Workspace，物化进 Agent snapshot |
| `/v1/skills` 一等资源 | 近期：Workspace skills + marketplace install；远期可升一等 |
| 八件套 builtin | 同名产品 catalog + Harness 映射 |
| multiagent = Agent roster | 保留；轻量子代理用 Workspace `subagents/` |
