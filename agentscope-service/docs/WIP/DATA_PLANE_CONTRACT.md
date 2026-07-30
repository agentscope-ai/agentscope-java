# 数据面契约补齐：Events / Worker / 错误模型

> 相对 Claude Managed Agents，优先补齐 **事件契约**、**Hands Worker 管理面**、**统一错误模型**，使 CLI / UI / Console / Worker 共用稳定数据面。  
> **未发布：直接改到位，不做 deprecated / 双写兼容。**
>
> 范围外（另文）：Agent body 收拢 → [API_REFACTOR.md](API_REFACTOR.md)；多副本 interrupt / Redis 等 → [FOLLOW_UP_PRODUCTION.md](FOLLOW_UP_PRODUCTION.md)；资源缺口总表 → [MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md)。
>
> 对照：[Claude MA reference（事件）](https://platform.claude.com/docs/en/managed-agents/reference)、Environments work API、`/api/sessions/*/events*`、`WorkerEnvironmentController`。
>
> 最后更新：2026-07-21  
> 状态：**已落地**（S0–S5）。细节见 [events/](events/README.md)。

---

## 0. 目标与优先级

| # | 主题 | 目标 | 状态 |
|---|---|---|---|
| 1 | **出站事件对齐** | 稳定 `{domain}.{action}` 命名与 payload；落库事件可重放 | 完成 |
| 2 | **入站事件补齐** | 驱动运行时的 user/system 事件齐全，未知 type 返回明确错误 | 完成（骨架项已标） |
| 3 | **`event_deltas`** | SSE 可选流式预览，**永不落库** | 完成 |
| 4 | **Worker 管理** | poll / ack / heartbeat / stop / list / get / stats + env key | 完成 |
| 5 | **统一错误模型** | HTTP 4xx/5xx 与 `session.error` 共用类型化错误体 | 完成 |

建议实现顺序：**错误模型骨架 → 出站重命名/补齐 → 入站驱动 → event_deltas → Worker API 重整**（错误模型先落地，后面事件与 Worker 都复用）。

---

## 1. 出站事件（Outbound / Persisted）

### 1.1 目标目录（相对 Claude）

| 类别 | 目标 `type` | 今日 | 动作 |
|---|---|---|---|
| Agent | `agent.message` | 有（payload 偏 `text`） | 对齐 content blocks（至少 text） |
| Agent | `agent.thinking` | `agent.reasoning` | **改名**为 `thinking` |
| Agent | `agent.tool_use` | 有 | 保留；补 `id` / `name` / `input` 稳定字段 |
| Agent | `agent.tool_result` | 有 | 保留；与 tool_use id 关联 |
| Agent | `agent.mcp_tool_use` / `agent.mcp_tool_result` | 无（可能混在 tool_use） | MCP 与内置工具分型 |
| Agent | `agent.custom_tool_use` | 无 | 自定义工具：发事件后 idle，等 `user.custom_tool_result` |
| Agent | `agent.thread_context_compacted` | 无 | harness 压缩时发出（有则接，无则后置） |
| Session | `session.status_running` / `_idle` / `_rescheduled` / `_terminated` | `session.status_*` 部分有；另有 `agent_start/end` | **统一**为 Claude 四态；删除或并入多余别名 |
| Session | `session.error` | 多为异常冒泡 / 无结构化事件 | 必发；含 typed `error` |
| Session | `session.updated` / `session.deleted` | 弱 / 无 | 补 |
| Session | `session.interrupted` | 有；另有 `interrupt_requested` | 保留 interrupt；跨副本请求可进 payload，不另造对外 type（或仅内部） |
| Span | `span.model_request_start` / `_end` | `agent.model_call_*` | **改名**为 `span.*`，usage 放 end |
| Thread | `session.thread_*` / `agent.thread_message_*` | multiagent 未对外 | 本迭代可只预留类型表；实现可跟 Threads API |

今日落库的 `agent.tool_use_delta`：与 Claude「deltas 不落库」冲突 → **删除落库**；流式增量只走 §3 `event_deltas`。

### 1.2 落库事件统一信封

```jsonc
{
  "id": "evt_...",
  "session_id": "sess_...",
  "sequence": 42,
  "type": "agent.message",
  "created_at": "2026-07-21T12:00:00Z",
  "payload": { /* type-specific */ }
}
```

- `GET …/events`、`GET …/events/stream`（无 deltas）只返回**已持久化**事件。  
- 每种 `type` 在文档中附 JSON Schema（可放 `docs/events/`）。

### 1.3 实现要点

- 集中 `SessionEventMapper`：`AgentEvent` → 目标 type + payload；禁止各处手写字符串。  
- `SessionTurnRunner.handleAgentEvent` 只调 mapper。  
- 状态机：turn 开始 → `session.status_running`；正常结束 → `session.status_idle`（`stop_reason`）；不可恢复 → `session.status_terminated` + `session.error`。

---

## 2. 入站事件（Inbound）

### 2.1 目标行为表

| `type` | 行为 | 优先级 |
|---|---|---|
| `user.message` | 已有：append + run turn | 保持；content 对齐 blocks |
| `user.interrupt` | 已有：中断 turn | 保持 |
| `user.tool_confirmation` | 已有：HITL | 保持；字段名与 Claude 对齐（`tool_use_id` 等，一次改名） |
| `user.custom_tool_result` | 响应 `agent.custom_tool_use`；注入结果并续跑 | **已落地**（Brain 续跑接线；Worker 自定义工具 SPI 为 follow-up） |
| `user.tool_result` | `self_hosted`：Worker/客户端回传外化工具结果并续跑 | **已落地**（`ManagedSessionApiController` + `SelfHostedWorkerController`） |
| `user.define_outcome` | 记录 outcome；驱动评估循环（若 harness 暂无则先持久化 + 文档标明支持度） | **补骨架** |
| `system.message` | 更新本 session 有效 system（不写回 Agent）；下轮生效 | **补** |
| 未知 type | **400** + 统一错误体；**禁止**静默 append | **改** |

### 2.2 API

- 仍：`POST /api/sessions/{id}/events`，body `{ "events": [ ... ] }`。  
- 每个 event：`type` + 类型化字段（不要无 schema 的自由 `payload` 作为对外契约；内部可 map）。  
- 部分失败策略：默认 **整批失败不部分提交**（更易 SDK）；若需 partial 再显式 `continue_on_error`（本迭代可不做）。

---

## 3. `event_deltas`（流式预览，不落库）

对齐 Claude：`GET /api/sessions/{id}/events/stream?event_deltas=agent.message&event_deltas=agent.thinking`

### 3.1 流专用 type（永不进 EventLog）

| Stream-only type | 含义 |
|---|---|
| `event_start` | 即将产生某持久化 type；带 `event_id`（预分配或临时 id）与 `type` |
| `event_delta` | 增量文本/片段；`event_id` + `delta` |
| （最终）同名持久化事件 | turn 结束写入 EventLog 的完整 `agent.message` / `agent.thinking` 等 |

### 3.2 行为规则

1. 未带 `event_deltas`：SSE 行为与今日一致，只推已落库事件（或落库后立刻推）。  
2. 带参：对勾选的 type 先推 `event_start` / `event_delta`，完整事件落库后再推一条最终事件；客户端用 `event_id` 对齐。  
3. `GET …/events`（历史）**永远看不到** delta。  
4. 删除今日落库的 `agent.tool_use_delta`；工具流式若需要，同样走 `event_deltas=agent.tool_use`（或首期只支持 message/thinking）。

### 3.3 实现要点

- `SessionEventLog.subscribe` 与「预览总线」分离：`PreviewBus` 仅进程内/持有 turn 的实例推 SSE。  
- 多副本：非 owner 的 SSE 可能收不到 deltas（与 FOLLOW_UP 跨副本一致）；文档写明 **deltas 仅 best-effort / sticky 到 turn owner**；落库事件仍可通过 list 补齐。

---

## 4. Worker 管理 API

对齐 Claude ` /v1/environments/{id}/work/*`，在 Builder 上重整（未发布，可改路径与语义）。

### 4.1 目标路由

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/api/environments/{id}/work/poll` | 长轮询认领（替换今日 `…/work/claim`） |
| `GET` | `/api/environments/{id}/work` | 列表（state 过滤） |
| `GET` | `/api/environments/{id}/work/{workId}` | 单条 |
| `GET` | `/api/environments/{id}/work/stats` | 队列统计 |
| `POST` | `/api/environments/{id}/work/{workId}/ack` | 确认认领 → `active`（事件驱动模型下 **不** 在 Brain 建 WorkspaceSandbox） |
| `POST` | `/api/environments/{id}/work/{workId}/heartbeat` | 保活 |
| `POST` | `/api/environments/{id}/work/{workId}/stop` | 请求停止 |
| `POST` | `/api/environments/{id}/work/{workId}` | 更新（如 metadata、state 合法迁移） |

删除或一次性替换：`POST …/worker/register`、`GET …/work/claim`、`POST …/work/{leaseId}/ready`、`POST …/work/{leaseId}/complete` —— 语义并入 poll/ack/heartbeat/stop；`HandsWorkerMain` / `InProcessEnvironmentWorker` 同 PR 改掉。

### 4.2 Work 状态机

```text
queued → starting → active → stopping → stopped
              ↑_______________|  (heartbeat 超时 → 可 reclaim → queued)
```

- poll：取出 `queued`（或 reclaim 过期 `starting`/`active`）。  
- ack：→ `starting` / `active`（Worker 本地工作目录；Brain 不持有 live Sandbox）。  
- heartbeat：刷新 `latest_heartbeat_at`。  
- stop / complete：→ `stopped`。  
- stats：按 state 计数 + oldest queued age。

附加 session 数据面（env key）：`GET …/sessions/{sessionId}/pending-tools`、`POST …/sessions/{sessionId}/tool-results`、`GET …/sessions/{sessionId}/skills`。

### 4.3 鉴权

- 引入 **environment key**（创建 env 时生成一次、可 rotate）：Worker 用 header（如 `X-Builder-Environment-Key`），**不必**用人 JWT。  
- Console 的 list/stats 仍可用用户 JWT + RUN/EDIT。  
- 密钥只在 create/rotate 响应中明文出现一次。

### 4.4 与入站 `user.tool_result`

**已落地。** `self_hosted` 下内置 hands 工具外化为 SchemaOnlyTool；挂起后 Worker（或客户端）通过：

- `POST /api/environments/{id}/sessions/{sessionId}/tool-results`，或
- `POST /api/sessions/{id}/events` 的 `user.tool_result` / `user.custom_tool_result`

按 `tool_use_id` 组装 `ToolResultBlock` 并由 `SessionTurnRunner.resumeWithToolResults` 续跑。参考执行器：`HandsWorkerMain` / `InProcessEnvironmentWorker`。

---

## 5. 统一错误模型

### 5.1 HTTP 错误体（所有 `/api/**` MA 相关）

```jsonc
{
  "type": "error",
  "error": {
    "type": "invalid_request_error",  // 见下表
    "code": "unknown_event_type",     // 稳定机器码
    "message": "Unknown event type: foo.bar",
    "param": "events[0].type",        // 可选
    "session_id": "sess_..."          // 可选
  }
}
```

| `error.type` | HTTP | 场景 |
|---|---|---|
| `invalid_request_error` | 400 | 参数 / 未知 event type / 非法状态迁移 |
| `authentication_error` | 401 | 未登录 / 坏 key |
| `permission_error` | 403 | ACL |
| `not_found_error` | 404 | 资源不存在 |
| `conflict_error` | 409 | turn 租约冲突、archived、version mismatch |
| `rate_limit_error` | 429 | 预留 |
| `api_error` | 500 | 未分类服务端错误 |

实现：`@ControllerAdvice` + 将 `ResponseStatusException` / 业务异常映射到上述结构；逐步去掉裸字符串 body。

### 5.1.1 控制面（aistiod product）简化错误体

Console / 控制面 `/api/agents|environments|sessions|…` 当前统一为：

```json
{ "error": "human readable message" }
```

| HTTP | 典型文案 |
|---|---|
| 400 | `invalid body` / `unsupported override key: tools` / `file too large` |
| 401 | `missing bearer token` / `invalid token` |
| 404 | `… not found`（不区分无权限与不存在） |
| 409 | `session is archived` / `environment is archived` |
| 500 | 内部错误字符串 |

前端通过 `api/http.ts` 的 `readApiError` 优先解析 `error`，兼容遗留 `message` 字段。

### 5.2 `session.error` 事件

```jsonc
{
  "type": "session.error",
  "payload": {
    "error": {
      "type": "api_error",
      "code": "model_call_failed",
      "message": "...",
      "retry_status": "not_retrying"  // retrying | not_retrying | exhausted
    }
  }
}
```

- Turn 失败：写 `session.error`，再视情况 `session.status_idle`（可恢复）或 `session.status_terminated`。  
- HTTP 与事件共用同一 `error` 对象形状。

---

## 6. 落地步骤（直接改造）

| Step | 内容 | 产出 |
|---|---|---|
| **S0** | 错误体 + `ControllerAdvice`；关键 API 单测 | 统一 HTTP 错误 |
| **S1** | `SessionEventMapper`；出站改名/删 `tool_use_delta` 落库；状态四态 | 可重放事件日志 |
| **S2** | 入站：未知 type 400；`tool_result` / `custom_tool_result` **已续跑**；`define_outcome` 仍骨架 | 完整入站表 |
| **S3** | SSE `event_deltas` + PreviewBus；UI 可选接入打字机 | 流式预览 |
| **S4** | Worker 路由按 §4 替换；environment key；改 `HandsWorkerMain` | 可运维 work 队列 |
| **S5** | `docs/events/*.md` + 更新 MANAGED_AGENTS_API | 对外契约 |

同 PR 改前端事件名消费处；不留旧 type 别名。

---

## 7. 验收

- [x] 事件文档列出全部入站 / 出站 / stream-only type；无未文档化的对外 type。  
- [x] 仓库无 `agent.reasoning`、`agent.model_call_*`、落库的 `agent.tool_use_delta`、`session.agent_start/end` 等旧对外名。  
- [x] `POST …/events` 未知 type → 400 + 统一错误体。  
- [x] `event_deltas` 开启时 SSE 有 `event_start`/`event_delta`；`GET …/events` 无 delta。  
- [x] Worker：poll/ack/heartbeat/stop/list/get/stats 可用；旧 claim/ready/complete 路由不存在。  
- [x] Environment key 可认证 worker；人 JWT 与 key 职责分离。  
- [x] Turn 失败同时具备 `session.error` 的类型化 `error`。

---

## 8. 相关文档

| 文档 | 职责 |
|---|---|
| [MANAGED_AGENTS_API.md](MANAGED_AGENTS_API.md) | 现状 / 总缺口 |
| [API_REFACTOR.md](API_REFACTOR.md) | Agent body 收拢 |
| **本文** | 数据面：事件、deltas、Worker、错误模型 |
| [FOLLOW_UP_PRODUCTION.md](FOLLOW_UP_PRODUCTION.md) | 多副本 SSE/interrupt、跨机 Hands 等 |
