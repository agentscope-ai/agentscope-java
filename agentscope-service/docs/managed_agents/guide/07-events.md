# Events · SSE、HITL 与错误

[← Sessions](06-sessions.md) · [回目录](README.md) · [下一页：Hands / Worker →](08-hands-worker.md)

---

本章是 **How-to**：怎么订阅流、怎么做打字机、怎么确认工具、怎么读错误。完整类型表与支持度（Full / Skeleton / Reserved）见 **[events/README.md](../events/README.md)**，此处不重复维护。

## 打开 SSE

```bash
curl -N "$BASE/api/sessions/$SESSION_ID/events/stream" \
  -H "Authorization: Bearer $TOKEN"
```

每帧：`event:` 为事件 `type`，`data:` 为 JSON（含 `id`、`sessionId`、`seq`、`type`、`payload`、`createdAt` 等）。

### 流式预览（不落库）

```bash
curl -N "$BASE/api/sessions/$SESSION_ID/events/stream?event_deltas=agent.message&event_deltas=agent.thinking" \
  -H "Authorization: Bearer $TOKEN"
```

| 流专用 type | 含义 |
|---|---|
| `event_start` | 即将产生某持久化类型；payload 含 `event_id`、`type` |
| `event_delta` | 增量文本；payload 含 `event_id`、`type`、`delta` |

完整 `agent.message` / `agent.thinking` 仍会在落库后推送。  
`GET …/events` **永远看不到** delta。多副本下 deltas 仅 turn-owner best-effort。

## 投递入站

```bash
curl -s -X POST "$BASE/api/sessions/$SESSION_ID/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"type":"user.message","payload":{"text":"Hi"}}]}'
```

- 一批内任一失败 → 整批不部分提交。
- 未知 `type` → 400，错误码 `unknown_event_type`。

常用入站：`user.message`、`user.interrupt`、`user.tool_confirmation`、`system.message`。其余见事件文档。

## HITL 确认

当工具策略为 `always_ask`：

1. 流上出现 `session.requires_action` / `session.status_requires_action`。
2. 客户端展示工具名与输入。
3. 确认：

```bash
curl -s -X POST "$BASE/api/sessions/$SESSION_ID/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "events": [{
      "type": "user.tool_confirmation",
      "payload": {
        "tool_use_id": "call_xxx",
        "allow": true
      }
    }]
  }'
```

拒绝时设 `"allow": false`，可选 `denyMessage`。兼容字段名 `toolUseId`。

## 解析错误

HTTP 错误体（`/api/**`）：

```json
{
  "type": "error",
  "error": {
    "type": "invalid_request_error",
    "code": "unknown_event_type",
    "message": "…",
    "param": "events[].type"
  }
}
```

Turn 失败时还会落库 `session.error`，内层 `error` 形状相同（可含 `retry_status`）。详见 [events/errors.md](../events/errors.md)。

## 拉历史

```bash
# 全量
curl -s "$BASE/api/sessions/$SESSION_ID/events" -H "Authorization: Bearer $TOKEN"

# 游标
curl -s "$BASE/api/sessions/$SESSION_ID/events?after=42" -H "Authorization: Bearer $TOKEN"

# 按类型过滤（可重复 types=）
curl -s "$BASE/api/sessions/$SESSION_ID/events?types=agent.tool_use&types=agent.tool_result" \
  -H "Authorization: Bearer $TOKEN"
```

重连建议：先 list 种下已见 `id` / 最大 `seq`，再 `GET …/events/stream?after=<seq>&event_deltas=agent.message`。
