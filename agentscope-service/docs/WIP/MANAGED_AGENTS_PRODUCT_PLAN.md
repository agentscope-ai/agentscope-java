# Managed Agents 产品对标规划与实施说明书（控制面本期）

> 状态：`pending approval`（规划 + 实施说明书；未开始编码）
> 范围：**Managed Agents / Build** 控制面 + Console；**排除** BYO Operate（`/api/v1/*`、sessionops、fleet、ASDP）
> 对标主轴：[Claude Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview)
> 辅参照：[Claude Code](https://code.claude.com/docs/en/how-claude-code-works)（作者态 / 扩展面心智）
> 策略：Console 与公开 API 并重，按可演示的端到端故事切片交付
> 文档版本：v2（v1 为纯规划；v2 补齐可直接执行的实施细节与验证结论）
> 关联：[MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md) · [guide/](../guide/README.md) · [12-limitations.md](../guide/12-limitations.md) · [14-validation.md](../guide/14-validation.md)

---

## 0. 如何使用本文档（执行协议 · 实施者必读）

本文档同时是**产品规划**和**实施说明书**。实施者请严格遵守以下协议。

### 0.1 执行顺序

1. 按里程碑顺序执行：**M0 → M1 → M2 → M3 → M4 → M5**。不要跳跃。
2. 每个里程碑内部按任务编号顺序执行（如 `T2.1 → T2.2 → T2.3`）。
3. **一次只做一个任务。** 完成一个任务的「验收」后再开始下一个任务。
4. 每个任务完成后必须跑该任务的「验收」小节，全部通过才算完成。

### 0.2 每个任务的固定结构

| 小节 | 含义 |
|---|---|
| **目标** | 这个任务要达成的用户可见效果 |
| **现状** | 当前代码是什么样（已核对，见 §2） |
| **改动文件** | 精确文件路径清单 |
| **实施步骤** | 按序执行的具体改法，含代码骨架 |
| **接口契约** | 请求 / 响应 JSON 示例 |
| **验收** | 可复制粘贴的命令与预期结果 |
| **不要做** | 明确禁止的越界改动 |

### 0.3 硬性规则

1. **不要修改** `agentscope-service/aistio/internal/httpapi/`、`internal/sessionops/`、`internal/asdp/`、`internal/dataplane/`、`internal/prober/`、`internal/controller/`，以及前端 `frontend/src/features/operate/`。这些属于 BYO Operate，不在本期范围。
2. **不要修改** 已有 API 的**响应字段名**或**删除**已有路由；只允许新增字段 / 新增路由。
3. **不要引入新的第三方依赖**（Go 与前端都不允许）。现有依赖见 `aistio/go.mod` 与 `frontend/package.json`。
4. **不要改数据库已有列的类型或语义**；需要新列 / 新索引时在 `aistio/internal/product/migrate.go` 里用 `CREATE ... IF NOT EXISTS` / `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 追加。
5. **代码注释一律英文**（仓库规范）。文档可用中文。
6. 每次改完 Go 代码必须跑：`cd agentscope-service/aistio && go build ./... && go vet ./...`
7. 每次改完前端必须跑：`cd agentscope-service/frontend && npm run build`（等价于 `tsc --noEmit && vite build`）
8. **不要写「已完成」但未跑验收**。验收失败就继续修，不要改验收标准。

### 0.4 本地起服务（验收前置）

```bash
cd agentscope-service
./scripts/dev-up.sh                      # 起 gateway/control/data/scheduler
export BASE=http://localhost:8080
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
```

后文所有 curl 验收都假设 `$BASE` / `${AUTH[@]}` 已按上面设置。

---

## 1. 范围与目标

### 1.1 产品目标

把 AgentScope Service 的 Managed Agents 做成「可对外讲清楚、可在 Console 跑通、可用 API 集成」的托管 Agent 平台：
定义 Agent → 配置 Environment → 跑 Session → 事件可观测 → Memory / Vault / Deployments / Channels 闭环。

### 1.2 对标分层

| 层级 | 主参考 | 对齐什么 |
|---|---|---|
| 平台资源模型 | Claude MA | Agent × Environment × Session × Events；Vault / Memory / Deployments / Files |
| 托管运行语义 | Claude MA | 长任务、SSE、interrupt、HITL、self_hosted worker、permission policy |
| 作者态 / 扩展面 | Claude Code | Tools / MCP / Skills / Subagents / Memory / Permissions 的产品分层与文案 |
| 品牌边界 | Claude MA Reference | 自有品牌；**不得**伪装成 Claude Code / Cowork |

### 1.3 本期原则

1. 控制面优先：资源生命周期、治理、Console Build 链路先闭环。
2. 故事切片：每个里程碑同时交付 Console 路径 + REST 契约 + 验收命令。
3. 强依赖才动数据面：只有在 resolve / mount / event 语义阻塞时才改 `service-dataplane`。**本期计划下，M0–M5 全部任务都不需要改 Java 数据面**（原因见 §2.6）。
4. 不混 Operate。
5. 语义对齐优先于路径字面：保留 `/api/*` + JWT，不搬成 Claude 的 `/v1/*` + API Key。

---

## 2. 已验证的代码事实（实施前提 · 已逐条核对）

以下事实均在当前仓库核对过，实施者可以直接信任，不需要再调研。

### 2.1 平面与路由归属

| 平面 | 代码位置 | 职责 |
|---|---|---|
| 控制面 | `agentscope-service/aistio/internal/product/` | Agent / Env / Session 生命周期、Memory / Vault / Deploy / Channels、`/api/internal/*` |
| 数据面 | `agentscope-service/service-dataplane/` | Events、turn、SSE、HITL、Hands work queue |
| 网关 | `agentscope-service/service-gateway/src/main/resources/application.yml` | 拆分 CP / DP |
| Console | `agentscope-service/frontend/src/` | Build 区页面 + `src/api/*.ts` |

网关关键事实（`application.yml`）：

- `order: 0` 先匹配数据面：`/api/sessions/*/events/**`、`/api/sessions/*/hands-stats`、`/api/environments/*/work/**`、`/api/environments/*/sessions/*/**`、`/api/hands/**`
- `order: 10` 控制面 `control-api` 已包含 `/api/deployments/**`、`/api/environments/**`、`/api/sessions`、`/api/sessions/**`、`/api/memory-stores/**`、`/api/vaults/**`
- **结论**：本期新增的控制面路由（`/api/deployments/webhook/...`、`PATCH /api/environments/:id`、`PATCH /api/sessions/:id`、`/api/files*` 除外）**都无需改网关**。
  - 例外：`/api/files*` 是新前缀，**必须**在 `control-api` 的 `Path=` 里追加 `/api/files/**,/api/files`（见 T4.2）。

### 2.2 控制面代码风格（照抄这些模式）

`aistio/internal/product/` 里所有 handler 遵循同一套惯例：

| 惯例 | 位置 |
|---|---|
| `writeErr(c, status, msg)` → JSON `{"error": msg}` | `server.go:144` |
| `writeTextErr(c, status, msg)` → 纯文本 | `server.go:148` |
| `currentUserID(c)` 取 owner | `middleware.go:65` |
| `shortID("prefix_")` 生成 id | `server.go:92` |
| `nowMillis()` / `nullMillis()` / `nullStr()` / `nullStrPtr()` / `deref()` / `mustJSON()` / `parseJSONRaw()` / `parseStringSlice()` | `server.go`、`handlers_agents.go`、`handlers_sessions.go` |
| 每个资源一个 `registerXxx(r gin.IRouter)`，在 `server.go:64 Register()` 里挂载 | `server.go` |
| 列表返回**裸数组**（不是 `{data:[]}`） | 全部 list handler |

**新增 handler 必须沿用同样风格**，不要引入新的错误包装或响应封套。

### 2.3 各资源当前路由（核对结果）

| 文件 | 已有路由 | 缺什么 |
|---|---|---|
| `handlers_env.go:10-17` | GET/POST `/api/environments`、GET/DELETE `/:id`、POST `/:id/archive`、POST `/:id/rotate-key` | **无 update** |
| `handlers_deploy.go:15-23` | GET/POST 列表、GET/PATCH/DELETE `/:id`、POST `/:id/archive`、POST `/:id/run` | **无公开 webhook 入口**、**无 pause/unpause** |
| `handlers_vault.go:10-18` | vault GET/POST/GET-id/DELETE；credentials GET/POST/DELETE | **无 vault update/archive**、**无 credential update/validate** |
| `handlers_memory.go:12-23` | store GET/POST/GET-id/DELETE；memories list / `*path` GET/PUT/DELETE | **无 store archive**、**无 redact** |
| `handlers_sessions.go:11-17` | POST/GET 列表、GET `/:id`、POST `/:id/archive`、DELETE `/:id` | **无 PATCH** |
| `handlers_agent_extras.go:23-25` | tools catalog builtins / mcp-servers / active | **mcp-servers 返回空数组**（`handlers_agent_extras.go:255-261`） |

### 2.4 数据库现状（`migrate.go`）

| 表 | 关键列 | 结论 |
|---|---|---|
| `environments` | `config_json`、`api_key_hash`、`archived_at` | 更新 name/config **不需要迁移** |
| `deployments:145-164` | `webhook_token`、`enabled`、`archived_at`、`last_run_at`、`last_session_id`、`last_status` | webhook / pause **不需要新列**；只需加 `webhook_token` 索引 |
| `vaults:123-132` | **已有 `archived_at`** | vault archive **不需要迁移** |
| `vault_credentials:134-143` | `ciphertext BYTEA` | credential update 复用 `encryptAESGCM` |
| `memory_stores:91-100` | **已有 `archived_at`** | store archive **不需要迁移** |
| `memories:102-112` / `memory_versions:114-121` | 无 redact 标记列 | redact 采用「覆盖内容 + 清历史」实现，**不需要新列** |
| `sessions:65-85` | `agent_overrides_json`、`resources_json`、`version` | session PATCH **不需要迁移** |

### 2.5 gin 路由冲突验证（已实测，v1.12.0）

曾担心「静态段与 `:param` 同层冲突」。已在本仓库用临时测试实测（测试文件已删除），**全部注册成功、无 panic**：

| 组合 | 结果 |
|---|---|
| `/api/deployments/:id` + `/api/deployments/:id/run` + `/api/deployments/webhook/:token` | OK |
| `/api/sessions/:id` (GET) + `PATCH /api/sessions/:id` + `/api/sessions/:id/archive` | OK |
| `PATCH /api/environments/:id` | OK |
| `/api/vaults/:id/credentials/:cid` + `.../validate` + `/api/vaults/:id/archive` | OK |
| `/api/memory-stores/:id/memories/*path` + `/api/memory-stores/:id/archive` | OK |

**结论**：本文档规划的所有路由形状都可以直接注册，不需要改路径造型来绕开冲突。

### 2.6 数据面已有能力（决定了本期为何不用改 Java）

这三条是把 M4 从「跨平面大工程」降级为「控制面小工程」的关键：

1. **resolve 每轮实时拉取，无缓存**
   `ControlPlaneClient.resolveSession`（`service-dataplane/.../control/ControlPlaneClient.java:70-107`）每次调用都发 HTTP，`DataSessionService.resolve`（`:160-162`）直接透传。
   → 控制面改了 session 行，**下一轮 turn 自动生效**。

2. **overrides 已被 harness 应用**
   `HarnessAgentBuildService.java:209-227` 已支持 overrides 中的 `name` / `description` / `system`（或 `sysPrompt`）/ `model` / `maxIters`。
   → `PATCH /api/sessions/:id` 改这几项**零 Java 改动**即可生效。
   → `tools` / `mcpServers` 覆盖**尚未**被应用，因此本期明确不做（列入 vNext）。

3. **`resources[]` 的 inline 文件已能落盘**
   `SessionResourceMountService.java:153-167`：`type=file` 且带 `content` 时，直接写入工作区 `resources/{filename}`。resolve 响应本来就带 `resources`（`handlers_internal.go:137`），DP DTO 也有 `resources` 字段。
   → Files 只要在**控制面 resolve 时把 `fileId` 展开成 `content`**，就能被 agent 读到，**零 Java 改动**。

另外一条已知债（本期顺手还掉）：
`DataSessionService.mergeAgentOverrides`（`:131-148`）注释明说 *"Persistence of overrides requires a control-plane API"*，目前只打 warn 不落库。T4.1 提供的内部接口正好补上这个洞。

### 2.7 前端现状

| 事实 | 位置 |
|---|---|
| 每个 `src/api/*.ts` 自带本地 `authHeaders()`，用裸 `fetch` | `api/environments.ts:20`、`api/vaults.ts:32` 等 |
| 页面在 `src/pages/*.tsx`；Agents / Deployments Hub 在 `src/features/build/` | `pages/EnvironmentsHubPage.tsx` (221行)、`features/build/deployments/DeploymentsPage.tsx` (353行) |
| 内联 `React.CSSProperties` 样式对象是主流写法 | `pages/AgentToolsPage.tsx:6-50` |
| **`saveBuiltinEnabled` 把 `permissionPolicy` 硬编码成 `always_allow`** | `api/tools.ts:130-135` ← M1 的核心缺口 |
| Tools 页帮助文案仍写「MCP 通过 `workspace/tools.json` 配置」（已过时，实际在 Agent body） | `pages/AgentToolsPage.tsx:61-65` |
| toolset 的 type 字符串是 `agent_toolset`（不是 Claude 的 `agent_toolset_20260401`） | `api/tools.ts:92,129` |
| 构建命令 | `npm run build` = `tsc --noEmit && vite build` |

### 2.8 鉴权链

`aistio/internal/product/middleware.go:16-45`：`jwtMiddleware` 对所有 `/api/` 路径要求 `Authorization: Bearer`，**例外白名单**只有：

```go
path == "/api/auth/login" || path == "/actuator/health" ||
path == "/healthz" || strings.HasPrefix(path, "/api/internal/")
```

→ 任何需要免登录的新路由（本期只有 deployment webhook）**必须**显式加进这个白名单，否则会 401。

---

## 3. 全局工程约定

### 3.1 Go 控制面

- 新 handler 一律 `func (s *Server) xxx(c *gin.Context)`，放在对应 `handlers_*.go`。
- 路由注册加在同文件的 `registerXxx` 函数里，**不要**新建注册函数（除非新资源，见 T4.2 Files）。
- 归属校验统一：先 `load*` 再比 `OwnerID != currentUserID(c)`，不匹配一律返回 **404**（不要 403，避免探测资源存在性）。
- 更新类接口统一用 `PATCH`，body 用 `*T` 指针字段表示「未提供 = 不改」。
- 时间统一 `nowMillis()`（毫秒 epoch）。

### 3.2 错误约定

| 场景 | 状态码 | 形态 |
|---|---|---|
| 资源不存在 / 非本人 | 404 | `writeErr(c, 404, "xxx not found")` |
| body 不合法 | 400 | `writeErr(c, 400, "invalid body")` |
| 状态冲突（已归档等） | 409 | `writeErr(c, 409, "...")` |
| 内部错误 | 500 | `writeErr(c, 500, err.Error())` |

### 3.3 前端

- 新增 API 函数写进已有 `src/api/*.ts`，**照抄同文件里已有函数的写法**（`authHeaders()` + `fetch` + `res.ok` 判断 + `throw new Error`）。
- 不要引入状态管理库；沿用页面内 `useState` / 已有 `@tanstack/react-query` 用法。
- 新增 UI 用与所在页面一致的内联样式风格。

### 3.4 文档同步

每个里程碑结束时，更新：

- `docs/guide/12-limitations.md`：把已实现项从「限制」移到「已落地」
- `docs/WIP/MANAGED_AGENTS_API.md`：把新路由加进对照表，并从 §4 缺口表里删掉
- `docs/guide/14-validation.md`：追加该里程碑的验收路径

---

## 4. 对标摘要

### 4.1 Claude Managed Agents（主对标）

```text
Agent (versioned: model/system/tools/mcp/skills/multiagent)
  × Environment (cloud | self_hosted; packages/networking/secrets)
    → Session (overrides, vault_ids, resources, initial_events)
      ↔ Events (user.* / agent.* / session.* / span.* / system.message)
+ Memory Stores · Vaults · Deployments · Files · Skills
```

要点：

- Agent 是**版本化**资源；更新产生新版本；archive 后只读、存量 session 继续跑
- Environment 定义 sandbox；**不版本化**；多个 session 共享配置但各自独立沙箱
- Session 可用 `initial_events` 一步创建并启动
- 事件命名 `{domain}.{action}`；`event_start` / `event_delta` 仅流式、不落库
- Tools 用 `agent_toolset` + `default_config` / 逐工具 `configs`，每项可配 `permission_policy`
- MCP 在 agent 上声明 server，凭证在 session 用 `vault_ids` 注入
- 组织级限流：创建类 300 rpm、读取类 1200 rpm

### 4.2 Claude Code（辅参照：只借心智，不抄 UI）

| Claude Code 概念 | 对我们 Console 的启示 |
|---|---|
| agentic loop（gather → act → verify） | Chat / Session 详情要让用户看见工具链与可打断性 |
| builtin tools（bash/file/search/web） | Tools 页对齐 toolset + 逐工具开关 + 权限 |
| Skills 渐进披露 | Skill 描述常驻、正文按需加载 |
| MCP vs Skill 分工 | MCP = 外接能力；Skill = 怎么用好这些能力 |
| Subagents 独立 context | Subagents 页强调「委托 + 上下文隔离」 |
| Permissions 分档 | 对齐 `permission_policy` + HITL，Console 必须可见可改 |
| Memory（CLAUDE.md / auto memory） | Memory Store = 跨 Session；Workspace = 定义态 |

**品牌红线**：Claude MA Reference 明确禁止把产品做成 Claude Code 的样子或使用其品牌元素。对标的是能力闭环与信息架构。

---

## 5. 能力矩阵与缺口

图例：`Y` 可用 · `P` 部分 · `N` 缺 · `—` 本期不追求

| 能力域 | Claude MA | 我们 | 本期处理 |
|---|---|---|---|
| 版本化 Agent + archive + versions | Y | Y | 保持 |
| toolset + 逐工具 config | Y | P | **M1**（权限策略不可配） |
| custom tools 续跑 | Y | P | 文档化，不改代码 |
| MCP on agent + vault 鉴权 | Y | P | **M1**（catalog 空）+ **M3**（validate） |
| 一等 Skills 资源 | Y | P | vNext |
| Multiagent / Threads | Y | P | vNext |
| Environment update | Y | N | **M2** |
| Env packages / networking | Y | P | vNext（见 SANDBOX_GAPS） |
| Session create + events + SSE | Y | Y | 保持 |
| Session 中途更新 | Y | N | **M4**（限 system/model/maxIters） |
| Session resources / Files | Y | N | **M4**（inline 文本文件） |
| interrupt / HITL | Y | Y | **M1** 打磨可见性 |
| Outcomes | Y | P | vNext |
| Memory store archive / redact | Y | N | **M3** |
| Vault update/archive、credential update/validate | Y | N | **M3** |
| Deployments webhook / pause | Y | P | **M2** |
| Channels | — | Y | 保持 |
| OpenAPI / 分页 | Y | N | **M5** |

---

## 6. 里程碑与任务

### 交付总览

| 里程碑 | 故事 | 主要改动 | 需改 Java DP |
|---|---|---|---|
| M0 | 文档基线与承诺面 | docs | 否 |
| M1 | 配置 → 运行闭环打磨 | frontend + 少量 Go | 否 |
| M2 | Environment / Deployments 生产可用 | Go + frontend | 否 |
| M3 | Secrets / Memory 治理闭环 | Go + frontend | 否 |
| M4 | Session 可变配置 + Files | Go + frontend | 否（见 §2.6） |
| M5 | 对外集成面 | Go + docs + frontend | 否 |

---

## M0 · 基线固化（仅文档）

**故事**：新人照文档能跑通 Local Chat，并清楚「什么已承诺、什么未承诺」。

### T0.1 文档互链与承诺面

**目标**：三份文档口径一致，实施者与用户看到同一张地图。

**改动文件**

- `agentscope-service/docs/guide/12-limitations.md`
- `agentscope-service/docs/WIP/MANAGED_AGENTS_API.md`

**实施步骤**

1. 在 `12-limitations.md` 的「路线图阅读顺序」中确认本文档已列为第 2 项（已完成，无需重复添加）。
2. 在 `MANAGED_AGENTS_API.md` 顶部确认已链接本文档（已完成）。
3. 在 `MANAGED_AGENTS_API.md` §5「最小对外发布面」后追加一段，写明本期承诺面 = §8.2 的表格内容。

**验收**

```bash
cd agentscope-service/docs
grep -c MANAGED_AGENTS_PRODUCT_PLAN guide/12-limitations.md WIP/MANAGED_AGENTS_API.md
# 两个文件都应 >= 1
```

**不要做**：不要在 M0 改任何代码。

### T0.2 基线回归

**目标**：确认起点是绿的，后续失败可归因到自己的改动。

**验收**

```bash
cd agentscope-service/aistio && go build ./... && go vet ./...
cd ../frontend && npm run build
```

按 §0.4 起服务后，跑通 [14-validation.md](../guide/14-validation.md) 路径 A（local Chat 一轮对话），确认事件里出现 `session.status_running` / `agent.message` / `session.status_idle`。

---

## M1 · 配置 → 运行闭环打磨

**故事**：定义 Agent（工具 + 权限）→ 选 Environment → Chat → 看见工具调用与 HITL 确认 → 归档 Session。

### T1.1 工具权限策略可配置（前端为主）

**目标**：用户能在 Tools 页把某个工具设为「每次询问」（`always_ask`），从而在 Chat 里触发 HITL 确认，而不是所有工具都被硬编码为 `always_allow`。

**现状**

`frontend/src/api/tools.ts:130-135` 与 `:198-202`、`:207-210` 把 `permissionPolicy` 写死成 `{ type: 'always_allow' }`，UI 无法表达 `always_ask`。后端 Agent body 本身支持任意 `permissionPolicy`，数据面 HITL 链路（`always_ask` → `requires_action` → `user.tool_confirmation`）已可用。

**改动文件**

- `frontend/src/api/agents.ts`（如 `AgentToolset` / config 类型里没有 `permissionPolicy`，补类型）
- `frontend/src/api/tools.ts`
- `frontend/src/components/ToolsActivePanel.tsx`
- `frontend/src/pages/AgentToolsPage.tsx`

**实施步骤**

1. 在 `api/agents.ts` 确认（必要时补充）类型：

```ts
export type PermissionPolicy = { type: 'always_allow' | 'always_ask' };

export interface AgentToolConfig {
  name: string;
  enabled?: boolean;
  permissionPolicy?: PermissionPolicy;
}
```

2. 在 `api/tools.ts` 新增读写函数（沿用同文件已有风格）：

```ts
/** Per-tool permission policy from Agent body `tools[].configs`. */
export function computeToolPolicies(
  tools: AgentToolset[] | undefined,
): Map<string, PermissionPolicy['type']> { /* 读 agent_toolset.configs */ }

/** Persist per-tool enablement + permission policy in one agent update. */
export async function saveBuiltinToolConfig(
  agentId: string,
  catalog: BuiltinToolInfo[],
  enabled: Set<string>,
  policies: Map<string, PermissionPolicy['type']>,
): Promise<AgentDefinition> { /* 见步骤 3 */ }
```

3. `saveBuiltinToolConfig` 内部把 `saveBuiltinEnabled` 的硬编码替换为按 `policies` 取值，缺省仍是 `always_allow`：

```ts
configs: catalog.map(b => ({
  name: b.id,
  enabled: enabled.has(b.id),
  permissionPolicy: { type: policies.get(b.id) ?? 'always_allow' },
})),
```

   保留 `saveBuiltinEnabled` 作为薄封装（内部调用新函数、传空 policies），避免破坏现有调用点。

4. `ToolsActivePanel.tsx` 每行工具在开关右侧加一个二选一控件（`Auto` / `Ask`），改动后调用 `saveBuiltinToolConfig`。

5. `AgentToolsPage.tsx:61-65` 帮助文案改为准确描述（英文）：工具与 MCP 配置保存在 Agent 定义（产生新版本），下一个 Session 生效；`Ask` 表示该工具调用前会暂停等待确认。

**接口契约**（Agent body 片段，PUT `/api/agents/{id}`）

```json
{
  "name": "demo",
  "version": 3,
  "tools": [
    {
      "type": "agent_toolset",
      "defaultConfig": { "enabled": true, "permissionPolicy": { "type": "always_allow" } },
      "configs": [
        { "name": "bash", "enabled": true, "permissionPolicy": { "type": "always_ask" } },
        { "name": "read", "enabled": true, "permissionPolicy": { "type": "always_allow" } }
      ]
    }
  ]
}
```

**验收**

```bash
cd agentscope-service/frontend && npm run build
```

UI 验收：Tools 页把 `bash` 设为 `Ask` → 保存 → `GET /api/agents/{id}` 的 `tools[0].configs` 中 `bash` 的 `permissionPolicy.type == "always_ask"` → 新建 Session 让 agent 跑 bash → Chat 出现待确认状态，发送 `user.tool_confirmation` 后继续。

**不要做**：不要改数据面 HITL 逻辑；不要改 `PUT /api/agents/{id}` 的请求结构。

### T1.2 MCP 目录返回内置模板

**目标**：Tools 页的「添加 MCP 服务器」不再是空列表，用户有可选起点。

**现状**：`handlers_agent_extras.go:255-261` 的 `toolsMcpCatalog` 永远返回 `[]`。

**改动文件**：`aistio/internal/product/handlers_agent_catalog_mcp.go`（新建）、`handlers_agent_extras.go`（改 handler 体）

**实施步骤**

1. 新建 `handlers_agent_catalog_mcp.go`，定义一个包级静态切片（**不读网络、不读磁盘**）：

```go
// builtinMcpCatalog lists curated remote MCP servers offered as starting points
// in the console. Entries carry no credentials; secrets come from vaults.
var builtinMcpCatalog = []gin.H{
	{
		"id": "everything", "name": "MCP Everything (reference)",
		"description":  "Reference server used to validate MCP wiring.",
		"transport":    "streamable-http",
		"url":          "https://example.invalid/mcp",
		"docsUrl":      "https://modelcontextprotocol.io",
		"requiredEnv":  []string{},
	},
	// 再加 2-4 条团队常用的远程 MCP
}
```

2. 把 `toolsMcpCatalog` 的 `c.JSON(http.StatusOK, []any{})` 换成 `c.JSON(http.StatusOK, builtinMcpCatalog)`，保留前面的 agent 归属校验不变。

3. 字段名必须与前端 `McpCatalogEntry`（`api/tools.ts:39-52`）一致：`id` / `name` / `description` / `transport` / `url` / `command` / `args` / `env` / `headers` / `queryParams` / `requiredEnv` / `docsUrl`。

**验收**

```bash
AGENT_ID=<某个已存在的 agent id>
curl -s "$BASE/api/agents/$AGENT_ID/tools/catalog/mcp-servers" "${AUTH[@]}" | jq 'length'
# 期望 > 0，且每项含 id/name/transport
```

**不要做**：不要从 Operate 的 `MCPServer` CRD 读数据（那是 BYO 面）；不要在启动时发起网络请求。

### T1.3 Session 列表状态可读

**目标**：Sessions 列表能看懂「跑到哪了 / 为什么停」。

**现状**：`GET /api/sessions` 已返回 `status` 与 `stopReason`（`handlers_sessions.go:74-75`），前端展示不完整。

**改动文件**：`frontend/src/pages/AgentSessionsPage.tsx`、必要时 `frontend/src/api/managedSessions.ts`（补类型字段）

**实施步骤**

1. 确认 `managedSessions.ts` 的 Session 类型含 `status`、`stopReason`、`updatedAt`；缺则补。
2. 列表中增加状态列：把 `status` 映射为中性文案与颜色（`active` / `archived` / 其它原样透传，**不要**臆造状态枚举）。
3. `stopReason` 非空时以次要文本展示其 `type` 或整体 JSON 摘要。

**验收**

```bash
curl -s "$BASE/api/sessions" "${AUTH[@]}" | jq '.[0] | {id,status,stopReason}'
cd agentscope-service/frontend && npm run build
```

UI 上 Sessions 列表能看到状态列。

**不要做**：不要在前端硬编码后端未返回的状态集合。

### M1 完成定义

- [ ] Tools 页可设置逐工具权限策略并持久化到 Agent 版本
- [ ] `Ask` 策略在 Chat 中触发确认流程
- [ ] MCP 目录非空
- [ ] Sessions 列表显示状态 / stopReason
- [ ] `go build` + `go vet` + `npm run build` 全绿
- [ ] `12-limitations.md` 中「前端 Tools / Settings」一行更新为已支持权限策略

---

## M2 · Environment / Deployments 生产可用

**故事**：改 Environment 配置无需删了重建；Webhook Deployment 可被外部系统触发，Console 能看到最近一次运行并跳到 Session。

### T2.1 Environment 更新 API

**目标**：可修改 Environment 的 `name` 与 `config`，无需删除重建。

**现状**：`handlers_env.go:10-17` 无更新路由。

**改动文件**：`aistio/internal/product/handlers_env.go`

**实施步骤**

1. 在 `registerEnvironments` 追加：

```go
r.PATCH("/api/environments/:id", s.updateEnvironment)
```

2. 追加请求结构与 handler：

```go
type envUpdateReq struct {
	Name   *string `json:"name"`
	Config any     `json:"config"`
}

// updateEnvironment changes the mutable parts of an environment. The type is
// immutable because running sessions resolve their sandbox from it.
func (s *Server) updateEnvironment(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	e, err := s.loadEnv(c.Request.Context(), id)
	if err != nil || e.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	if e.ArchivedAt != nil {
		writeErr(c, http.StatusConflict, "environment is archived")
		return
	}
	var req envUpdateReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	name := e.Name
	if req.Name != nil {
		if *req.Name == "" {
			writeErr(c, http.StatusBadRequest, "name cannot be empty")
			return
		}
		name = *req.Name
	}
	configJSON := deref(e.ConfigJSON)
	if req.Config != nil {
		configJSON = mustJSON(req.Config)
	}
	now := nowMillis()
	if _, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE environments SET name=$1, config_json=$2, updated_at=$3
		 WHERE environment_id=$4 AND owner_id=$5`, name, configJSON, now, id, owner); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	out, _ := s.loadEnv(c.Request.Context(), id)
	c.JSON(http.StatusOK, out.toJSON())
}
```

3. 前端 `frontend/src/api/environments.ts` 追加：

```ts
export interface UpdateEnvironmentRequest {
  name?: string;
  config?: Record<string, unknown>;
}

export async function updateEnvironment(
  id: string,
  req: UpdateEnvironmentRequest,
): Promise<Environment> { /* PATCH，写法照抄本文件 createEnvironment */ }
```

4. `frontend/src/pages/EnvironmentsHubPage.tsx` 给每个 Environment 加「编辑」入口：可改名称与 config（JSON 文本域，保存前 `JSON.parse` 校验，解析失败给出错误提示且不发请求）。**type 字段只读展示**。

**接口契约**

```http
PATCH /api/environments/env_abc
{ "name": "prod-sandbox", "config": { "networking": { "type": "unrestricted" } } }
```

响应：与 `GET /api/environments/{id}` 同结构（`id/name/type/config/ownerId/archivedAt/createdAt/updatedAt`）。

语义说明（写进文档）：省略 `config` 表示不变；传 `{}` 表示清空。`type` 不可改。

**验收**

```bash
ENV_ID=$(curl -s -X POST "$BASE/api/environments" "${AUTH[@]}" \
  -d '{"name":"m2-env","type":"local"}' | jq -r .id)
curl -s -X PATCH "$BASE/api/environments/$ENV_ID" "${AUTH[@]}" \
  -d '{"name":"m2-env-renamed","config":{"foo":"bar"}}' | jq '{name,config,type}'
# 期望 name=m2-env-renamed, config={"foo":"bar"}, type=local
curl -s -X PATCH "$BASE/api/environments/does-not-exist" "${AUTH[@]}" -d '{"name":"x"}' -o /dev/null -w '%{http_code}\n'
# 期望 404
```

**不要做**：不要允许改 `type`；不要动 `api_key_hash`（轮换走已有 `rotate-key`）。

### T2.2 Deployment 公开 Webhook 入口

**目标**：外部系统用 webhook token 直接触发一次部署运行，无需登录态。

**现状**：`handlers_deploy.go:148-151` 创建 `webhook` 类型时会生成 token 存库，但**没有任何路由消费它**；文档里却写了这个接口。

**改动文件**

- `aistio/internal/product/handlers_deploy.go`
- `aistio/internal/product/middleware.go`
- `aistio/internal/product/migrate.go`
- `frontend/src/features/build/deployments/DeploymentsPage.tsx`

**实施步骤**

1. `migrate.go` 在 `deployments` 建表语句之后追加索引：

```sql
CREATE INDEX IF NOT EXISTS idx_deployments_webhook ON deployments (webhook_token);
```

2. `middleware.go:19-25` 的白名单加一条（放在 `/api/internal/` 判断之前或之后都可以）：

```go
strings.HasPrefix(path, "/api/deployments/webhook/") ||
```

3. `handlers_deploy.go` 的 `registerDeployments` 追加：

```go
r.POST("/api/deployments/webhook/:token", s.triggerDeploymentWebhook)
```

4. 新增 handler：

```go
// triggerDeploymentWebhook fires a deployment from an unauthenticated caller
// that presents a valid webhook token. Disabled or archived deployments are
// indistinguishable from unknown tokens on purpose.
func (s *Server) triggerDeploymentWebhook(c *gin.Context) {
	token := c.Param("token")
	if token == "" {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	d, err := s.scanDeploy(s.db.Pool.QueryRow(c.Request.Context(),
		deploySelect+` WHERE webhook_token=$1 AND trigger_type='webhook'
		   AND enabled=TRUE AND archived_at IS NULL`, token))
	if err != nil {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	_ = c.ShouldBindJSON(&body)
	out, err := s.fireDeployment(c.Request.Context(), d, body.Text)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	// Deliberately narrow: the full row would leak webhook_token back out.
	c.JSON(http.StatusOK, gin.H{
		"deploymentId": out.DeploymentID,
		"sessionId":    nullStrPtr(out.LastSessionID),
		"status":       nullStrPtr(out.LastStatus),
		"lastRunAt":    nullMillis(out.LastRunAt),
	})
}
```

5. `DeploymentsPage.tsx`：`triggerType === 'webhook'` 时展示可复制的完整 URL `${window.location.origin}/api/deployments/webhook/${webhookToken}`，并标注「持有此 URL 即可触发，请当作密钥保管」。

**接口契约**

```http
POST /api/deployments/webhook/{token}
Content-Type: application/json
{ "text": "run nightly report" }
```

响应 200：`{"deploymentId":"dep_x","sessionId":"sess_y","status":"ok","lastRunAt":1730000000000}`
响应 404：token 未知 / 非 webhook 类型 / 已禁用 / 已归档。

**验收**

```bash
AGENT_ID=<agent id>; ENV_ID=<env id>
DEP=$(curl -s -X POST "$BASE/api/deployments" "${AUTH[@]}" \
  -d "{\"name\":\"m2-hook\",\"agentId\":\"$AGENT_ID\",\"environmentId\":\"$ENV_ID\",\"triggerType\":\"webhook\"}")
TOKEN_HOOK=$(jq -r .webhookToken <<<"$DEP")

# 关键：不带 Authorization
curl -s -X POST "$BASE/api/deployments/webhook/$TOKEN_HOOK" \
  -H 'Content-Type: application/json' -d '{"text":"hello"}' | jq
# 期望 200 且返回 sessionId

SESS=$(curl -s -X POST "$BASE/api/deployments/webhook/$TOKEN_HOOK" \
  -H 'Content-Type: application/json' -d '{"text":"hi"}' | jq -r .sessionId)
curl -s "$BASE/api/sessions/$SESS/events" "${AUTH[@]}" | jq '[.[].type] | unique'
# 期望包含 user.message

curl -s -X POST "$BASE/api/deployments/webhook/bogus-token" -o /dev/null -w '%{http_code}\n'
# 期望 404

# 响应体不得包含 webhookToken
curl -s -X POST "$BASE/api/deployments/webhook/$TOKEN_HOOK" \
  -H 'Content-Type: application/json' -d '{}' | grep -c webhookToken
# 期望 0
```

**不要做**

- 不要在 webhook 响应里返回完整 deployment 对象（会泄露 token）。
- 不要改 `fireDeployment` 的既有行为（`/:id/run` 仍走同一函数）。
- 不要改网关配置：`/api/deployments/**` 已路由到控制面。

### T2.3 Deployment pause / unpause

**目标**：暂停与恢复语义清晰，且与 webhook 拦截一致。

**现状**：只有 `PATCH` 里的 `enabled` 字段和 `archive`（不可逆）。

**改动文件**：`aistio/internal/product/handlers_deploy.go`、`frontend/src/api/deployments.ts`、`DeploymentsPage.tsx`

**实施步骤**

1. 注册两个路由：

```go
r.POST("/api/deployments/:id/pause", s.pauseDeployment)
r.POST("/api/deployments/:id/unpause", s.unpauseDeployment)
```

2. 用共享实现：

```go
func (s *Server) pauseDeployment(c *gin.Context)   { s.setDeploymentEnabled(c, false) }
func (s *Server) unpauseDeployment(c *gin.Context) { s.setDeploymentEnabled(c, true) }

// setDeploymentEnabled flips the enabled flag; archived deployments stay paused.
func (s *Server) setDeploymentEnabled(c *gin.Context, enabled bool) {
	owner := currentUserID(c)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE deployments SET enabled=$1, updated_at=$2
		 WHERE deployment_id=$3 AND owner_id=$4 AND archived_at IS NULL`,
		enabled, now, c.Param("id"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	d, _ := s.loadDeploy(c.Request.Context(), c.Param("id"))
	c.JSON(http.StatusOK, d.toJSON())
}
```

3. 前端 `deployments.ts` 加 `pauseDeployment(id)` / `unpauseDeployment(id)`；页面开关改调这两个接口（保留 `updateDeployment` 用于改名/改 cron）。

**验收**

```bash
DEP_ID=<deployment id>
curl -s -X POST "$BASE/api/deployments/$DEP_ID/pause" "${AUTH[@]}" | jq .enabled    # false
curl -s -X POST "$BASE/api/deployments/$DEP_ID/unpause" "${AUTH[@]}" | jq .enabled  # true
# pause 后 webhook 应 404
```

### T2.4 最近运行深链

**目标**：Deployments 页能一键跳到最近一次运行的 Session。

**现状**：`lastSessionId` / `lastStatus` / `lastRunAt` 已在响应里（`handlers_deploy.go:78-81`）。

**改动文件**：`frontend/src/features/build/deployments/DeploymentsPage.tsx`

**实施步骤**：`lastSessionId` 非空时渲染链接，指向已有 Session 详情路由（照抄 `AgentSessionsPage` 里跳转 `AgentSessionDetailPage` 的路径构造方式）；同时展示 `lastStatus` 与 `lastRunAt`（本地时间）。

**验收**：手动 run 一次后，Deployments 页出现可点击的最近运行链接，点击进入该 Session 详情且事件非空。

### M2 完成定义

- [ ] `PATCH /api/environments/:id` 可用，`type` 不可改
- [ ] 未登录 curl webhook 能触发并产生 Session
- [ ] webhook 响应不含 token；未知/禁用 token 返回 404
- [ ] pause / unpause 可用且拦截 webhook
- [ ] Console 可编辑 Env、可复制 webhook URL、可跳最近运行
- [ ] `go build` + `go vet` + `npm run build` 全绿
- [ ] `MANAGED_AGENTS_API.md` 缺口表删除对应三行

---

## M3 · Secrets / Memory 治理闭环

**故事**：轮换 MCP 凭证并验证可用；Memory 可归档、可脱敏。

### T3.1 Vault 更新与归档

**目标**：Vault 可改名/改元数据、可归档（而不是只能删除）。

**现状**：`vaults` 表已有 `archived_at`（`migrate.go:129`），`listVaults` / `loadVault` 已按 `archived_at IS NULL` 过滤，但没有写入该列的路由。

**改动文件**：`aistio/internal/product/handlers_vault.go`、`frontend/src/api/vaults.ts`、`frontend/src/pages/VaultsPage.tsx`

**实施步骤**

1. 注册：

```go
r.PATCH("/api/vaults/:id", s.updateVault)
r.POST("/api/vaults/:id/archive", s.archiveVault)
```

2. `updateVault`：可改 `displayName`（非空）与 `metadata`（省略即不变），归档的 vault 返回 409。响应复用 `loadVault` 的结构。
3. `archiveVault`：`UPDATE vaults SET archived_at=$1, updated_at=$1 WHERE vault_id=$2 AND owner_id=$3 AND archived_at IS NULL`；`RowsAffected()==0` → 404；成功返回归档后的对象（此时 `loadVault` 会过滤掉，故直接返回 `gin.H{"id":id,"archivedAt":now}`）。
4. 前端加「重命名」「归档」操作。

**重要语义**：归档后 `resolveVaultCredentials`（`handlers_vault.go:211-244`）**目前不过滤归档 vault**。本任务要求在该函数的 `SELECT owner_id FROM vaults WHERE vault_id=$1` 上追加 `AND archived_at IS NULL`，保证归档即停止注入。

**验收**

```bash
V=$(curl -s -X POST "$BASE/api/vaults" "${AUTH[@]}" -d '{"displayName":"m3-vault"}' | jq -r .id)
curl -s -X PATCH "$BASE/api/vaults/$V" "${AUTH[@]}" -d '{"displayName":"m3-vault-2"}' | jq .displayName  # m3-vault-2
curl -s -X POST "$BASE/api/vaults/$V/archive" "${AUTH[@]}" | jq .archivedAt   # 非 null
curl -s "$BASE/api/vaults/$V" "${AUTH[@]}" -o /dev/null -w '%{http_code}\n'   # 404
curl -s "$BASE/api/vaults" "${AUTH[@]}" | jq "[.[] | select(.id==\"$V\")] | length"  # 0
```

### T3.2 凭证更新与校验

**目标**：可轮换某条凭证的密文，并做一次「是否可用」的检查，全程不回显明文。

**现状**：只有 list / create / delete。

**改动文件**：`aistio/internal/product/handlers_vault.go`、`frontend/src/api/vaults.ts`、`frontend/src/pages/VaultsPage.tsx`

**实施步骤**

1. 注册：

```go
r.PATCH("/api/vaults/:id/credentials/:cid", s.updateCredential)
r.POST("/api/vaults/:id/credentials/:cid/validate", s.validateCredential)
```

2. `updateCredential`：body `{"label":?,"target":?,"secret":?}`（均可选）。给了 `secret` 才重新 `encryptAESGCM(s.vaultKey, secret)` 并更新 `ciphertext`。响应与 `listCredentials` 单项同结构（**绝不包含 secret**）。

3. `validateCredential` 分两级，**默认只做本地校验**：

```go
// validateCredential performs a local decrypt check and, when the credential
// targets an http(s) endpoint, a bounded reachability probe. It never returns
// the secret.
```

   - 级别 1（总是执行）：能否从库里取出并成功 `decryptAESGCM`，且明文非空。
   - 级别 2（仅当 `target` 以 `http://` 或 `https://` 开头）：发一次 `GET`，超时 5 秒，`http.Client{Timeout: 5*time.Second}`，只记录状态码。**必须**先校验 scheme 只允许 http/https（防 SSRF 扩面），失败不视为致命错误。

   响应：

```json
{ "ok": true, "checks": { "decrypt": "ok", "reachability": "http_200" }, "checkedAt": 1730000000000 }
```

   解密失败时 `ok=false`、`checks.decrypt="failed"`，HTTP 状态仍为 200（这是检查结果而非请求错误）。

4. 前端：凭证行加「更新密钥」与「校验」按钮，校验结果以徽标展示。

**验收**

```bash
V=<vault id>
C=$(curl -s -X POST "$BASE/api/vaults/$V/credentials" "${AUTH[@]}" \
  -d '{"type":"bearer","label":"gh","target":"https://example.com","secret":"s1"}' | jq -r .id)
curl -s -X PATCH "$BASE/api/vaults/$V/credentials/$C" "${AUTH[@]}" -d '{"secret":"s2"}' | jq
# 响应不得含 secret 字段
curl -s -X POST "$BASE/api/vaults/$V/credentials/$C/validate" "${AUTH[@]}" | jq
# 期望 ok=true, checks.decrypt="ok"
curl -s -X POST "$BASE/api/vaults/$V/credentials/$C/validate" "${AUTH[@]}" | grep -c '"secret"'
# 期望 0
```

**不要做**：不要实现完整 OAuth 刷新流程（vNext）；不要允许 `file://` / `gopher://` 等非 http(s) 探测。

### T3.3 Memory Store 归档与 redact

**目标**：Memory Store 可归档；单条记忆可脱敏（不可逆），满足合规诉求。

**现状**：`memory_stores` 已有 `archived_at`（`migrate.go:97`）但无写入路由；无 redact。

**改动文件**：`aistio/internal/product/handlers_memory.go`、`frontend/src/api/memoryStores.ts`、`frontend/src/pages/MemoryStoresPage.tsx`

**实施步骤**

1. 注册（`/redact` 用**独立字面段**，避免与 `memories/*path` 通配纠缠；§2.5 已验证可行）：

```go
r.POST("/api/memory-stores/:id/archive", s.archiveMemoryStore)
r.POST("/api/memory-stores/:id/redact", s.redactMemory)
```

2. `archiveMemoryStore`：与 T3.1 的 vault archive 同形。

3. `redactMemory`：body `{"path":"notes/a.md","replacement":"[REDACTED]"}`（`replacement` 省略时默认 `[REDACTED]`）。语义：

   - 找到 `memories` 行；找不到 404
   - `head_version + 1`
   - `UPDATE memories SET content=$replacement, head_version=$hv, updated_at=$now`
   - `DELETE FROM memory_versions WHERE memory_id=$1`（清空全部历史）
   - 插入一条新的 `memory_versions`（`version=$hv`，内容为 replacement）
   - 返回更新后的 memory（复用 `loadMemory`）

   **在 handler 注释与文档里写明：redact 不可逆，历史版本会被永久删除。**

4. `buildMemoryMount`（`handlers_internal.go`）在挂载时应跳过已归档 store：在其 store 查询上加 `AND archived_at IS NULL`（与 T3.1 的 vault 处理保持一致）。

5. 前端：Store 卡片加「归档」；记忆条目加「脱敏」（二次确认弹窗）。

**验收**

```bash
MS=$(curl -s -X POST "$BASE/api/memory-stores" "${AUTH[@]}" -d '{"name":"m3-ms"}' | jq -r .id)
curl -s -X PUT "$BASE/api/memory-stores/$MS/memories/notes/a.md" "${AUTH[@]}" -d '{"content":"v1"}' >/dev/null
curl -s -X PUT "$BASE/api/memory-stores/$MS/memories/notes/a.md" "${AUTH[@]}" -d '{"content":"secret"}' >/dev/null
curl -s "$BASE/api/memory-stores/$MS/memories/versions/notes/a.md" "${AUTH[@]}" | jq length   # 2

curl -s -X POST "$BASE/api/memory-stores/$MS/redact" "${AUTH[@]}" -d '{"path":"notes/a.md"}' | jq .content
# 期望 "[REDACTED]"
curl -s "$BASE/api/memory-stores/$MS/memories/versions/notes/a.md" "${AUTH[@]}" | jq length   # 1
curl -s "$BASE/api/memory-stores/$MS/memories/versions/notes/a.md" "${AUTH[@]}" | jq -r '.[0].content'  # [REDACTED]

curl -s -X POST "$BASE/api/memory-stores/$MS/archive" "${AUTH[@]}" | jq .archivedAt   # 非 null
curl -s "$BASE/api/memory-stores/$MS" "${AUTH[@]}" -o /dev/null -w '%{http_code}\n'   # 404
```

**不要做**：不要给 `memories` 加新列；不要让 redact 可撤销。

### M3 完成定义

- [ ] Vault update / archive 可用，归档后不再注入凭证
- [ ] Credential update / validate 可用，响应绝不含明文
- [ ] Memory store archive、memory redact 可用且历史被清除
- [ ] 归档 store 不再被 resolve 挂载
- [ ] Console 三个页面均有对应操作
- [ ] 三项命令验收全绿

---

## M4 · Session 可变配置 + Files

**故事**：Session 空闲时改系统提示/模型无需重建；上传一个文本文件并让 agent 在下一轮读到。

> 关键前提见 §2.6：resolve 无缓存、overrides 已被 harness 应用、inline file resource 已能落盘。
> **本里程碑不需要修改任何 Java 代码。**

### T4.1 Session 更新（overrides）

**目标**：`PATCH /api/sessions/{id}` 修改会话级 `system` / `model` / `maxIters` / `name` / `description`，下一轮生效。

**现状**：`handlers_sessions.go:11-17` 无 PATCH；`sessions.agent_overrides_json` 列已存在；`internalResolveSession` 已把它透给数据面（`handlers_internal.go:133`）；`HarnessAgentBuildService.java:209-227` 已消费这些键。

**改动文件**：`aistio/internal/product/handlers_sessions.go`、`handlers_internal.go`、`frontend/src/api/managedSessions.ts`、`frontend/src/pages/AgentSessionDetailPage.tsx`

**实施步骤**

1. 注册：

```go
r.PATCH("/api/sessions/:id", s.updateSession)
```

2. 定义**允许的 override 键白名单**（严格限制，超出的键返回 400）：

```go
// sessionOverrideKeys is the closed set of session-scoped overrides the harness
// currently applies (see HarnessAgentBuildService). Tools and MCP overrides are
// intentionally rejected until the data plane consumes them.
var sessionOverrideKeys = map[string]bool{
	"system": true, "model": true, "maxIters": true,
	"name": true, "description": true,
}
```

3. `updateSession` 行为：

   - 校验归属，404 规则同前
   - 已归档（`archived_at` 非空）→ 409
   - body：`{"agentOverrides": { ... }}`
   - 逐键校验白名单；出现 `tools` / `mcpServers` 等 → 400，错误信息写明「暂不支持，见 vNext」
   - 与已有 `agent_overrides_json` **浅合并**（新键覆盖旧键；值为 `null` 表示删除该键）
   - `UPDATE sessions SET agent_overrides_json=$1, version=version+1, updated_at=$2 WHERE session_id=$3 AND owner_id=$4`
   - 返回 `sessionRow.toJSON()`

4. 在 `handlers_internal.go` 的 `registerInternal` 追加内部变体，供数据面把 `system.message` 落库（补上 `DataSessionService.mergeAgentOverrides` 的 TODO）：

```go
r.PATCH("/api/internal/sessions/:id/overrides", s.internalPatchSessionOverrides)
```

   实现与 `updateSession` 共用一个内部函数，区别是 owner 从 `X-Builder-Internal-User` 头（已由 `internalMiddleware` 写入 context）取，取不到时跳过 owner 校验。

5. 前端：Session 详情页加「会话配置」区块，可编辑 system prompt / model / maxIters，保存调 PATCH，并提示「下一轮生效」。

**接口契约**

```http
PATCH /api/sessions/sess_abc
{ "agentOverrides": { "system": "Answer in one sentence.", "maxIters": 5 } }
```

响应：与 `GET /api/sessions/{id}` 同结构（含更新后的 `agentOverridesJson`）。

拒绝示例：

```json
{ "error": "unsupported override key: tools" }
```

**验收**

```bash
S=<一个 idle 的 session id>
curl -s -X PATCH "$BASE/api/sessions/$S" "${AUTH[@]}" \
  -d '{"agentOverrides":{"system":"Always answer with the single word PONG."}}' | jq .agentOverridesJson
# 期望包含 system

curl -s -X POST "$BASE/api/sessions/$S/events" "${AUTH[@]}" \
  -d '{"events":[{"type":"user.message","payload":{"text":"hello"}}]}' >/dev/null
sleep 8
curl -s "$BASE/api/sessions/$S/events" "${AUTH[@]}" | jq -r '[.[] | select(.type=="agent.message")] | last'
# 期望回复受 system 影响（例如输出 PONG）

curl -s -X PATCH "$BASE/api/sessions/$S" "${AUTH[@]}" -d '{"agentOverrides":{"tools":[]}}' \
  -o /dev/null -w '%{http_code}\n'
# 期望 400
```

**不要做**：不要支持 `tools` / `mcpServers` 覆盖；不要改 `SessionTurnRunner` 或任何 Java 文件。

### T4.2 Files 最小集（控制面存储 + resolve 展开）

**目标**：上传一个文本文件 → 创建 Session 时挂载 → agent 在工作区 `resources/` 下读到真实内容。

**现状**：`SessionResourceMountService.java:153-167` 已支持 `{"type":"file","content":"...","filename":"..."}` 直接落盘；`fileId` 形式只会写一个占位 NOTE。控制面没有任何文件存储。

**设计决策（已定，不要更改）**：
Files 只存**文本**内容在 Postgres；resolve 时把 `{"type":"file","fileId":"file_x"}` 就地展开为带 `content` / `filename` 的资源。这样零 Java 改动即可打通。二进制文件与对象存储列入 vNext。

**改动文件**

- `aistio/internal/product/migrate.go`
- `aistio/internal/product/handlers_files.go`（新建）
- `aistio/internal/product/server.go`（`Register` 里加 `s.registerFiles(r)`）
- `aistio/internal/product/handlers_internal.go`（resolve 展开）
- `service-gateway/src/main/resources/application.yml`（新前缀必须加进 `control-api`）
- `frontend/src/api/files.ts`（新建）、Session 创建处的挂载 UI

**实施步骤**

1. `migrate.go` 追加表：

```sql
CREATE TABLE IF NOT EXISTS files (
    row_id       BIGSERIAL PRIMARY KEY,
    file_id      TEXT NOT NULL UNIQUE,
    owner_id     TEXT NOT NULL,
    filename     TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'text/plain',
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    content      TEXT NOT NULL DEFAULT '',
    created_at   BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_files_owner ON files (owner_id);
```

2. 新建 `handlers_files.go`：

```go
func (s *Server) registerFiles(r gin.IRouter) {
	r.GET("/api/files", s.listFiles)
	r.POST("/api/files", s.createFile)
	r.GET("/api/files/:id", s.getFile)
	r.GET("/api/files/:id/content", s.getFileContent)
	r.DELETE("/api/files/:id", s.deleteFile)
}
```

   - `createFile` body：`{"filename":"notes.md","content":"...","contentType":"text/markdown"}`
   - **大小上限 1 MiB**（`len(content) > 1<<20` → 400 `"file too large"`）
   - `filename` 必须非空，且不允许包含 `/` 或 `..`（防路径穿越）
   - 元数据响应（不含 content）：`{"id","filename","contentType","sizeBytes","createdAt"}`
   - `getFileContent` 才返回 `{"id","filename","content"}`
   - 全部按 `owner_id` 隔离

3. `server.go:64` 的 `Register` 里加 `s.registerFiles(r)`。

4. `handlers_internal.go` 的 `internalResolveSession`：把第 137 行

```go
"resources": parseJSONRaw(deref(sess.ResourcesJSON)),
```

   改为先经过展开函数：

```go
"resources": s.expandFileResources(c.Request.Context(), sess.OwnerID, parseJSONRaw(deref(sess.ResourcesJSON))),
```

   `expandFileResources` 逻辑：遍历数组，对 `type == "file"` 且有 `fileId` 且**没有** `content` 的项，按 owner + file_id 查库，命中则补 `content` 与 `filename`（已有 `filename` 时保留调用方的）；查不到就原样返回（数据面会写占位 NOTE，行为不回退）。

5. **网关必须改**（这是本期唯一的网关改动）：`application.yml` 中 `id: control-api` 的 `Path=` 追加 `,/api/files,/api/files/**`。

6. 前端：`api/files.ts` 提供 `listFiles` / `createFile` / `getFile` / `deleteFile`；在创建 Session 的表单里允许选择已上传文件，构造 `resources: [{"type":"file","fileId":"..."}]`。

**接口契约**

```http
POST /api/files
{ "filename": "brief.md", "content": "# Brief\nship it", "contentType": "text/markdown" }
→ 200 { "id":"file_abc","filename":"brief.md","contentType":"text/markdown","sizeBytes":21,"createdAt":1730000000000 }
```

```http
POST /api/sessions
{ "agent":"agt_x", "environmentId":"env_y", "resources":[{"type":"file","fileId":"file_abc"}] }
```

**验收**

```bash
F=$(curl -s -X POST "$BASE/api/files" "${AUTH[@]}" \
  -d '{"filename":"brief.md","content":"MAGIC-TOKEN-12345"}' | jq -r .id)
curl -s "$BASE/api/files/$F/content" "${AUTH[@]}" | jq -r .content   # MAGIC-TOKEN-12345

S=$(curl -s -X POST "$BASE/api/sessions" "${AUTH[@]}" \
  -d "{\"agent\":\"$AGENT_ID\",\"environmentId\":\"$ENV_ID\",\"resources\":[{\"type\":\"file\",\"fileId\":\"$F\"}]}" | jq -r .id)

curl -s -X POST "$BASE/api/sessions/$S/events" "${AUTH[@]}" \
  -d '{"events":[{"type":"user.message","payload":{"text":"Read resources/brief.md and repeat its exact content."}}]}' >/dev/null
sleep 12
curl -s "$BASE/api/sessions/$S/events" "${AUTH[@]}" | grep -c 'MAGIC-TOKEN-12345'
# 期望 >= 1

# 超限保护
python3 -c "print('{\"filename\":\"big.txt\",\"content\":\"' + 'a'*1100000 + '\"}')" > /tmp/big.json
curl -s -X POST "$BASE/api/files" "${AUTH[@]}" --data @/tmp/big.json -o /dev/null -w '%{http_code}\n'
# 期望 400

# 路径穿越保护
curl -s -X POST "$BASE/api/files" "${AUTH[@]}" -d '{"filename":"../evil","content":"x"}' \
  -o /dev/null -w '%{http_code}\n'
# 期望 400
```

**不要做**：不要做二进制上传 / multipart；不要接对象存储；不要改 `SessionResourceMountService.java`。

### M4 完成定义

- [ ] `PATCH /api/sessions/:id` 生效于下一轮，且拒绝 tools/mcp 键
- [ ] `/api/internal/sessions/:id/overrides` 可被数据面调用
- [ ] Files CRUD 可用，含大小与文件名校验
- [ ] 挂载文件后 agent 能读到真实内容（MAGIC-TOKEN 验收通过）
- [ ] 网关 `control-api` 已包含 `/api/files/**`
- [ ] 零 Java 文件改动（`git status` 中 `service-dataplane/` 无变更）

---

## M5 · 对外集成面

**故事**：第三方能按一份 OpenAPI 集成；Console 在数据量变大时不掉队。

### T5.1 列表分页

**目标**：大列表可分页，且**完全向后兼容**。

**设计决策（已定）**：采用 offset 分页 + 响应头计数，**保持响应体仍是裸数组**，避免破坏所有现有前端调用。

- 查询参数：`?limit=<1..500>&offset=<>=0>`
- 不传 `limit` → 行为与今天完全一致（全量返回）
- 响应头：`X-Total-Count`（该 owner 的总数）

**改动文件**：`handlers_agents.go`、`handlers_sessions.go`、`handlers_env.go`、`handlers_deploy.go`、`handlers_memory.go`、`handlers_vault.go`

**实施步骤**

1. 在 `server.go` 加共享工具：

```go
// pageParams parses optional offset pagination. A zero limit means "no limit",
// which preserves the historical full-dump behaviour.
func pageParams(c *gin.Context) (limit, offset int, ok bool) { /* 解析 + 校验，非法返回 ok=false */ }

func appendPage(q string, limit, offset int, args []any) (string, []any) { /* 追加 LIMIT/OFFSET 占位符 */ }
```

2. 每个 list handler：先 `COUNT(*)` 写 `X-Total-Count` 头，再按分页查询。非法参数返回 400。

**验收**

```bash
curl -s "$BASE/api/agents" "${AUTH[@]}" | jq 'type'                    # "array"
curl -si "$BASE/api/agents?limit=1" "${AUTH[@]}" | grep -i x-total-count
curl -s "$BASE/api/agents?limit=1" "${AUTH[@]}" | jq length            # 1
curl -s "$BASE/api/agents?limit=abc" "${AUTH[@]}" -o /dev/null -w '%{http_code}\n'  # 400
cd agentscope-service/frontend && npm run build                        # 现有页面不受影响
```

### T5.2 OpenAPI 文档

**目标**：产出一份手写的 OpenAPI 3.1 文件，覆盖本期承诺面。

**改动文件**：`agentscope-service/docs/openapi/managed-agents.yaml`（新建）、`docs/WIP/MANAGED_AGENTS_API.md`（加链接）

**实施步骤**

1. 覆盖范围严格等于 §8.2 承诺面表格；**不要**包含 `/api/internal/*` 与 Operate 的 `/api/v1/*`。
2. `securitySchemes` 用 `bearerAuth`（JWT）；deployment webhook 路径显式标注 `security: []`。
3. 错误响应统一引用一个 `Error` schema：`{ "error": "string" }`。
4. 用一个纯离线检查兜底（不引依赖）：

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('docs/openapi/managed-agents.yaml')); print(len(d['paths']))"
```

**验收**：文件可被 YAML 解析；`paths` 条目数 ≥ 30；文中每条路径都能在 Go 代码里 grep 到对应注册。

### T5.3 错误码与文案对齐

**目标**：前端错误提示与后端错误体一致。

**改动文件**：`docs/WIP/DATA_PLANE_CONTRACT.md`（补控制面错误表）、前端各 `api/*.ts` 的错误解析

**实施步骤**：控制面错误体统一为 `{"error": "..."}`（§3.2）。前端在各 api 文件中优先解析 JSON 的 `error` 字段，失败再回退到状态码文案（`api/tools.ts:63-73` 的 `readError` 是可参考的写法，注意它读的是 `message`，控制面用的是 `error`，两者都要兼容）。

**验收**：故意触发 404 / 400，UI 上出现后端给的具体文案而不是裸状态码。

### M5 完成定义

- [ ] 六个列表接口支持 `limit` / `offset` 且默认行为不变
- [ ] `X-Total-Count` 正确
- [ ] OpenAPI 文件存在且可解析，与代码一致
- [ ] 前端错误提示读取 `error` 字段
- [ ] 全量回归：M1–M4 的验收命令重跑一遍全绿

---

## 7. 信息架构与文案（Console Build）

保持现有分区，只补断点，不新造 Dashboard：

```text
Build
├── Agents            作者态中心
│   ├── Chat          Session 运行（HITL 可见）
│   ├── Settings / Tools / Skills / Workspace / Subagents
│   ├── Sessions      历史与状态（M1）
│   └── Channels
├── Environments      可编辑（M2）
├── Memory Stores     归档 / 脱敏（M3）
├── Vaults            更新 / 校验（M3）
├── Deployments       webhook / pause / 深链（M2）
└── Channels (hub)
```

统一文案口径（英文 UI 文案，参照 Claude Code 的分工但不使用其品牌元素）：

- **Tools**：模型可调用的动作（内置 / 自定义）
- **MCP**：外接系统的工具来源（凭证放 Vault）
- **Skills**：按需加载的工作流与领域知识
- **Subagents**：上下文隔离的委托配置
- **Memory**：跨 Session 的持久记忆（挂载到 Session）
- **Workspace**：该 Agent 的文件资产（区别于全局 Files）

---

## 8. API 契约演进

### 8.1 原则

1. 只增不改：新增字段 / 新增路由；不改已有字段名与语义。
2. Agents 继续用 `PUT` 更新（Claude 用 `POST`），文档注明差异即可，不加别名。
3. Session 生命周期归控制面，events 归数据面；网关顺序不可回退。
4. `/api/internal/*` 永不进公开文档。
5. 控制面错误体固定 `{"error": "..."}`。

### 8.2 本期承诺面（M5 后冻结 minor）

| 资源 | 承诺路由 |
|---|---|
| Agents | `GET/POST /api/agents`、`GET/PUT/DELETE /api/agents/{id}`、`POST /api/agents/{id}/archive`、`GET /api/agents/{id}/versions[/{v}]` |
| Environments | `GET/POST /api/environments`、`GET/PATCH/DELETE /api/environments/{id}`、`POST …/archive`、`POST …/rotate-key` |
| Sessions | `GET/POST /api/sessions`、`GET/PATCH/DELETE /api/sessions/{id}`、`POST …/archive` |
| Events（DP） | `GET/POST /api/sessions/{id}/events`、`GET …/events/stream` |
| Memory | store CRUD + `POST …/archive` + `POST …/redact` + memories `*path` 读写删 + versions |
| Vaults | vault CRUD + `PATCH` + `POST …/archive`；credentials list/create/**patch**/delete/**validate** |
| Deployments | CRUD + archive + run + **pause/unpause** + **webhook** |
| Files | `GET/POST /api/files`、`GET /api/files/{id}[/content]`、`DELETE /api/files/{id}` |
| Channels | 现有 channel 与 binding 路由 |

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| webhook URL 泄露即可触发 | 响应不回显 token；pause 立即失效；文档标注按密钥保管；后续加限流 |
| credential validate 触发 SSRF | 只允许 http/https；5 秒超时；探测失败不阻断 |
| redact 误操作不可逆 | UI 二次确认；文档明确不可逆 |
| Files 撑爆数据库 | 1 MiB 上限 + 纯文本；二进制走 vNext |
| 分页改动破坏现有前端 | 不传 `limit` 行为不变；响应体仍是裸数组 |
| 实施者误入 Operate 范围 | §0.3 硬性规则 1；review 时检查 `git status` 无 `internal/httpapi`、`sessionops`、`features/operate` 改动 |
| overrides 白名单被绕过 | 服务端强校验并返回 400，不依赖前端限制 |

---

## 10. vNext（登记，不排期）

- Session `tools` / `mcpServers` 覆盖（需 `HarnessAgentBuildService` 消费）
- 一等 Skills 资源与跨 Agent 引用
- Multiagent Threads 公开 API 与 Console 线程视图
- Outcomes 完整评价环
- 真实 compaction / compress 产品化
- 二进制 Files 与对象存储
- MCP OAuth 刷新闭环
- Environment packages / networking 与 Claude cloud 对等（见 SANDBOX_GAPS）
- Plugins / Marketplace

---

## 11. 决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 成功标准 | Console 与 API 并重，故事切片 | 用户指定 |
| 主对标 / 辅参照 | Claude MA / Claude Code | 资源模型 + 作者态心智 |
| Operate | 排除 | 用户明确要求 |
| Session 覆盖范围 | 仅 system/model/maxIters/name/description | 这些已被 harness 消费（§2.6） |
| Files 形态 | 文本 + 库内存储 + resolve 展开 | 零 Java 改动即可打通 |
| Redact 实现 | 覆盖内容 + 清历史，无新列 | 避免迁移与状态机复杂度 |
| 分页 | offset + `X-Total-Count` 头，响应体不变 | 完全向后兼容 |
| MCP catalog | 静态内置清单 | 不引入外部依赖与 Operate 耦合 |
| API 前缀 | 保持 `/api/*` | 语义对齐即可 |
| 工期 | M0–M5 约 6–9 周（单人） | M4 已因 §2.6 显著降本 |

---

## 12. 参考

**Claude Managed Agents**

- https://platform.claude.com/docs/en/managed-agents/overview
- https://platform.claude.com/docs/en/managed-agents/reference
- https://platform.claude.com/docs/en/managed-agents/agent-setup
- https://platform.claude.com/docs/en/managed-agents/tools
- https://platform.claude.com/docs/en/managed-agents/sessions
- https://platform.claude.com/docs/en/managed-agents/mcp-connector

**Claude Code**

- https://code.claude.com/docs/en/how-claude-code-works
- https://code.claude.com/docs/en/features-overview
- https://code.claude.com/docs/en/skills
- https://code.claude.com/docs/en/mcp

**本仓库**

- [MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md)
- [DATA_PLANE_CONTRACT.md](DATA_PLANE_CONTRACT.md)
- [SELF_HOSTED_GAPS.md](SELF_HOSTED_GAPS.md) · [SANDBOX_GAPS.md](SANDBOX_GAPS.md) · [FOLLOW_UP_PRODUCTION.md](FOLLOW_UP_PRODUCTION.md)
- [guide/01-overview.md](../guide/01-overview.md) · [guide/12-limitations.md](../guide/12-limitations.md) · [guide/14-validation.md](../guide/14-validation.md)
