---
title: "Managed Session 事件 API"
description: "Draft 3 首版：事件受理、Turn 输入、历史、实时事件与独立结果。"
en_link: /v2/en/service/claude-session-api
---

本页描述 AgentScope Service 的 Draft 3 源码协议。部署需要单独升级；早期集群验收记录对应此前部署版本。SDK 兼容性仍按候选能力管理，固定版本及验证记录位于 `agentscope-service/tests/session-protocol/`。

Session 固定 Agent 和 Environment 配置。HTTP 成功表示持久受理，Input 的 applied 表示运行时已处理，Turn 的终态表示执行结果。MQ、checkpoint 和自动 Resume 继续保障执行可靠性。

## 创建与查询

`POST /v1/sessions` 接受 `agent`（ID 或版本引用）、`environment_id`、`title` 和字符串 metadata。先使用资源管理 API 准备 Agent 与 Environment。`GET /v1/sessions/{session}` 返回配置快照和真实状态；metadata 和事件不能改变调用者的作用域。

平台应用使用 `x-api-key`，或使用 Bearer 平台令牌，两者不能同时发送。原生作用域 Header 为 `X-AgentScope-Tenant` 和 `X-AgentScope-Namespace`。Claude 客户端使用 `anthropic-version: 2023-06-01` 和 `anthropic-beta: managed-agents-2026-04-01`；暂不支持 Anthropic Workspace 到平台作用域的映射。

## 统一交互入口

所有公开交互均使用 `POST /v1/sessions/{session}/events`：

```json
{"events":[{"type":"user.message","content":[{"type":"text","text":"分析性能"}],"agentscope_service":{"intent":"new_turn"}}]}
```

`new_turn` 创建独立 Turn，不允许指定 `turn_id`。`steer` 和 `inject` 使用 `user.message`，必须指定已有 `turn_id`。`cancel` 使用 `user.interrupt` 并指定 `turn_id`。Input 在安全的推理边界应用；工具运行期间，steer/inject 等待真实工具结果后继续执行，不会因此取消工具。

显式 intent 必须携带 `Idempotency-Key`。HTTP 200 按请求顺序返回 data、稳定的 Event 与 Turn/Input 身份以及冻结的受理回执。响应不确定时，用保留的原始 key 和相同请求体重试；当前状态另行查询。扩展事件批次原子受理 1–50 个事件，JSON 最大 1 MiB。物理执行在提交后开始，同一批次中的新 Turn 按数组顺序发布；并发 HTTP 到达顺序不定义执行顺序。

普通 Claude 调用支持空闲 Session 的单个 `user.message`、`user.interrupt`，或 interrupt 后跟一条普通消息。执行期间普通追加、多条普通消息、客户端工具回复和 thread 交互暂不支持。

## 查询执行和结果

以下路径均位于 `/v1/sessions/{session}`：

| 路径 | 用途 |
| --- | --- |
| `/turns`、`/turns/{turn}` | Turn 当前状态与身份 |
| `/turns/{turn}/inputs`、`/turns/{turn}/inputs/{input}` | Input 受理与处理状态 |
| `/turns/{turn}/output` | 独立最终结果，支持 after/limit 分页 |
| `/events` | 历史，支持 page/limit/order/types[] 和时间过滤 |
| `/events/stream` | 连接后处理的实时事件 |
| `/capabilities` | 协议版本、限制与启用能力 |

Turn 状态为 accepted/preparing/running/waiting_tool/waiting/completed/failed/cancelled；Input 状态为 accepted/applied/rejected。Resume 保留原 Turn，取消已受理不代表执行已停止。Session 状态为 idle/running/rescheduling/terminated；排队不等于 rescheduling。

Output 返回 `output_available`、`turn_status`、`data`、当前页的 `output_event_ids` 和 `next_cursor/has_more`。最终结果尚未生成时返回 200 和空集合。无结果终态与最终投影暂不可用不同，后者返回可重试的 503。SDK 的 `final_output/finalOutput` 先查询 Turn 等待终态，再分页读取结果，不依赖 SSE 或快照。

## 历史与实时事件

标准事件具有稳定的 id/type/processed_at 及类型相关字段。已受理但尚未处理的输入可见，`processed_at=null`。`created_at[gt/gte/lt/lte]` 比较 processed_at。历史返回 data/next_page，并固定查询上界和投影版本；翻页时保留作用域与查询条件。

`AgentScope-Extensions: turn-inputs-v1` 启用 `agentscope_service` 字段及 `agentscope_service.turn/input` 通知。标准流不包含这些扩展类型。官方 SDK 的 `beta=true` 传输参数可以使用，但不改变业务语义。

SSE 从连接后开始，不回放历史，不支持 after/Last-Event-ID/preview。重连时先打开并缓冲新流，再读取历史，最后按稳定 Event ID 去重合并；Console 使用此顺序。keepalive 不表示任务完成，观察者断线不取消执行，Turn 结束不关闭 Session 流；权限撤销会停止订阅。

## 首版能力边界

Session 列举/更新/归档/删除、文件、客户端工具确认/结果、workflow、thread、可靠流投递、snapshot 和 delta 均未启用。调用返回 `unsupported_capability`，Console 隐藏对应操作。当前可创建或按 ID 打开 Session、查询历史/结果，并提交 new_turn 或指定 Turn 的 steer/inject/cancel。

Java 和 TypeScript 使用 `ServiceClient.managedSessions()`，Python 使用 `managed_sessions()`。平台交互辅助方法均发送 events。一次调用内生成的 key 保持不变；跨调用重试时，应显式保留并复用原 key。

### 恢复与 Fork

Draft 3 首版未启用公开 checkpoint restore 和 fork。展示这些操作前先检查 `/capabilities`。运行时仍会在内部恢复已提交的 checkpoint；这不表示客户端有权替换 Session 的执行状态或创建分支。此前的 checkpoint 写入示例不适用于本协议版本。

{/* Preserve earlier anchors; current capability limits supersede the old examples. */}
<span id="创建-session-与提交-turn"></span>
<span id="归档删除与恢复"></span>
<span id="轮内输入"></span>
<span id="查询分页与订阅"></span>
<span id="快照一致性"></span>
<span id="checkpoint-元数据"></span>
<span id="文件与交付物"></span>
