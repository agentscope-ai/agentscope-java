# Sessions · 会话生命周期

[← Environments](05-environments.md) · [回目录](README.md) · [下一页：Events →](07-events.md)

---

**Session** 是 Agent × Environment 的一次有状态运行：事件追加写、可 SSE 订阅、可中断与 HITL。

基路径：`/api/sessions`。

## 创建

```bash
curl -s -X POST "$BASE/api/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "agent": "agt_xxx",
    "environmentId": "env_xxx",
    "memoryStoreIds": [],
    "vaultIds": [],
    "resources": []
  }'
```

| 字段 | 说明 |
|---|---|
| `agent` | 字符串 agent id，**或**对象（含 `id`、可选 `version` / overrides） |
| `environmentId` | **必填**，未归档 Environment |
| `memoryStoreIds` | 可选，跨会话 Memory Store |
| `vaultIds` | 可选，凭证 Vault |
| `resources` | 可选挂载声明；**file 类型目前多为占位**，见 [Limitations](12-limitations.md) |

创建后状态为 `created`，并写入 `session.status_created`。

## 状态机

```text
created
   │
   ▼
running ──► idle
   │          ▲
   ├──► requires_action ──(tool_confirmation)──► running
   │
   └──► terminated   （session.error 后不可恢复失败）

任意时刻可 archive → archived
```

| 状态 | 含义 |
|---|---|
| `created` | 已建、尚未跑 turn |
| `running` | 正在执行 turn |
| `idle` | turn 正常结束，可继续发 `user.message` |
| `requires_action` | 等待 HITL |
| `terminated` | 不可恢复错误（伴随 `session.error`） |
| `rescheduled` | 预留 |
| `archived` | 已归档 |

对应出站事件多为 `session.status_<status>`。

## 主要 API

| Method | Path | 说明 |
|---|---|---|
| `POST` | `/api/sessions` | 创建 |
| `GET` | `/api/sessions` | 列表（可选 `agentId`） |
| `GET` | `/api/sessions/{id}` | 详情 |
| `POST` | `/api/sessions/{id}/archive` | 归档 |
| `DELETE` | `/api/sessions/{id}` | 删除会话与事件 |
| `POST` | `/api/sessions/{id}/events` | 投递入站事件（整批失败则不部分提交） |
| `GET` | `/api/sessions/{id}/events` | 历史（可选 `after` 序号） |
| `GET` | `/api/sessions/{id}/events/stream` | SSE；可选 `after=` 序号游标、重复 `event_deltas=` |
| `GET` | `/api/sessions/{id}/hands-stats` | Hands 租约指标 |

## 驱动运行

1. **发消息**：`user.message`，`payload.text`（也认 `message` / `content`）。
2. **中断**：`user.interrupt`。本机有活跃 turn 时直接打断；否则写入协调库 interrupt ticket，由持有 turn lease 的 data 副本在心跳中消费并取消。
3. **HITL**：`user.tool_confirmation`，字段优先 `tool_use_id`（兼容 `toolUseId`），以及 `allow` / `denyMessage`。
4. **Session 级 system**：`system.message` 写入 Session overrides，**不写回 Agent**，下轮生效。

### SSE 语义（四层拆分后）

- 持久化事件（含 control 写的 `session.status_*` / `session.updated`）经 **DB 游标轮询** fan-out，任意 data 副本上的 SSE 都能看到。
- 断线重连请带 `?after=<lastSeq>`，避免丢事件。
- `event_deltas` 预览（流式 token，不落库）仍是进程内 best-effort：多副本时只有跑 turn 的实例能推预览，持久化事件不受影响。

驱动 turn 的入站类型：`user.message`、`user.interrupt`、`user.tool_confirmation`。  
续跑外化工具：`user.tool_result` / `user.custom_tool_result`（**已接线**；Worker 也可用 `POST …/tool-results`）。  
仍为落库骨架：`user.define_outcome`。未知 `type` → **400** `unknown_event_type`。完整表见 [events/README.md](../events/README.md)。

## 与部署 / 渠道

- Deployments 创建 Session 并跑 turn（可自动 default environment）。
- IM / ChatUI 桥接也会落到 managed Session 事件流。

产品集成请以本模块 HTTP 为准；旧 `/api/agents/{id}/chat/*` 已随四层拆分移除。
