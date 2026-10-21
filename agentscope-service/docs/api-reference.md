# AgentScope Service API Reference

[English](api-reference.en.md) · [API 快速体验](api-quickstart.md) · [事件与流式交互](events-and-streaming.md) · [项目介绍](../../README_zh.md)

本文覆盖当前仓库实现的公开 Managed Session 协议及控制面 API 目录。Session 协议版本为 `agentscope-service-2026-10-19-draft3`。接入前查询实际部署的 capabilities；源码中的路由声明不等于已部署或已开放能力。

## 目录

- [API 层级](#api-layers)
- [认证与通用约定](#authentication)
- [可用 Session API](#supported-session-api)
- [创建和查询 Session](#sessions)
- [发送消息与控制执行](#events-write)
- [平台扩展：Turn 与 Input 查询](#turns-and-inputs)
- [平台扩展：最终输出](#final-output)
- [历史、SSE 与事件类型](#observation)
- [分页](#pagination)
- [错误与限制](#errors)
- [Agent 与资源管理](#management)
- [控制面路由目录](#management-route-catalog)
- [机器协议、兼容入口和未开放能力](#boundaries)
- [实现依据](#implementation-sources)

<a id="api-layers"></a>
## Session/Events 兼容流程与平台扩展

快速体验采用 [Claude 官方 quickstart](https://platform.claude.com/docs/en/managed-agents/quickstart)和[事件指南](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)中的对话模式：创建 Session → 先开流 → 发送普通 `user.message` → 读取 `agent.message` / `session.status_idle`，用事件历史恢复观察，同时检查 `session.error`。当前兼容级别仍是已验证的 `candidate` 子集。

| 层级 | 范围 | 接入方式 |
| --- | --- | --- |
| Session/Events 兼容子集 | Session create/get，Events send/list/stream，普通文本消息和中断 | 主对话流程；无需 Turn ID、平台意图或响应扩展 Header |
| 平台管理 | Agent、Environment、Application、Namespace 等资源 | 使用本项目 `/api/v1/...`、`/api/...`；当前没有 Claude 风格的 `/v1/agents`、`/v1/environments` 管理别名 |
| 平台执行扩展 | `/capabilities`、`/turns/**`、定向 steer/inject/cancel、显式 `new_turn`、独立最终输出 | 需要这些能力时使用；`AgentScope-Extensions: turn-inputs-v1` 选择平台事件/关联信息 |

`/turns/{turn_id}/output` 是本项目独立结果读取能力，不属于 Claude Session/Events 快速体验的必要步骤。普通 `user.message` 在本兼容子集中要求 Session 空闲；活动期间的显式排队及定向输入另走平台扩展。首批没有实现上游文档中的全部 Agent、Environment、生命周期、tool confirmation、workflow、budget 或 delta 能力。

<a id="authentication"></a>
## 认证与通用约定

以公开 Gateway 地址作为 Base URL，例如显式本地开发模式中的 `http://localhost:18080`。下文路径均相对该地址。`{session_id}` 等大括号表示需要 URL 编码的路径参数，不要原样发送。JSON 使用 UTF-8；管理字段通常为 camelCase，Session 字段为 snake_case。

| Header | 含义 |
| --- | --- |
| `Authorization: Bearer <jwt>` | 用户 JWT；用于管理及已授权 Session |
| `X-API-Key: <key>` | Application 凭据；限制在已授予的 Session 目标和 scope |
| `X-AgentScope-Tenant` | 租户选择，示例使用 default |
| `X-AgentScope-Namespace` | Namespace 选择，仍需成员和资源授权 |
| `Content-Type: application/json` | JSON 请求正文 |
| `Idempotency-Key` | 建议创建 Session 和发送事件时提供；显式意图必填；1–256 字符 |
| `AgentScope-Extensions: turn-inputs-v1` | 选择响应中的平台 Turn/Input 信息与事件 |
| `anthropic-version: 2023-06-01` | 可选兼容 Header；其他值拒绝 |
| `anthropic-beta: managed-agents-2026-04-01` | 可选兼容 Header；不构成能力授权 |


`/v1/sessions` 的 `Authorization` 与 `X-API-Key` 不能同时使用。用户 JWT 由 `POST /api/auth/login` 获取，正文为 `{"username":"...","password":"..."}`，成功响应含 `token`。Application key 由 Application owner 创建，包含显式 scope 和资源目标，不授予任意管理资源访问权，也不能代表人工审批者。

作用域 Header 只选择上下文，不授予权限。创建 Session 需要目标 Agent 的 invoke 权限及所选资源访问权；事件、Turn/Input 和最终输出查询需要 read；消息写入需要 invoke；cancel/interrupt 需要 cancel。每次重试重新检查调用者，SSE 定期复核 read 权限。

`BUILDER_LOCAL_DEV=true` 使用显式开发身份；只有部署启用此模式时，空凭据才有效。内部服务 token、Environment Worker key 和 Task token 有独立信任边界，不能代替用户凭据。

兼容 Header 可选；不支持的版本、beta 值、`anthropic-workspace-id`、重复选择 Header 或未知扩展返回 400。这些 Header 不改变项目产品名称，也不意味着完整兼容其他服务。

Session 创建和事件提交成功时当前均返回 **200**。控制面创建操作通常返回 **201**，响应封装因操作而异，不能统一按 `{data: ...}` 解析。公开 Session 响应包含 `request-id` Header，错误正文也有 `request_id`。

<a id="supported-session-api"></a>
## 可用 Session API

### Session/Events 兼容子集

| Method | Path | 用途 |
| --- | --- | --- |
| `POST` | `/v1/sessions` | 创建并固定 Session |
| `GET` | `/v1/sessions/{session_id}` | 读取 Session |
| `POST` | `/v1/sessions/{session_id}/events` | 原子受理用户事件 |
| `GET` | `/v1/sessions/{session_id}/events` | 分页查询事件历史 |
| `GET` | `/v1/sessions/{session_id}/events/stream` | 观察未来已处理事件 |

### 平台执行扩展

| Method | Path | 用途 |
| --- | --- | --- |
| `GET` | `/v1/sessions/{session_id}/capabilities` | 查询协议与限制 |
| `GET` | `/v1/sessions/{session_id}/turns` | 查询已受理 Turn |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}` | 查询 Turn 当前状态 |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/capabilities` | 查询 Turn 交互能力 |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/inputs` | 查询 Turn 的 Input |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/inputs/{input_id}` | 查询单个 Input |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/output` | 读取已提交的最终消息 |


此清单同时考虑入口过滤器，而非只列 Controller 声明。当前协议未开放 `GET /v1/sessions` 列表、Session 修改/生命周期、snapshot/usage/resources 以及公开文件、checkpoint、webhook 操作。未知字段和查询参数会拒绝，不会静默忽略。相应 GET 请求可接受 SDK 传输标记 `beta=true`，它不是业务过滤条件。

<a id="sessions"></a>
## 创建和查询 Session

### POST /v1/sessions

```json
{
  "agent": {"type": "agent", "id": "<agent_id>", "version": 1},
  "environment_id": "<environment_id>",
  "title": "A product conversation",
  "metadata": {"source": "my-application"}
}
```


| 字段 | 必填 | 契约 |
| --- | --- | --- |
| `agent` | 是 | Agent ID 字符串，或 `{type:"agent", id, version?}`；ID 最长 256 字符，version 为正整数 |
| `environment_id` | 否 | 有权使用的可运行 Environment ID；缺省从 Agent/部署配置解析 |
| `title` | 否 | 最长 500 字符的字符串，或 null |
| `metadata` | 否 | 最多 16 个字符串键值对；键最长 64，值最长 512 字符 |

只接受这四个顶层字段。不要发送 `resources`、`memory_store_ids`、`agent_overrides` 或内联 Agent 定义；资源配置通过 Agent 管理接口绑定。重试时提供稳定的 `Idempotency-Key`；省略时服务端会生成键，客户端无法依靠自己的请求身份可靠重放。

响应直接返回 Session 资源，含 `type:"session"`、`id`、固定的 `agent` 引用、`environment_id`、`title`、`metadata`、`status`、创建/更新时间，以及已有真实计量的 `usage`。Session 固定所选 Agent 版本。选择 `turn-inputs-v1` 时，`agentscope_service` 还含运行类型、生命周期/配置版本和执行身份。空 `resources`、`stats` 或 null `budget` 是占位字段，不代表相关功能已经开放。

执行使用平台服务账号，同时保留原调用者授权；不接受任意执行身份。当前公开协议拒绝非空 Skill/多 Agent 配置、客户端工具确认/外部结果、不支持的工具名和 stdio MCP。HTTP MCP 需要对应的命名 toolset，启用工具的权限策略必须支持非交互执行。

### GET /v1/sessions/{session_id}

在 read 授权下返回 Session 当前视图。Session 的 `idle`、`running` 等状态概括对话状态，不等同于某个 Turn 的准确结果。空闲边界后可能立即执行排队 Turn。普通兼容对话通过事件流/历史中的 `agent.message`、`session.status_idle` 和 `session.error` 观察本次交互；需要精确跟踪指定 Turn 时再使用平台查询扩展。

### 平台扩展：GET /v1/sessions/{session_id}/capabilities

返回 `session_id`、`runtime_kind`、`protocol_revision`、`compatibility`、`extensions`、`features`、`capabilities`、`limits`、`input_event_types`、`intents`、`content_types`。当前声明支持文本、`user.message`/`user.interrupt`、`new_turn`/`steer`/`inject`/`cancel`、`turn-inputs-v1:true` 及 `turn_output:true`。durable events、snapshot、Session 列表/修改/生命周期和其他后续功能仍为 false。兼容级别为 `candidate`，不构成完整 SDK 兼容保证。

<a id="events-write"></a>
## 发送消息与控制执行

### POST /v1/sessions/{session_id}/events

```json
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Summarize the incident."}]
  }]
}
```

以上为用于空闲 Session 的普通兼容请求。需要平台排队扩展时，在 Event 中增加 `"agentscope_service":{"intent":"new_turn"}` 并提供幂等键。下表区分普通输入与显式平台意图。


请求只有 `events` 一个顶层字段，包含 **1–50 个对象**，序列化及原始请求正文最大 **1 MiB**。`user.message.content` 是非空数组，每项为 `{type:"text",text:"..."}`，文本不能为空；不接受图片、文件块、工具结果或额外内容字段。`user.interrupt` 不带 content。

| 输入 | 含义 | 目标 / 请求键 |
| --- | --- | --- |
| 普通 `user.message` | 仅在 Session 空闲、没有活动或排队工作时启动 Turn | 无显式目标；建议提供键 |
| `user.message` + `intent:new_turn` | 创建 Turn，在未完成工作之后排队 | 不允许 `turn_id`；键必填 |
| `user.message` + `intent:steer` | 在已有 Turn 的支持边界引导执行 | 显式 `turn_id`；键必填 |
| `user.message` + `intent:inject` | 在已有 Turn 的支持边界补充上下文 | 显式 `turn_id`；键必填 |
| 普通 `user.interrupt` | 在受理时解析并中断当前工作；空闲时可能无操作 | 无显式目标；建议提供键 |
| `user.interrupt` + `intent:cancel` | 请求取消指定 Turn | 显式 `turn_id`；键必填 |

扩展只接受 `intent`、`turn_id` 两个字段。显式目标不能使用 `current`、`latest`。提交显式意图无需响应选择 Header，当前回执自身带平台信息；后续标准历史/SSE 查询要通过 Header 选择平台信息。

注入输入与显式取消示例：

```json
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Also include the confirmed completion time."}],
    "agentscope_service": {"intent": "inject", "turn_id": "<turn_id>"}
  }]
}
```

```json
{
  "events": [{
    "type": "user.interrupt",
    "agentscope_service": {"intent": "cancel", "turn_id": "<turn_id>"}
  }]
}
```


每个操作使用各自稳定的 `Idempotency-Key`。取消回执只代表请求已受理，不代表物理工具立即停止；继续查询 Turn/Input 才能确定结果。

### 受理回执与幂等

以下展示通过 `turn-inputs-v1` 或显式意图选择的平台受理字段，ID 为示意值；普通回执省略 `agentscope_service`：

```json
{
  "data": [{
    "id": "<event_id>",
    "type": "user.message",
    "content": [{"type": "text", "text": "Summarize the incident."}],
    "processed_at": null,
    "agentscope_service": {
      "schema_version": 1,
      "session_id": "<session_id>",
      "event_id": "<event_id>",
      "turn_id": "<turn_id>",
      "admitted_at": "2026-10-19T12:00:00Z",
      "intent": "new_turn",
      "status": "accepted",
      "input_event_ids": ["<event_id>"],
      "receipt": {"type": "turn", "id": "<turn_id>", "status": "accepted"}
    }
  }]
}
```


响应与请求顺序一致。显式交互回执包含 `input_id`、`input_type` 和 Input receipt，而非新 Turn receipt。Event、Turn、Input ID 需要分别保留。

受理将身份、额度变更、回执与待投递记录原子提交；任何无效或冲突项都会回滚整批。支持的普通批次为单条 message、单条 interrupt，或按顺序的 `[interrupt, message]`。多个显式意图可在整体数量限制内原子受理；不要任意混合普通与显式事件，或假设所有组合都支持。

相同调用者/Session/键及相同内容返回**冻结的原受理回执**，即使执行已推进或输入已关闭。相同键改变内容返回 409；新键重新执行可变状态检查。超时或 5xx 不代表未受理，应使用原正文和键重试。GET 返回当前状态，与冻结回执不同；不同操作的键不可混用。

<a id="turns-and-inputs"></a>
## 平台扩展：Turn 与 Input 查询

### GET /v1/sessions/{session_id}/turns

| 参数 | 默认值 | 取值 |
| --- | --- | --- |
| `limit` | 100 | 1–256 |
| `order` | `desc` | `asc`、`desc` |
| `after` | 无 | 本列表和查询返回的 `next_cursor` |
| `status` | 无 | `accepted`、`preparing`、`running`、`waiting`、`waiting_tool`、`completed`、`failed`、`cancelled` |
| `input_event_id` | 无 | 按已受理 Event ID 过滤 |

返回 `{data:[...],has_more,next_cursor}`。Turn 包含 `id`、`type:"turn"`、`session_id`、`created_at`、`admission_sequence`、`status`、`delivery_status`、`inputs_open`、`input_event_ids`、已分配的执行/发布序列、时间、错误、`cancel_requested`、`output_available`、`output_url`。时间为 null 或发布信息缺失表示对应事实尚未产生。

`accepted` 表示已受理，`waiting_tool` 表示等待确认工具结果，不是完成。典型路径是 `accepted → preparing/running → waiting_tool → running → completed`，`failed`、`cancelled` 也是终态。后续 Turn 等待前面的未完成 Turn。工具结束本身不代表模型的这一轮已经结束。

### GET /v1/sessions/{session_id}/turns/{turn_id}

返回当前 Turn 资源，字段同上。`output_available` 表示已有提交的最终消息，不等同于终态。

### GET /v1/sessions/{session_id}/turns/{turn_id}/capabilities

返回该 Turn 当前允许的交互能力。steer/inject/cancel 前查询，但能力查询不预留状态，竞争中的状态变化仍可能使受理失败。

### GET /v1/sessions/{session_id}/turns/{turn_id}/inputs

接受 `limit`（默认 100，范围 1–256）、`after`、`status`（`accepted`、`applied`、`rejected`）和 `event_id`，返回 `{data,has_more,next_cursor}`。`GET .../inputs/{input_id}` 校验 Session/Turn 归属后返回单个 Input。

Input 包含 `id`、`type:"input"`、`session_id`、`turn_id`、`input_type`、`sequence`、`status`、`delivery_status`、Event 关联，以及已经产生的受理/应用时间。拒绝可能带 `rejection_reason`，已应用的特殊处理可能带 `resolution_reason`。accepted/delivered 不等于 applied；保留 Input ID 查询，不要根据写回执或 Redis cursor 推断成功。

<a id="final-output"></a>
## 平台扩展：最终输出

### GET /v1/sessions/{session_id}/turns/{turn_id}/output

接受 `limit`（默认 100，范围 1–256）与 `after`（此输出集合的 `next_cursor`）。需要 read 权限，不依赖 SSE、snapshot 或输出选择 Header。

```json
{
  "type": "turn_output",
  "session_id": "<session_id>",
  "turn_id": "<turn_id>",
  "turn_status": "completed",
  "output_available": true,
  "output_event_ids": ["<assistant_event_id>"],
  "data": [{
    "id": "<assistant_event_id>",
    "type": "agent.message",
    "processed_at": "2026-10-19T12:00:10Z",
    "content": [{"type": "text", "text": "Here is the summary."}],
    "agentscope_service": {"schema_version": 1, "session_id": "<session_id>", "turn_id": "<turn_id>", "item_id": "<item_id>"}
  }],
  "next_cursor": null,
  "has_more": false
}
```


只包含已经提交并封存的最终助手消息，按输出顺序返回；封存后集合稳定。输出尚未产生时返回 200，`output_available:false`、`data:[]`、`output_event_ids:[]`、`has_more:false`、游标为 null。终态无输出是可能的，与已有输出投影暂时不可读不同，后者返回可重试的 503。

`has_more` 为 true 时，把 `next_cursor` 作为 `after` 继续读取。`output_event_ids` 是当前页消息的 ID。读取输出不会创建或恢复 Turn；工具事件和中间助手消息应读取历史，不属于最终输出集合。

<a id="observation"></a>
## 历史、SSE 与事件类型

### GET /v1/sessions/{session_id}/events

| 参数 | 契约 |
| --- | --- |
| `limit` | 默认 100，范围 1–256 |
| `order` | `asc`（默认）或 `desc` |
| `page` | 上一页历史响应中的 `next_page` |
| `types[]` | 可重复，按完整事件类型过滤 |
| `created_at[gt]`、`[gte]`、`[lt]`、`[lte]` | RFC 3339 时间，与事件 **processed_at** 比较；pending Event 没有处理时间 |
| `beta` | 可选 SDK 传输标记，只接受 `true` |

返回 `{data:[...],next_page:<cursor-or-null>}`。第一页固定读取上界，后续分页保持该上界和事件投影版本；去掉 `page` 可重新刷新。游标绑定 Session、调用者、过滤条件/顺序及响应表示，遍历中不能改变这些条件；`limit` 可以调整。方括号和时间需要 URL 编码，例如 `curl -G --data-urlencode 'types[]=agent.message' ...`。

输入 Event 初始 `processed_at:null`，被处理后保持同一个 ID 并更新时间。消费者应按 Event ID 合并并允许处理状态更新，不能把每个重复 ID 都当成冗余。

### GET /v1/sessions/{session_id}/events/stream

返回 `text/event-stream`，使用 `event: <type>` 和 JSON `data: ...`。连接时捕获边界，只发送**未来已处理事件**及心跳注释，不发送 pending 受理记录，也不重放旧历史。一个 Turn 结束不会关闭 Session 流。

不支持 `Last-Event-ID`、`after`、preview、deltas 或 durable resume。唯一可选参数是 `beta=true`。重连时先开流并缓冲，再读历史合并。连接断开或 Ctrl+C 不取消工作；不需要实时观察时，轮询 Turn 并读取 `/output` 即可。

### 事件族

| 事件族 | 含义 |
| --- | --- |
| `user.message`、`user.interrupt` | 稳定公开 ID 的受理输入，处理时间稍后更新 |
| `agent.message` | 公开助手文本内容 |
| `agent.tool_use`、`agent.tool_result` | 支持的原生工具调用和结果，以 `tool_use_id` 关联 |
| `agent.mcp_tool_use`、`agent.mcp_tool_result` | HTTP MCP 调用和结果，以 `mcp_tool_use_id` 关联 |
| `span.model_request_start`、`span.model_request_end` | 关联的模型请求 span；仅在所需计数均有实际计量时投影结束用量 |
| `session.status_running`、`session.status_idle` | 当前实现的对话状态边界 |
| `session.error` | 执行失败的公开错误信息 |
| `agentscope_service.turn.*` | 平台 Turn 状态和终态输出链接，通过 `turn-inputs-v1` 选择 |
| `agentscope_service.input.*` | 平台 Input 受理、应用、拒绝通知，通过 `turn-inputs-v1` 选择 |

事件 JSON 包含 `id`、`type`、`processed_at` 和类型相关内容。工具结果带内容与 `is_error`，需要保留调用/结果关联。选择后，`agentscope_service` 带 Session/Turn/Input 上下文。标准输入输出事件不是原生运行日志封装；私有推理、凭据和原始 checkpoint 不进入公开事件。不支持客户端审批或客户端提交工具结果事件。

<a id="pagination"></a>
## 分页

| 集合 | 请求游标 | 响应后续字段 | 结束条件 |
| --- | --- | --- | --- |
| Session 事件历史 | `page` | `next_page` | null |
| Turn | `after` | `next_cursor`、`has_more` | false / null |
| Turn Input | `after` | `next_cursor`、`has_more` | false / null |
| Turn 最终输出 | `after` | `next_cursor`、`has_more` | false / null |
| 管理列表 | 各操作约定 | 常见 `items`；Product 列表可能是数组，配 `X-Total-Count`、`limit/offset` | 见处理器契约 |

游标不是 offset、Event ID、Redis Stream ID 或 SSE 续传 token，不能跨接口、Session、Turn、调用者或过滤条件使用。Turn/Input 列表分页受理资源并读取其当前状态，不要假设它们具有历史接口的冻结版本语义。

<a id="errors"></a>
## 错误与限制

公开 `/v1/sessions` 错误形态如下，ID 为示意值：

```json
{
  "type": "error",
  "error": {
    "type": "conflict_error",
    "message": "Invalid request",
    "code": "idempotency_conflict"
  },
  "request_id": "req_<request_id>"
}
```


选择输出扩展或请求带显式平台意图时，错误可包含 `error.code`；标准错误可省略。保留 request ID 用于排查。控制面当前有独立 JSON/文本错误格式，常见 `{"error":"..."}`，不要按 Session 错误解析。

| HTTP | Session error type | 常见含义 |
| --- | --- | --- |
| 400 | `invalid_request_error` | 字段/查询无效、不支持能力、显式意图缺少键 |
| 401 | `authentication_error` | 缺少或无效凭据 |
| 403 | `permission_error` | 当前调用者无权限 |
| 404 | `not_found_error` | Session/Turn/Input 不存在或归属不匹配 |
| 409 | `conflict_error` | 幂等正文变化、目标终态/关闭、Session 非活动、请求竞争 |
| 410 | `invalid_request_error` | 相应引用资源过期；不代表未来接口已经开放 |
| 413 | `request_too_large` | 请求正文超过支持大小 |
| 429 | `rate_limit_error` | 受理额度或 SSE 容量限制 |
| 503 | `api_error` | 数据库/服务不可用或已有投影暂时不可读 |

常用平台 code 有 `unsupported_capability`、`invalid_event`、`incompatible_query`、`invalid_cursor`、`idempotency_key_required`、`idempotency_conflict`、`request_in_progress`、`session_inactive`、`turn_terminal`、`admission_limited`、`service_unavailable`；还可能返回其他协议冲突 code。客户端按 HTTP 和 code 判断，不匹配自然语言错误文本。

capabilities 声明上限为 50 Events/请求、事件写入正文 1,048,576 字节、256 对象/页。最大页长不是默认页长（100）。Session metadata/title 限制见上文；执行并发、排队额度和 SSE 连接上限由部署配置。409 协调冲突、429、5xx 或传输结果不明时，退避重试并保持原写入正文/键；校验和授权错误需先修正。

<a id="management"></a>
## Agent 与资源管理

这些操作由控制面执行用户/Namespace/资源授权，用于创建 Session 前配置 Agent。下方目录列方法、路径，并链接实际处理器中的请求结构、响应、检查与状态码。正文和响应不采用统一封装。

### Agent 目录与定义

`POST /api/v1/agents` 接受：

```json
{
  "tenant": "default",
  "namespace": "default",
  "agentKey": "support-assistant",
  "displayName": "Support Assistant",
  "description": "Answers product questions",
  "binding": {"kind": "managed"},
  "definition": {
    "name": "Support Assistant",
    "system": "Answer clearly and use only confirmed facts.",
    "maxIters": 8,
    "defaultEnvironmentId": "<environment_id>",
    "tools": [{"type": "agent_toolset", "defaultConfig": {"enabled": false}}]
  }
}
```


| 字段 | 契约 |
| --- | --- |
| `agentKey` | tenant/namespace 内必填逻辑键 |
| `tenant`、`namespace` | 缺省使用部署作用域，授权上下文可能覆盖正文选择 |
| `displayName`、`description` | 目录展示信息 |
| `binding.kind` | 本文 Session 流程使用 `managed`；Hosted 配置另有契约，External 使用注册接口 |
| `definition` | Managed binding 必填；`name` 可回退到 displayName |
| `definition.system` | Agent 指令 |
| `definition.model` | 可选、服务端支持的模型选择；快速体验省略以使用部署默认模型 |
| `definition.maxIters` | 正迭代上限；创建时省略或非正数回退为 20 |
| `definition.defaultEnvironmentId` | 有权访问的可选环境；本地开发可准备默认环境 |
| `definition.tools`、`mcpServers` | toolset 与 HTTP MCP 配置；还需满足 Session 协议校验 |
| `definition.workspaceId`、`workspaceBinding`、Vault/Memory 默认绑定 | 资源配置；Session 受理仍会检查可用性 |

成功创建可运行 Agent 返回 `{agent,binding,policy,definition}`，ID 从 `agent.id` 获取。省略 `binding` 只创建目录记录。`GET /api/v1/agents` 返回 `{items:[...]}`，支持 tenant/namespace/status/includeArchived/limit；单项查询为 `GET /api/v1/agents/{agent_id}`。

`GET /api/v1/agents/{agent_id}/definition` 返回可移植定义；`PATCH .../definition` 接受定义字段，要求 `name` 和当前正整数 `version`，生成下一版本。`GET .../versions`、`GET .../versions/{version}` 查询历史；已有 Session 保持固定定义。目录 `PATCH /api/v1/agents/{agent_id}` 和 binding 修改也要求各自当前 `version`，修改前先 GET。

定义字段见 [ManagedDefinitionInput](../service-controlplane/internal/product/managed_definition.go)，目录创建/更新见 [agent_catalog_handler.go](../service-controlplane/internal/httpapi/agent_catalog_handler.go)。配置存储支持某字段，不代表首批公开 Session 支持其执行语义。

### Environment

`POST /api/environments` 接受 `{name,type,config}`，直接返回 Environment（`id`、`name`、`type`、`config`、归属和时间）。`GET /api/environments` 返回数组，使用 Product 分页/计数 Header。本地示例：

```json
{"name":"Local API Environment","type":"local","config":{}}
```

本地环境需要部署策略允许。`cloud`、`self_hosted`、`remote` 使用供应商相关配置；`sandbox` 会归一化为 `cloud`。端点、domain、template、凭据等约束见 [Environment handler](../service-controlplane/internal/product/handlers_env.go) 和[部署指南](../deploy/README.md)，不能只修改此示例的 type。创建 Environment 不等于启动 Worker 或验证供应商连接。

### Application 与凭据

先用第一段正文调用 `POST /api/v1/applications`，再用第二段调用 `POST /api/v1/applications/{application_id}/credentials`：

```json
{
  "name": "Product Integration",
  "tenant": "default",
  "namespace": "default"
}
```

```json
{
  "name": "backend-session-client",
  "scopes": ["invoke", "read", "cancel"],
  "targets": [{"type": "agent", "id": "<agent_id>"}]
}
```


响应分别为 `{application:...}` 和 `{credential:...,apiKey:"..."}`。创建时保存 `apiKey`，后续列表不返回明文。目标授权标识 Agent/资源，不带版本；版本在创建 Session 时选择。Application owner 管理凭据，可选 `expiresAt` 必须在未来，每个 scope/target 都会校验。`DELETE .../credentials/{credential_id}` 撤销凭据。Session 请求使用 `X-API-Key`，与人工管理 JWT 分开。

Application 成员和额度不会自动隔离业务终端用户，接入方需要选择合适的 Application/Session 归属和资源授权。详见 [Application schema](../service-controlplane/internal/controlplane/model/application.go) 和[凭据处理器](../service-controlplane/internal/httpapi/service_session_auth.go)。

### HTTP MCP 配置

URL server 与命名 toolset 必须对应。以下为 definition 片段；替换 URL，并单独配置实际供应商访问授权：

```json
{
  "mcpServers": [{"name": "knowledge", "url": "https://tools.example.com/mcp"}],
  "tools": [
    {"type": "agent_toolset", "defaultConfig": {"enabled": false}},
    {
      "type": "mcp_toolset",
      "mcpServerName": "knowledge",
      "defaultConfig": {"enabled": true, "permissionPolicy": {"type": "always_allow"}}
    }
  ]
}
```

Brain 在推理前直接发现并固定 MCP 描述；真实 MCP 调用由 Hands 异步执行。URL 配好不代表目录或凭据已经验证，需在实际部署验收。当前协议支持的原生工具配置名为 `bash`、`read`、`write`、`edit`、`glob`、`grep`、`web_fetch`、`web_search`。本版本启用工具需要 `always_allow`，禁用工具可保留其他策略。这是配置约束，不表示每个 Agent 都应该启用全部工具。

### 资源请求字段

以下是正文主要字段指引，并非完整嵌套 schema；可选字段及供应商相关校验见路由目录中的处理器。修改操作的 `version`/`expectedVersion` 是当前资源版本，不是 Agent 定义版本或 Session 协议版本。

| 操作 | 正文字段 / 行为 |
| --- | --- |
| `POST /api/workspaces` | 必填非空 `name`；可选 `description`、`tools`、`mcpServers`、`skills`；直接返回 Workspace |
| `POST /api/memory-stores` | `name`、`description`；基于路径的 `PUT .../memories/*path` 使用 `content`、`summary` |
| `POST /api/vaults` | `displayName`、`metadata` |
| `POST /api/vaults/{id}/credentials` | `type`、`label`、`target`、`secret`；实际密钥通过有权限的 API 保存 |
| `POST /api/channels` / `PUT /api/channels/{id}` | `channelId`、`type`、`dmScope`、`defaultAgentId`、`disabled`、`properties`、`bindings`；同时检查供应商配置和身份策略 |
| `POST /api/deployments` | `name`、`agentId`、`agentVersion`、`environmentId`、`triggerType`、`cronExpression`；定时/供应商触发有独立校验 |
| `POST /api/v1/orchestration-definitions` | OrchestrationDefinition schema；见[定义模型](../service-controlplane/internal/controlplane/model/orchestration.go)与处理器的校验/发布/启动边界 |
| `POST/PATCH /api/v1/automations[/{id}]` | `name`、`description`、`enabled`、`execution`、`triggers`，或受支持的 `triggerType/triggerConfig/actionType/actionConfig`；更新使用 `expectedVersion` |

### 其他资源族

| 资源族 | 用途 / 请求结构依据 |
| --- | --- |
| Namespace / 资源权限 | 成员、组、授权、申请、审计：[access](../service-controlplane/internal/httpapi/access_management.go)、[resource access](../service-controlplane/internal/httpapi/resource_groups.go) |
| Workspace / Skill / 工具目录 | 定义文件、发布版本和工具配置：[Workspace](../service-controlplane/internal/product/handlers_workspaces.go)、[Agent workspace](../service-controlplane/internal/product/handlers_workspace.go) |
| Memory Store | 存储创建和基于路径的记忆读写：[处理器](../service-controlplane/internal/product/handlers_memory.go) |
| Vault / OAuth | 凭据、连接与授权流程：[Vault](../service-controlplane/internal/product/handlers_vault.go)、[OAuth](../service-controlplane/internal/product/oauth_connector.go) |
| Channel / Deployment | 供应商绑定、投递活动和定时触发：[Channel](../service-controlplane/internal/product/handlers_channels.go)、[Deployment](../service-controlplane/internal/product/handlers_deploy.go) |
| Team / Issue / AgentTask | 协作、分派、评论、决策、执行 Attempt：见下方路由处理器链接 |
| Workflow 定义 / Run | 校验、发布定义，启动、观察、发信号和控制运行：见下方路由处理器链接 |
| Runtime Profile / Pool / Host | Hosted 容量与配置；机器生命周期协议单独列出 |

<a id="management-route-catalog"></a>
## 控制面路由目录

下方从当前注册代码解析出完整路径，面向启用持久存储和 Product API 的部署。每个处理器链接指向实际 Go 实现，含 JSON 请求结构、响应封装、权限检查和状态码。这是配合上方 Session/资源契约的端点目录，不是 OpenAPI schema，也不保证每项可选功能都已启用。依赖 Kubernetes 的资源单独标记，机器和兼容路由在后文区分。

### 账号与认证

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/admin/access-audit` | [accountAudit](../service-controlplane/internal/product/account_management.go#L233) |
| `GET` | `/api/admin/integrations/github` | [githubAppGet](../service-controlplane/internal/product/oauth_github.go#L80) |
| `PUT` | `/api/admin/integrations/github` | [githubAppSave](../service-controlplane/internal/product/oauth_github.go#L98) |
| `GET` | `/api/admin/users` | [adminListUsers](../service-controlplane/internal/product/handlers_admin.go#L51) |
| `POST` | `/api/admin/users` | [adminCreateUser](../service-controlplane/internal/product/handlers_admin.go#L63) |
| `DELETE` | `/api/admin/users/{id}` | [adminDeleteUser](../service-controlplane/internal/product/handlers_admin.go#L206) |
| `PATCH` | `/api/admin/users/{id}/password` | [adminResetPassword](../service-controlplane/internal/product/handlers_admin.go#L144) |
| `PATCH` | `/api/admin/users/{id}/roles` | [adminUpdateRoles](../service-controlplane/internal/product/handlers_admin.go#L190) |
| `PATCH` | `/api/admin/users/{id}/status` | [adminAccountStatus](../service-controlplane/internal/product/account_management.go#L123) |
| `GET` | `/api/auth/dev-session` | [localDevSession](../service-controlplane/internal/product/local_dev.go#L39) |
| `POST` | `/api/auth/login` | [login](../service-controlplane/internal/product/handlers_auth.go#L37) |
| `POST` | `/api/auth/logout` | [logoutAccount](../service-controlplane/internal/product/account_management.go#L333) |
| `GET` | `/api/auth/me` | [me](../service-controlplane/internal/product/handlers_auth.go#L83) |
| `GET` | `/api/oauth/mcp/callback/{connectionId}` | [oauthCallback](../service-controlplane/internal/product/oauth_callback.go#L60) |
| `GET` | `/api/oauth/providers/github` | [githubProviderStatus](../service-controlplane/internal/product/oauth_github.go#L70) |
| `POST` | `/api/user/change-password` | [changePassword](../service-controlplane/internal/product/handlers_auth.go#L101) |
| `DELETE` | `/api/user/channel-connections` | [removeMyChannelIdentity](../service-controlplane/internal/product/account_connections.go#L71) |
| `GET` | `/api/user/channel-connections` | [myChannelConnections](../service-controlplane/internal/product/account_connections.go#L27) |
| `DELETE` | `/api/user/channel-subscriptions/{subscriptionId}` | [unsubscribeMyChannel](../service-controlplane/internal/product/account_connections.go#L88) |
| `GET` | `/api/user/login-sessions` | [listLoginSessions](../service-controlplane/internal/product/account_management.go#L278) |
| `POST` | `/api/user/login-sessions/revoke-others` | [revokeOtherLoginSessions](../service-controlplane/internal/product/account_management.go#L313) |
| `DELETE` | `/api/user/login-sessions/{sessionId}` | [revokeLoginSession](../service-controlplane/internal/product/account_management.go#L301) |
| `GET` | `/api/user/profile` | [profile](../service-controlplane/internal/product/handlers_auth.go#L94) |
| `PUT` | `/api/user/profile` | [updateProfile](../service-controlplane/internal/product/account_management.go#L262) |

### Namespace、权限与偏好

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/v1/access/accounts` | [platformAccountDirectory](../service-controlplane/internal/httpapi/access_management.go#L120) |
| `GET` | `/api/v1/access/audit` | [namespaceAudit](../service-controlplane/internal/httpapi/access_management.go#L205) |
| `GET` | `/api/v1/access/users/{accountId}/namespaces` | [accountNamespaces](../service-controlplane/internal/httpapi/access_management.go#L186) |
| `POST` | `/api/v1/entity-identities:resolve` | [resolveEntityIdentities](../service-controlplane/internal/httpapi/entity_identity_handler.go#L100) |
| `GET` | `/api/v1/me/namespaces` | [listMyNamespaces](../service-controlplane/internal/httpapi/namespace_access.go#L417) |
| `GET` | `/api/v1/me/navigation` | [navigationAccess](../service-controlplane/internal/httpapi/workspace_rbac.go#L187) |
| `GET` | `/api/v1/me/preferences` | [getMyPreferences](../service-controlplane/internal/httpapi/access_management.go#L259) |
| `PUT` | `/api/v1/me/preferences` | [updateMyPreferences](../service-controlplane/internal/httpapi/access_management.go#L271) |
| `GET` | `/api/v1/me/scope` | [getCurrentScope](../service-controlplane/internal/httpapi/scope_handler.go#L74) |
| `GET` | `/api/v1/namespaces` | [listManagedNamespaces](../service-controlplane/internal/httpapi/access_management.go#L57) |
| `POST` | `/api/v1/namespaces` | [createNamespace](../service-controlplane/internal/httpapi/namespace_access.go#L446) |
| `GET` | `/api/v1/namespaces/{namespaceName}` | [getNamespaceAccess](../service-controlplane/internal/httpapi/namespace_access.go#L505) |
| `PUT` | `/api/v1/namespaces/{namespaceName}` | [updateNamespaceAccess](../service-controlplane/internal/httpapi/namespace_access.go#L511) |
| `GET` | `/api/v1/namespaces/{namespaceName}/accounts` | [namespaceAccountDirectory](../service-controlplane/internal/httpapi/access_management.go#L125) |
| `GET` | `/api/v1/namespaces/{namespaceName}/audit` | [namespaceAudit](../service-controlplane/internal/httpapi/access_management.go#L205) |
| `GET` | `/api/v1/namespaces/{namespaceName}/groups` | [listAccessGroups](../service-controlplane/internal/httpapi/resource_groups.go#L41) |
| `PUT` | `/api/v1/namespaces/{namespaceName}/groups` | [updateAccessGroups](../service-controlplane/internal/httpapi/resource_groups.go#L88) |
| `POST` | `/api/v1/namespaces/{namespaceName}/import-template` | [importWorkflowTemplate](../service-controlplane/internal/httpapi/resource_templates.go#L81) |
| `GET` | `/api/v1/namespaces/{namespaceName}/requests` | [listAccessRequests](../service-controlplane/internal/httpapi/resource_groups.go#L140) |
| `POST` | `/api/v1/namespaces/{namespaceName}/requests` | [createAccessRequest](../service-controlplane/internal/httpapi/resource_groups.go#L155) |
| `POST` | `/api/v1/namespaces/{namespaceName}/requests/{requestId}/review` | [reviewAccessRequest](../service-controlplane/internal/httpapi/resource_groups.go#L198) |
| `GET` | `/api/v1/namespaces/{namespaceName}/resources` | [listNamespaceResources](../service-controlplane/internal/httpapi/resource_access.go#L223) |
| `GET` | `/api/v1/namespaces/{namespaceName}/resources/{kind}/{resourceId}/access` | [getResourceAccess](../service-controlplane/internal/httpapi/resource_access.go#L254) |
| `PUT` | `/api/v1/namespaces/{namespaceName}/resources/{kind}/{resourceId}/access` | [updateResourceAccess](../service-controlplane/internal/httpapi/resource_access.go#L307) |
| `GET` | `/api/v1/namespaces/{namespaceName}/shared-templates` | [sharedWorkflowTemplates](../service-controlplane/internal/httpapi/resource_templates.go#L30) |
| `POST` | `/api/v1/namespaces/{namespaceName}/transfer` | [transferNamespace](../service-controlplane/internal/httpapi/access_management.go#L228) |

### Application 与凭据

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/v1/applications` | [listApplications](../service-controlplane/internal/httpapi/application_handler.go#L94) |
| `POST` | `/api/v1/applications` | [createApplication](../service-controlplane/internal/httpapi/application_handler.go#L67) |
| `GET` | `/api/v1/applications/{applicationId}` | [getApplication](../service-controlplane/internal/httpapi/application_handler.go#L115) |
| `PATCH` | `/api/v1/applications/{applicationId}` | [patchApplication](../service-controlplane/internal/httpapi/application_handler.go#L131) |
| `GET` | `/api/v1/applications/{applicationId}/credentials` | [listSessionCredentials](../service-controlplane/internal/httpapi/service_session_auth.go#L115) |
| `POST` | `/api/v1/applications/{applicationId}/credentials` | [createSessionCredential](../service-controlplane/internal/httpapi/service_session_auth.go#L52) |
| `DELETE` | `/api/v1/applications/{applicationId}/credentials/{credentialId}` | [revokeSessionCredential](../service-controlplane/internal/httpapi/service_session_auth.go#L149) |

### Agent 目录、定义与运行策略

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `POST` | `/api/v1/agent-registrations/{agentId}/credentials/rotate` | [rotateAgentRegistrationCredential](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1145) |
| `DELETE` | `/api/v1/agent-registrations/{agentId}/credentials/{credentialId}` | [revokeAgentRegistrationCredential](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1172) |
| `GET` | `/api/v1/agent-runtime-policies/{agentId}` | [getAgentRuntimePolicy](../service-controlplane/internal/httpapi/orchestration_handler.go#L412) |
| `PUT` | `/api/v1/agent-runtime-policies/{agentId}` | [putAgentRuntimePolicy](../service-controlplane/internal/httpapi/orchestration_handler.go#L429) |
| `GET` | `/api/v1/agents` | [listCatalogAgents](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L168) |
| `POST` | `/api/v1/agents` | [createCatalogAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L445) |
| `GET` | `/api/v1/agents/runtime-options` | [listAgentRuntimeOptions](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L223) |
| `GET` | `/api/v1/agents/{agentId}` | [getCatalogAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L702) |
| `PATCH` | `/api/v1/agents/{agentId}` | [patchCatalogAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L732) |
| `GET` | `/api/v1/agents/{agentId}/bindings` | [listAgentBindings](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L783) |
| `POST` | `/api/v1/agents/{agentId}/bindings` | [createAgentBinding](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L796) |
| `PATCH` | `/api/v1/agents/{agentId}/bindings/{bindingId}` | [patchAgentBinding](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L820) |
| `GET` | `/api/v1/agents/{agentId}/definition` | [getManagedAgentDefinition](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L576) |
| `PATCH` | `/api/v1/agents/{agentId}/definition` | [patchManagedAgentDefinition](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L655) |
| `POST` | `/api/v1/agents/{agentId}/definition` | [initializePortableDefinition](../service-controlplane/internal/httpapi/workspace_definition.go#L34) |
| `GET` | `/api/v1/agents/{agentId}/hosted-settings` | [getHostedAgentSettings](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L909) |
| `PATCH` | `/api/v1/agents/{agentId}/hosted-settings` | [patchHostedAgentSettings](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1035) |
| `GET` | `/api/v1/agents/{agentId}/instances` | [listCatalogAgentInstances](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1127) |
| `GET` | `/api/v1/agents/{agentId}/overview` | [getAgentDetailOverview](../service-controlplane/internal/httpapi/agent_detail_handler.go#L93) |
| `GET` | `/api/v1/agents/{agentId}/runtime-inventory` | [getAgentRuntimeInventory](../service-controlplane/internal/httpapi/agent_detail_handler.go#L371) |
| `GET` | `/api/v1/agents/{agentId}/versions` | [listManagedAgentVersions](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L602) |
| `GET` | `/api/v1/agents/{agentId}/versions/{version}` | [getManagedAgentVersion](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L624) |
| `GET` | `/api/v1/agents/{agentId}/workspace-capabilities` | [agentWorkspaceCapabilities](../service-controlplane/internal/httpapi/workspace_definition.go#L56) |

### Workspace、Skill、工具与市场

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/agents/{id}/bindings` | [listAgentBindings](../service-controlplane/internal/product/handlers_channels.go#L576) |
| `POST` | `/api/agents/{id}/bindings` | [addAgentBinding](../service-controlplane/internal/product/handlers_channels.go#L721) |
| `PUT` | `/api/agents/{id}/bindings` | [replaceAgentBindings](../service-controlplane/internal/product/handlers_channels.go#L643) |
| `DELETE` | `/api/agents/{id}/bindings/{index}` | [deleteAgentBinding](../service-controlplane/internal/product/handlers_channels.go#L836) |
| `PUT` | `/api/agents/{id}/bindings/{index}` | [updateAgentBinding](../service-controlplane/internal/product/handlers_channels.go#L778) |
| `POST` | `/api/agents/{id}/channels/{channelId}/default` | [setChannelDefault](../service-controlplane/internal/product/handlers_channels.go#L458) |
| `GET` | `/api/agents/{id}/presences` | [listAgentPresences](../service-controlplane/internal/product/handlers_presences.go#L75) |
| `POST` | `/api/agents/{id}/presences` | [createAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L102) |
| `DELETE` | `/api/agents/{id}/presences/{channelId}` | [deleteAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L221) |
| `GET` | `/api/agents/{id}/presences/{channelId}` | [getAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L241) |
| `PUT` | `/api/agents/{id}/presences/{channelId}` | [updateAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L156) |
| `GET` | `/api/agents/{id}/shares` | [listShares](../service-controlplane/internal/product/handlers_agent_extras.go#L539) |
| `POST` | `/api/agents/{id}/shares` | [addShare](../service-controlplane/internal/product/handlers_agent_extras.go#L573) |
| `DELETE` | `/api/agents/{id}/shares/{granteeType}/{granteeId}` | [revokeShare](../service-controlplane/internal/product/handlers_agent_extras.go#L607) |
| `GET` | `/api/agents/{id}/skills/workspace` | [listWorkspaceSkills](../service-controlplane/internal/product/handlers_agent_extras.go#L114) |
| `POST` | `/api/agents/{id}/skills/workspace/marketplace-install` | [marketplaceInstallSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L300) |
| `DELETE` | `/api/agents/{id}/skills/workspace/{name}` | [deleteWorkspaceSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L277) |
| `GET` | `/api/agents/{id}/skills/workspace/{name}` | [getWorkspaceSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L168) |
| `PUT` | `/api/agents/{id}/skills/workspace/{name}` | [putWorkspaceSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L234) |
| `GET` | `/api/agents/{id}/tools/active` | [toolsActive](../service-controlplane/internal/product/handlers_agent_extras.go#L342) |
| `GET` | `/api/agents/{id}/tools/catalog/builtins` | [toolsBuiltinCatalog](../service-controlplane/internal/product/handlers_agent_extras.go#L326) |
| `GET` | `/api/agents/{id}/tools/catalog/mcp-servers` | [toolsMcpCatalog](../service-controlplane/internal/product/handlers_agent_extras.go#L334) |
| `GET` | `/api/agents/{id}/workspace` | [workspaceSummary](../service-controlplane/internal/product/handlers_workspace.go#L102) |
| `DELETE` | `/api/agents/{id}/workspace/file` | [workspaceDeleteFile](../service-controlplane/internal/product/handlers_workspace.go#L403) |
| `GET` | `/api/agents/{id}/workspace/file` | [workspaceReadFile](../service-controlplane/internal/product/handlers_workspace.go#L265) |
| `POST` | `/api/agents/{id}/workspace/file` | [workspaceCreateFile](../service-controlplane/internal/product/handlers_workspace.go#L334) |
| `PUT` | `/api/agents/{id}/workspace/file` | [workspaceWriteFile](../service-controlplane/internal/product/handlers_workspace.go#L297) |
| `POST` | `/api/agents/{id}/workspace/file/move` | [workspaceMoveFile](../service-controlplane/internal/product/handlers_workspace.go#L367) |
| `GET` | `/api/agents/{id}/workspace/files` | [workspaceFiles](../service-controlplane/internal/product/handlers_workspace.go#L202) |
| `GET` | `/api/agents/{id}/workspace/subagents` | [listSubagents](../service-controlplane/internal/product/handlers_workspace.go#L462) |
| `POST` | `/api/agents/{id}/workspace/subagents/from-agent` | [subagentFromAgent](../service-controlplane/internal/product/handlers_workspace.go#L559) |
| `DELETE` | `/api/agents/{id}/workspace/subagents/{name}` | [deleteSubagent](../service-controlplane/internal/product/handlers_workspace.go#L597) |
| `PUT` | `/api/agents/{id}/workspace/subagents/{name}` | [upsertSubagent](../service-controlplane/internal/product/handlers_workspace.go#L515) |
| `POST` | `/api/agents/{id}/workspace/upload` | [workspaceUpload](../service-controlplane/internal/product/handlers_workspace.go#L422) |
| `GET` | `/api/marketplaces` | [listMarketplaces](../service-controlplane/internal/product/handlers_marketplaces.go#L78) |
| `POST` | `/api/marketplaces` | [createMarketplace](../service-controlplane/internal/product/handlers_marketplaces.go#L101) |
| `DELETE` | `/api/marketplaces/{id}` | [deleteMarketplace](../service-controlplane/internal/product/handlers_marketplaces.go#L140) |
| `GET` | `/api/marketplaces/{id}` | [getMarketplace](../service-controlplane/internal/product/handlers_marketplaces.go#L131) |
| `GET` | `/api/marketplaces/{id}/skills` | [browseMarketplaceSkills](../service-controlplane/internal/product/handlers_marketplaces.go#L152) |
| `GET` | `/api/toolsets/builtin` | [platformBuiltinCatalog](../service-controlplane/internal/product/handlers_workspaces.go#L360) |
| `GET` | `/api/toolsets/mcp-catalog` | [platformMcpCatalog](../service-controlplane/internal/product/handlers_workspaces.go#L364) |
| `GET` | `/api/workspaces` | [listWorkspaces](../service-controlplane/internal/product/handlers_workspaces.go#L157) |
| `POST` | `/api/workspaces` | [createWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L198) |
| `DELETE` | `/api/workspaces/{id}` | [deleteWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L336) |
| `GET` | `/api/workspaces/{id}` | [getWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L251) |
| `PATCH` | `/api/workspaces/{id}` | [patchWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L280) |
| `GET` | `/api/workspaces/{id}/agents` | [wsLinkedAgents](../service-controlplane/internal/product/workspace_publication.go#L198) |
| `DELETE` | `/api/workspaces/{id}/file` | [wsDeleteFile](../service-controlplane/internal/product/handlers_workspaces.go#L424) |
| `GET` | `/api/workspaces/{id}/file` | [wsReadFile](../service-controlplane/internal/product/handlers_workspaces.go#L383) |
| `PUT` | `/api/workspaces/{id}/file` | [wsWriteFile](../service-controlplane/internal/product/handlers_workspaces.go#L399) |
| `GET` | `/api/workspaces/{id}/files` | [wsFiles](../service-controlplane/internal/product/handlers_workspaces.go#L368) |
| `POST` | `/api/workspaces/{id}/publish` | [wsPublish](../service-controlplane/internal/product/workspace_publication.go#L158) |
| `GET` | `/api/workspaces/{id}/revisions` | [wsRevisions](../service-controlplane/internal/product/workspace_publication.go#L166) |
| `GET` | `/api/workspaces/{id}/skills` | [wsListSkills](../service-controlplane/internal/product/handlers_workspaces.go#L517) |
| `POST` | `/api/workspaces/{id}/skills/marketplace-install` | [wsMarketplaceInstall](../service-controlplane/internal/product/handlers_marketplaces.go#L167) |
| `DELETE` | `/api/workspaces/{id}/skills/{name}` | [wsDeleteSkill](../service-controlplane/internal/product/handlers_workspaces.go#L613) |
| `GET` | `/api/workspaces/{id}/skills/{name}` | [wsGetSkill](../service-controlplane/internal/product/handlers_workspaces.go#L482) |
| `PUT` | `/api/workspaces/{id}/skills/{name}` | [wsPutSkill](../service-controlplane/internal/product/handlers_workspaces.go#L553) |
| `GET` | `/api/workspaces/{id}/subagents` | [wsListSubagents](../service-controlplane/internal/product/handlers_workspaces.go#L759) |
| `DELETE` | `/api/workspaces/{id}/subagents/{name}` | [wsDeleteSubagent](../service-controlplane/internal/product/handlers_workspaces.go#L817) |
| `PUT` | `/api/workspaces/{id}/subagents/{name}` | [wsUpsertSubagent](../service-controlplane/internal/product/handlers_workspaces.go#L780) |
| `GET` | `/api/workspaces/{id}/tools` | [wsGetTools](../service-controlplane/internal/product/handlers_workspaces.go#L438) |
| `PUT` | `/api/workspaces/{id}/tools` | [wsPutTools](../service-controlplane/internal/product/handlers_workspaces.go#L451) |

### Environment

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/environments` | [listEnvironments](../service-controlplane/internal/product/handlers_env.go#L82) |
| `POST` | `/api/environments` | [createEnvironment](../service-controlplane/internal/product/handlers_env.go#L118) |
| `DELETE` | `/api/environments/{id}` | [deleteEnvironment](../service-controlplane/internal/product/handlers_env.go#L221) |
| `GET` | `/api/environments/{id}` | [getEnvironment](../service-controlplane/internal/product/handlers_env.go#L157) |
| `PATCH` | `/api/environments/{id}` | [updateEnvironment](../service-controlplane/internal/product/handlers_env.go#L172) |
| `POST` | `/api/environments/{id}/archive` | [archiveEnvironment](../service-controlplane/internal/product/handlers_env.go#L236) |
| `POST` | `/api/environments/{id}/rotate-key` | [rotateEnvironmentKey](../service-controlplane/internal/product/handlers_env.go#L255) |

### Memory Store

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/memory-stores` | [listMemoryStores](../service-controlplane/internal/product/handlers_memory.go#L64) |
| `POST` | `/api/memory-stores` | [createMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L110) |
| `DELETE` | `/api/memory-stores/{id}` | [deleteMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L224) |
| `GET` | `/api/memory-stores/{id}` | [getMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L132) |
| `POST` | `/api/memory-stores/{id}/archive` | [archiveMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L161) |
| `GET` | `/api/memory-stores/{id}/memories` | [listMemories](../service-controlplane/internal/product/handlers_memory.go#L248) |
| `DELETE` | `/api/memory-stores/{id}/memories/*path` | [deleteMemory](../service-controlplane/internal/product/handlers_memory.go#L382) |
| `GET` | `/api/memory-stores/{id}/memories/*path` | [readMemory](../service-controlplane/internal/product/handlers_memory.go#L46) |
| `PUT` | `/api/memory-stores/{id}/memories/*path` | [putMemory](../service-controlplane/internal/product/handlers_memory.go#L315) |
| `POST` | `/api/memory-stores/{id}/redact` | [redactMemory](../service-controlplane/internal/product/handlers_memory.go#L181) |

### Vault 与 OAuth 连接

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/vaults` | [listVaults](../service-controlplane/internal/product/handlers_vault.go#L65) |
| `POST` | `/api/vaults` | [createVault](../service-controlplane/internal/product/handlers_vault.go#L107) |
| `DELETE` | `/api/vaults/{id}` | [deleteVault](../service-controlplane/internal/product/handlers_vault.go#L217) |
| `GET` | `/api/vaults/{id}` | [getVault](../service-controlplane/internal/product/handlers_vault.go#L129) |
| `PATCH` | `/api/vaults/{id}` | [updateVault](../service-controlplane/internal/product/handlers_vault.go#L157) |
| `POST` | `/api/vaults/{id}/archive` | [archiveVault](../service-controlplane/internal/product/handlers_vault.go#L198) |
| `GET` | `/api/vaults/{id}/credentials` | [listCredentials](../service-controlplane/internal/product/handlers_vault.go#L241) |
| `POST` | `/api/vaults/{id}/credentials` | [addCredential](../service-controlplane/internal/product/handlers_vault.go#L270) |
| `DELETE` | `/api/vaults/{id}/credentials/{cid}` | [deleteCredential](../service-controlplane/internal/product/handlers_vault.go#L402) |
| `PATCH` | `/api/vaults/{id}/credentials/{cid}` | [updateCredential](../service-controlplane/internal/product/handlers_vault.go#L300) |
| `POST` | `/api/vaults/{id}/credentials/{cid}/validate` | [validateCredential](../service-controlplane/internal/product/handlers_vault.go#L357) |
| `GET` | `/api/vaults/{id}/oauth-connections` | [listOAuthConnections](../service-controlplane/internal/product/oauth_connector.go#L207) |
| `POST` | `/api/vaults/{id}/oauth-connections` | [createOAuthConnection](../service-controlplane/internal/product/oauth_connector.go#L245) |
| `PATCH` | `/api/vaults/{id}/oauth-connections/{connectionId}` | [updateOAuthConnection](../service-controlplane/internal/product/oauth_connector.go#L293) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/authorize` | [startOAuth](../service-controlplane/internal/product/oauth_connector.go#L388) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/disconnect` | [disconnectOAuth](../service-controlplane/internal/product/oauth_connector.go#L353) |
| `GET` | `/api/vaults/{id}/oauth-connections/{connectionId}/flows/{flowId}` | [oauthFlowStatus](../service-controlplane/internal/product/oauth_callback.go#L146) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/flows/{flowId}/cancel` | [cancelOAuth](../service-controlplane/internal/product/oauth_callback.go#L155) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/flows/{flowId}/complete` | [completeOAuth](../service-controlplane/internal/product/oauth_callback.go#L168) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/verify` | [verifyGitHubConnection](../service-controlplane/internal/product/oauth_github_verify.go#L42) |

### Channel、Deployment 与工作来源

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/channels` | [listChannels](../service-controlplane/internal/product/handlers_channels.go#L176) |
| `POST` | `/api/channels` | [createChannel](../service-controlplane/internal/product/handlers_channels.go#L262) |
| `GET` | `/api/channels/types` | [listChannelTypes](../service-controlplane/internal/product/handlers_channels.go#L212) |
| `DELETE` | `/api/channels/{channelId}` | [deleteChannel](../service-controlplane/internal/product/handlers_channels.go#L415) |
| `GET` | `/api/channels/{channelId}` | [getChannel](../service-controlplane/internal/product/handlers_channels.go#L216) |
| `PUT` | `/api/channels/{channelId}` | [updateChannel](../service-controlplane/internal/product/handlers_channels.go#L339) |
| `GET` | `/api/channels/{channelId}/activity` | [channelWorkActivity](../service-controlplane/internal/product/channel_work_api.go#L383) |
| `GET` | `/api/channels/{channelId}/collaboration` | [channelWorkConfig](../service-controlplane/internal/product/channel_work_api.go#L83) |
| `PUT` | `/api/channels/{channelId}/collaboration` | [putChannelWorkConfig](../service-controlplane/internal/product/channel_work_api.go#L95) |
| `POST` | `/api/channels/{channelId}/deliveries/{deliveryId}/retry` | [retryChannelDelivery](../service-controlplane/internal/product/channel_work_api.go#L462) |
| `POST` | `/api/channels/{channelId}/disable` | [disableChannel](../service-controlplane/internal/product/handlers_channels.go#L437) |
| `POST` | `/api/channels/{channelId}/enable` | [enableChannel](../service-controlplane/internal/product/handlers_channels.go#L433) |
| `DELETE` | `/api/channels/{channelId}/identity` | [deleteChannelIdentity](../service-controlplane/internal/product/channel_work_api.go#L232) |
| `DELETE` | `/api/channels/{channelId}/links/{linkId}` | [unsubscribeChannelWork](../service-controlplane/internal/product/channel_work_api.go#L483) |
| `POST` | `/api/channels/{channelId}/messages/{messageId}/retry` | [retryChannelIntake](../service-controlplane/internal/product/channel_work_api.go#L547) |
| `POST` | `/api/channels/{channelId}/pairing` | [createChannelPairing](../service-controlplane/internal/product/channel_work_api.go#L209) |
| `GET` | `/api/deployments` | [listDeployments](../service-controlplane/internal/product/handlers_deploy.go#L122) |
| `POST` | `/api/deployments` | [createDeployment](../service-controlplane/internal/product/handlers_deploy.go#L157) |
| `POST` | `/api/deployments/webhook/{token}` | [triggerDeploymentWebhook](../service-controlplane/internal/product/handlers_deploy.go#L343) |
| `DELETE` | `/api/deployments/{id}` | [deleteDeployment](../service-controlplane/internal/product/handlers_deploy.go#L273) |
| `GET` | `/api/deployments/{id}` | [getDeployment](../service-controlplane/internal/product/handlers_deploy.go#L197) |
| `PATCH` | `/api/deployments/{id}` | [updateDeployment](../service-controlplane/internal/product/handlers_deploy.go#L206) |
| `POST` | `/api/deployments/{id}/archive` | [archiveDeployment](../service-controlplane/internal/product/handlers_deploy.go#L254) |
| `POST` | `/api/deployments/{id}/pause` | [pauseDeployment](../service-controlplane/internal/product/handlers_deploy.go#L306) |
| `POST` | `/api/deployments/{id}/run` | [runDeployment](../service-controlplane/internal/product/handlers_deploy.go#L288) |
| `POST` | `/api/deployments/{id}/unpause` | [unpauseDeployment](../service-controlplane/internal/product/handlers_deploy.go#L307) |
| `GET` | `/api/v1/work-sources` | [listWorkSources](../service-controlplane/internal/httpapi/work_source_handler.go#L61) |
| `POST` | `/api/v1/work-sources` | [createWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L37) |
| `POST` | `/api/v1/work-sources/comment-outbox/flush` | [flushWorkSourceCommentOutbox](../service-controlplane/internal/httpapi/work_source_handler.go#L173) |
| `GET` | `/api/v1/work-sources/{workSourceId}` | [getWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L79) |
| `PATCH` | `/api/v1/work-sources/{workSourceId}` | [patchWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L91) |
| `POST` | `/api/v1/work-sources/{workSourceId}/reconcile` | [reconcileWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L190) |
| `POST` | `/api/v1/work-sources/{workSourceId}/webhooks/github` | [githubWorkSourceWebhook](../service-controlplane/internal/httpapi/work_source_handler.go#L216) |
| `POST` | `/hooks/v1/automations/{automationId}/{triggerId}` | [automationWebhook](../service-controlplane/internal/httpapi/automation_handler.go#L492) |

### Team、Issue、任务、产物与审批

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/v1/agent-tasks` | [listAgentTasks](../service-controlplane/internal/httpapi/collaboration_handler.go#L1079) |
| `GET` | `/api/v1/agent-tasks/{taskId}` | [getAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1109) |
| `POST` | `/api/v1/agent-tasks/{taskId}/cancel` | [cancelAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1380) |
| `POST` | `/api/v1/agent-tasks/{taskId}/dispatch` | [dispatchAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1222) |
| `POST` | `/api/v1/agent-tasks/{taskId}/inputs/replay` | [replayAgentTaskInputs](../service-controlplane/internal/httpapi/collaboration_handler.go#L1295) |
| `POST` | `/api/v1/agent-tasks/{taskId}/retry` | [retryAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1390) |
| `GET` | `/api/v1/approvals` | [listApprovals](../service-controlplane/internal/httpapi/collaboration_handler.go#L2161) |
| `POST` | `/api/v1/approvals` | [createApproval](../service-controlplane/internal/httpapi/collaboration_handler.go#L2105) |
| `GET` | `/api/v1/approvals/{approvalId}` | [getApproval](../service-controlplane/internal/httpapi/collaboration_handler.go#L2183) |
| `POST` | `/api/v1/approvals/{approvalId}/decide` | [decideApproval](../service-controlplane/internal/httpapi/collaboration_handler.go#L2203) |
| `POST` | `/api/v1/artifacts/uploads` | [uploadArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L1838) |
| `GET` | `/api/v1/artifacts/{artifactId}` | [getArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L1998) |
| `POST` | `/api/v1/artifacts/{artifactId}/complete` | [completeArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L2015) |
| `POST` | `/api/v1/artifacts/{artifactId}/download` | [downloadArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L2045) |
| `GET` | `/api/v1/chat-agents` | [listChatAgents](../service-controlplane/internal/httpapi/chat_handler.go#L89) |
| `GET` | `/api/v1/chats` | [listChats](../service-controlplane/internal/httpapi/chat_handler.go#L72) |
| `POST` | `/api/v1/chats` | [createChat](../service-controlplane/internal/httpapi/chat_handler.go#L107) |
| `DELETE` | `/api/v1/chats/{chatId}` | [deleteChat](../service-controlplane/internal/httpapi/chat_handler.go#L246) |
| `GET` | `/api/v1/chats/{chatId}` | [getChat](../service-controlplane/internal/httpapi/chat_handler.go#L156) |
| `PATCH` | `/api/v1/chats/{chatId}` | [patchChat](../service-controlplane/internal/httpapi/chat_handler.go#L163) |
| `POST` | `/api/v1/chats/{chatId}/turns` | [sendChatTurn](../service-controlplane/internal/httpapi/chat_handler.go#L197) |
| `GET` | `/api/v1/events` | [collaborationEventStream](../service-controlplane/internal/httpapi/collaboration_handler.go#L47) |
| `GET` | `/api/v1/execution-attempts` | [listExecutionAttempts](../service-controlplane/internal/httpapi/orchestration_handler.go#L501) |
| `GET` | `/api/v1/execution-attempts/{attemptId}` | [getExecutionAttempt](../service-controlplane/internal/httpapi/orchestration_handler.go#L518) |
| `GET` | `/api/v1/inbox` | [listInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L56) |
| `GET` | `/api/v1/inbox/summary` | [inboxSummary](../service-controlplane/internal/httpapi/inbox_handler.go#L77) |
| `GET` | `/api/v1/inbox/{inboxId}` | [getInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L118) |
| `POST` | `/api/v1/inbox/{inboxId}/archive` | [archiveInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L139) |
| `POST` | `/api/v1/inbox/{inboxId}/read` | [readInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L125) |
| `GET` | `/api/v1/issues` | [listIssues](../service-controlplane/internal/httpapi/collaboration_handler.go#L237) |
| `POST` | `/api/v1/issues` | [createIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L135) |
| `GET` | `/api/v1/issues/{issueId}` | [getIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L288) |
| `PATCH` | `/api/v1/issues/{issueId}` | [updateIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L301) |
| `POST` | `/api/v1/issues/{issueId}/accept` | [acceptIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L443) |
| `PUT` | `/api/v1/issues/{issueId}/access` | [updateIssueAccess](../service-controlplane/internal/httpapi/namespace_access.go#L564) |
| `GET` | `/api/v1/issues/{issueId}/activity` | [listIssueActivities](../service-controlplane/internal/httpapi/collaboration_handler.go#L980) |
| `POST` | `/api/v1/issues/{issueId}/archive` | [archiveIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L509) |
| `GET` | `/api/v1/issues/{issueId}/artifacts` | [listIssueArtifacts](../service-controlplane/internal/httpapi/collaboration_handler.go#L994) |
| `POST` | `/api/v1/issues/{issueId}/assign` | [assignIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L694) |
| `POST` | `/api/v1/issues/{issueId}/children` | [createChildIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L381) |
| `GET` | `/api/v1/issues/{issueId}/comments` | [listIssueComments](../service-controlplane/internal/httpapi/collaboration_handler.go#L774) |
| `POST` | `/api/v1/issues/{issueId}/comments` | [addIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L745) |
| `POST` | `/api/v1/issues/{issueId}/comments/preview-routing` | [previewIssueCommentRouting](../service-controlplane/internal/httpapi/collaboration_handler.go#L961) |
| `DELETE` | `/api/v1/issues/{issueId}/comments/{commentId}` | [deleteIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L908) |
| `PATCH` | `/api/v1/issues/{issueId}/comments/{commentId}` | [updateIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L873) |
| `POST` | `/api/v1/issues/{issueId}/comments/{commentId}/resolve` | [resolveIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L935) |
| `GET` | `/api/v1/issues/{issueId}/export` | [exportIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L544) |
| `POST` | `/api/v1/issues/{issueId}/reject` | [rejectIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L447) |
| `POST` | `/api/v1/issues/{issueId}/reopen` | [reopenIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L451) |
| `GET` | `/api/v1/issues/{issueId}/subscribers` | [listIssueSubscribers](../service-controlplane/internal/httpapi/collaboration_handler.go#L1048) |
| `POST` | `/api/v1/issues/{issueId}/subscribers` | [subscribeIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L1013) |
| `DELETE` | `/api/v1/issues/{issueId}/subscribers/{subscriberType}/{subscriberRef}` | [unsubscribeIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L1061) |
| `GET` | `/api/v1/issues/{issueId}/summary` | [getIssueSummary](../service-controlplane/internal/httpapi/collaboration_handler.go#L526) |
| `POST` | `/api/v1/issues/{issueId}/team-proposals` | [createTeamProposal](../service-controlplane/internal/httpapi/team_composer_handler.go#L69) |
| `POST` | `/api/v1/issues/{issueId}/team-proposals/{proposalId}/confirm` | [confirmTeamProposal](../service-controlplane/internal/httpapi/team_composer_handler.go#L178) |
| `POST` | `/api/v1/issues/{issueId}/transition` | [transitionIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L410) |
| `GET` | `/api/v1/teams` | [listCollaborationTeams](../service-controlplane/internal/httpapi/collaboration_handler.go#L1629) |
| `POST` | `/api/v1/teams` | [createCollaborationTeam](../service-controlplane/internal/httpapi/collaboration_handler.go#L1555) |
| `POST` | `/api/v1/teams/from-proposal/{proposalId}` | [saveTeamProposalAsTeam](../service-controlplane/internal/httpapi/team_composer_handler.go#L264) |
| `GET` | `/api/v1/teams/{teamId}` | [getCollaborationTeam](../service-controlplane/internal/httpapi/collaboration_handler.go#L1654) |
| `PATCH` | `/api/v1/teams/{teamId}` | [updateCollaborationTeam](../service-controlplane/internal/httpapi/collaboration_handler.go#L1668) |
| `POST` | `/api/v1/teams/{teamId}/members` | [addCollaborationTeamMember](../service-controlplane/internal/httpapi/collaboration_handler.go#L1731) |
| `DELETE` | `/api/v1/teams/{teamId}/members/{memberId}` | [removeCollaborationTeamMember](../service-controlplane/internal/httpapi/collaboration_handler.go#L1822) |
| `PATCH` | `/api/v1/teams/{teamId}/members/{memberId}` | [updateCollaborationTeamMember](../service-controlplane/internal/httpapi/collaboration_handler.go#L1771) |
| `GET` | `/api/v1/teams/{teamId}/overview` | [getCollaborationTeamOverview](../service-controlplane/internal/httpapi/team_detail_handler.go#L59) |

### Workflow 与 Automation

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/v1/automations` | [listAutomations](../service-controlplane/internal/httpapi/automation_handler.go#L146) |
| `POST` | `/api/v1/automations` | [createAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L122) |
| `POST` | `/api/v1/automations/schedule-preview` | [previewAutomationSchedule](../service-controlplane/internal/httpapi/automation_handler.go#L373) |
| `DELETE` | `/api/v1/automations/{automationId}` | [archiveAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L212) |
| `GET` | `/api/v1/automations/{automationId}` | [getAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L171) |
| `PATCH` | `/api/v1/automations/{automationId}` | [updateAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L184) |
| `GET` | `/api/v1/automations/{automationId}/deliveries` | [listAutomationDeliveries](../service-controlplane/internal/httpapi/automation_handler.go#L424) |
| `POST` | `/api/v1/automations/{automationId}/deliveries/{deliveryId}/replay` | [replayAutomationDelivery](../service-controlplane/internal/httpapi/automation_handler.go#L437) |
| `POST` | `/api/v1/automations/{automationId}/rotate-secret` | [rotateAutomationSecret](../service-controlplane/internal/httpapi/automation_handler.go#L389) |
| `GET` | `/api/v1/automations/{automationId}/runs` | [listAutomationRuns](../service-controlplane/internal/httpapi/automation_handler.go#L276) |
| `GET` | `/api/v1/automations/{automationId}/runs/{automationRunId}` | [getAutomationRun](../service-controlplane/internal/httpapi/automation_handler.go#L312) |
| `POST` | `/api/v1/automations/{automationId}/runs/{automationRunId}/cancel` | [cancelAutomationRun](../service-controlplane/internal/httpapi/automation_handler.go#L345) |
| `POST` | `/api/v1/automations/{automationId}/runs/{automationRunId}/rerun` | [rerunAutomationRun](../service-controlplane/internal/httpapi/automation_handler.go#L357) |
| `POST` | `/api/v1/automations/{automationId}/trigger` | [triggerAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L251) |
| `GET` | `/api/v1/orchestration-definitions` | [listOrchestrationDefinitions](../service-controlplane/internal/httpapi/orchestration_handler.go#L65) |
| `POST` | `/api/v1/orchestration-definitions` | [createOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L43) |
| `GET` | `/api/v1/orchestration-definitions/{definitionId}` | [getOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L90) |
| `PATCH` | `/api/v1/orchestration-definitions/{definitionId}` | [patchOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L102) |
| `POST` | `/api/v1/orchestration-definitions/{definitionId}/publish` | [publishOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L184) |
| `GET` | `/api/v1/orchestration-definitions/{definitionId}/revisions` | [listOrchestrationRevisions](../service-controlplane/internal/httpapi/orchestration_handler.go#L205) |
| `POST` | `/api/v1/orchestration-definitions/{definitionId}/runs` | [startOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L217) |
| `POST` | `/api/v1/orchestration-definitions/{definitionId}/validate` | [validateOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L160) |
| `GET` | `/api/v1/orchestration-runs` | [listOrchestrationRuns](../service-controlplane/internal/httpapi/orchestration_handler.go#L268) |
| `GET` | `/api/v1/orchestration-runs/{runId}` | [getOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L297) |
| `POST` | `/api/v1/orchestration-runs/{runId}/cancel` | [cancelOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L341) |
| `GET` | `/api/v1/orchestration-runs/{runId}/events` | [listOrchestrationEvents](../service-controlplane/internal/httpapi/orchestration_handler.go#L322) |
| `GET` | `/api/v1/orchestration-runs/{runId}/graph` | [getOrchestrationGraph](../service-controlplane/internal/httpapi/orchestration_handler.go#L309) |
| `POST` | `/api/v1/orchestration-runs/{runId}/pause` | [pauseOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L335) |
| `POST` | `/api/v1/orchestration-runs/{runId}/rerun` | [rerunOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L344) |
| `POST` | `/api/v1/orchestration-runs/{runId}/resume` | [resumeOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L338) |
| `POST` | `/api/v1/orchestration-runs/{runId}/signals/{name}` | [signalOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L377) |

### Runtime Profile、Pool 与 Host

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/v1/agent-instances` | [listAgentInstances](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L27) |
| `GET` | `/api/v1/agent-instances/{instanceId}` | [getAgentInstance](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L46) |
| `POST` | `/api/v1/runtime-bindings/{bindingId}/disable` | [disableRuntimeBinding](../service-controlplane/internal/httpapi/taskplane_handler.go#L403) |
| `POST` | `/api/v1/runtime-bindings/{bindingId}/enable` | [enableRuntimeBinding](../service-controlplane/internal/httpapi/taskplane_handler.go#L404) |
| `POST` | `/api/v1/runtime-host-enrollment-tokens` | [createRuntimeHostEnrollmentToken](../service-controlplane/internal/httpapi/taskplane_handler.go#L92) |
| `POST` | `/api/v1/runtime-host-enrollments` | [createRuntimeHostEnrollment](../service-controlplane/internal/httpapi/taskplane_handler.go#L52) |
| `GET` | `/api/v1/runtime-hosts` | [listRuntimeHosts](../service-controlplane/internal/httpapi/taskplane_handler.go#L329) |
| `GET` | `/api/v1/runtime-hosts/{hostId}` | [getRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L339) |
| `PATCH` | `/api/v1/runtime-hosts/{hostId}/capacity` | [updateRuntimeHostCapacity](../service-controlplane/internal/httpapi/taskplane_handler.go#L352) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/drain` | [drainRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L378) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/resume` | [resumeRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L381) |
| `GET` | `/api/v1/runtime-pools` | [listRuntimePools](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L148) |
| `POST` | `/api/v1/runtime-pools` | [upsertRuntimePool](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L122) |
| `GET` | `/api/v1/runtime-pools/{name}` | [getRuntimePool](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L157) |
| `PUT` | `/api/v1/runtime-pools/{name}` | [upsertRuntimePool](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L122) |
| `GET` | `/api/v1/runtime-profiles` | [listRuntimeProfiles](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L95) |
| `POST` | `/api/v1/runtime-profiles` | [upsertRuntimeProfile](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L69) |
| `GET` | `/api/v1/runtime-profiles/{name}` | [getRuntimeProfile](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L104) |
| `PUT` | `/api/v1/runtime-profiles/{name}` | [upsertRuntimeProfile](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L69) |

### 平台文件

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/files` | [listFiles](../service-controlplane/internal/product/handlers_files.go#L70) |
| `POST` | `/api/files` | [createFile](../service-controlplane/internal/product/handlers_files.go#L92) |
| `DELETE` | `/api/files/{id}` | [deleteFile](../service-controlplane/internal/product/handlers_files.go#L160) |
| `GET` | `/api/files/{id}` | [getFile](../service-controlplane/internal/product/handlers_files.go#L140) |
| `GET` | `/api/files/{id}/content` | [getFileContent](../service-controlplane/internal/product/handlers_files.go#L149) |

### 可选 Kubernetes 资源

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/v1/mcpservers` | [listMCPServers](../service-controlplane/internal/httpapi/mcpserver_handler.go#L61)（Kubernetes client） |
| `POST` | `/api/v1/mcpservers` | [createMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L29)（Kubernetes client） |
| `DELETE` | `/api/v1/mcpservers/{name}` | [deleteMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L150)（Kubernetes client） |
| `GET` | `/api/v1/mcpservers/{name}` | [getMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L98)（Kubernetes client） |
| `PATCH` | `/api/v1/mcpservers/{name}` | [patchMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L115)（Kubernetes client） |
| `GET` | `/api/v1/mcpservers/{name}/tools` | [listMCPTools](../service-controlplane/internal/httpapi/mcpserver_handler.go#L172)（Kubernetes client） |
| `GET` | `/api/v1/modelconfigs` | [listModelConfigs](../service-controlplane/internal/httpapi/modelconfig_handler.go#L64)（Kubernetes client） |
| `POST` | `/api/v1/modelconfigs` | [createModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L29)（Kubernetes client） |
| `DELETE` | `/api/v1/modelconfigs/{name}` | [deleteModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L156)（Kubernetes client） |
| `GET` | `/api/v1/modelconfigs/{name}` | [getModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L101)（Kubernetes client） |
| `PATCH` | `/api/v1/modelconfigs/{name}` | [patchModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L118)（Kubernetes client） |
| `GET` | `/api/v1/sandboxes` | [listSandboxes](../service-controlplane/internal/httpapi/sandbox_handler.go#L78)（experimental + Kubernetes） |
| `POST` | `/api/v1/sandboxes` | [createSandbox](../service-controlplane/internal/httpapi/sandbox_handler.go#L29)（experimental + Kubernetes） |
| `DELETE` | `/api/v1/sandboxes/{name}` | [deleteSandbox](../service-controlplane/internal/httpapi/sandbox_handler.go#L111)（experimental + Kubernetes） |
| `GET` | `/api/v1/sandboxes/{name}` | [getSandbox](../service-controlplane/internal/httpapi/sandbox_handler.go#L94)（experimental + Kubernetes） |

### 健康、版本、全局观察与运维

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/v1/metrics/agents` | [queryAgentMetrics](../service-controlplane/internal/httpapi/overview_handler.go#L216) |
| `GET` | `/api/v1/metrics/tokens` | [queryTokenMetrics](../service-controlplane/internal/httpapi/overview_handler.go#L162) |
| `GET` | `/actuator/health` | [healthz](../service-controlplane/internal/httpapi/server.go#L1230) |
| `GET` | `/api/v1/commands` | [listRecentCommands](../service-controlplane/internal/httpapi/session_handler.go#L898) |
| `GET` | `/api/v1/dataplanes` | [listDataPlanes](../service-controlplane/internal/httpapi/dataplane_handler.go#L99) |
| `GET` | `/api/v1/dead-letters` | [listOutboxDeadLetters](../service-controlplane/internal/httpapi/taskplane_handler.go#L425) |
| `POST` | `/api/v1/dead-letters/{eventId}/replay` | [replayOutboxDeadLetter](../service-controlplane/internal/httpapi/taskplane_handler.go#L443) |
| `GET` | `/api/v1/overview` | [fleetOverview](../service-controlplane/internal/httpapi/overview_handler.go#L287) |
| `GET` | `/api/v1/overview/timeseries` | [overviewTimeseries](../service-controlplane/internal/httpapi/overview_handler.go#L437) |
| `GET` | `/api/v1/version` | [version](../service-controlplane/internal/httpapi/server.go#L1244) |
| `GET` | `/healthz` | [healthz](../service-controlplane/internal/httpapi/server.go#L1230) |
| `GET` | `/readyz` | [readyz](../service-controlplane/internal/httpapi/server.go#L1234) |

<a id="boundaries"></a>
## 机器协议、兼容入口和未开放能力

### 机器与 Task 协议

下方端点面向注册客户端、Runtime Host 或持有执行范围 Task token 的 Agent，不是普通业务前端 Session API。Task 回调通常需要 `X-Agent-Task-Token`，Host 使用 enrollment/runtime 凭据，注册使用专用注册身份；各处理器检查持久授权、Attempt 和 fence。不要把用户 JWT、Environment key 和内部 token 互换。

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `POST` | `/api/v1/agent-registrations` | [registerExternalAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L107) |
| `POST` | `/api/v1/agent-runtime/exchange` | [registration](../service-controlplane/internal/httpapi/server.go#L444) |
| `POST` | `/api/v1/agent-tasks/{taskId}/ack` | [acknowledgeAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1251) |
| `POST` | `/api/v1/agent-tasks/{taskId}/children` | [createAgentTaskChildIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L1434) |
| `POST` | `/api/v1/agent-tasks/{taskId}/complete` | [completeAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1456) |
| `GET` | `/api/v1/agent-tasks/{taskId}/context` | [getAgentTaskContext](../service-controlplane/internal/httpapi/collaboration_handler.go#L1134) |
| `POST` | `/api/v1/agent-tasks/{taskId}/fail` | [failAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1356) |
| `POST` | `/api/v1/agent-tasks/{taskId}/inputs/delivery-failed` | [failAgentTaskInputDelivery](../service-controlplane/internal/httpapi/collaboration_handler.go#L1272) |
| `POST` | `/api/v1/agent-tasks/{taskId}/progress` | [progressAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1325) |
| `POST` | `/api/v1/agent-tasks/{taskId}/respond` | [respondAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1404) |
| `GET` | `/api/v1/agent-tasks/{taskId}/run` | [getTaskRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L575) |
| `GET` | `/api/v1/agent-tasks/{taskId}/run/artifacts` | [getTaskRunArtifacts](../service-controlplane/internal/httpapi/orchestration_handler.go#L690) |
| `GET` | `/api/v1/agent-tasks/{taskId}/run/graph` | [getTaskRunGraph](../service-controlplane/internal/httpapi/orchestration_handler.go#L588) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/node/complete` | [completeTaskRunNode](../service-controlplane/internal/httpapi/orchestration_handler.go#L601) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/node/fail` | [failTaskRunNode](../service-controlplane/internal/httpapi/orchestration_handler.go#L621) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/replan` | [replanTaskRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L642) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/signals/{name}` | [signalTaskRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L660) |
| `POST` | `/api/v1/agent-tasks/{taskId}/runtime-approvals` | [requestRuntimeToolApproval](../service-controlplane/internal/httpapi/managed_tool_approval.go#L82) |
| `POST` | `/api/v1/agent-tasks/{taskId}/runtime-approvals/{approvalId}/ack` | [acknowledgeRuntimeToolApprovalDecision](../service-controlplane/internal/httpapi/managed_tool_approval.go#L221) |
| `GET` | `/api/v1/agent-tasks/{taskId}/runtime-approvals/{approvalId}/decision` | [getRuntimeToolApprovalDecision](../service-controlplane/internal/httpapi/managed_tool_approval.go#L179) |
| `POST` | `/api/v1/agent-tasks/{taskId}/start` | [startAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1315) |
| `POST` | `/api/v1/agent-tasks/{taskId}/workspace-application` | [reportWorkspaceApplication](../service-controlplane/internal/httpapi/workspace_definition.go#L151) |
| `POST` | `/api/v1/runtime-host-enrollments/exchange` | [exchangeRuntimeHostEnrollment](../service-controlplane/internal/httpapi/taskplane_handler.go#L126) |
| `POST` | `/api/v1/runtime-hosts/register` | [registerRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L293) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/credentials` | [refreshRuntimeHostCredential](../service-controlplane/internal/httpapi/runtime_host_credentials.go#L28) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/claim` | [claimExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L511) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/cancelled` | [cancelledExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L848) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/checkpoint` | [checkpointExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L722) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/complete` | [completeExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L699) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/credentials` | [refreshExecutionCredentials](../service-controlplane/internal/httpapi/runtime_host_credentials.go#L71) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/events` | [appendExecutionAttemptEvent](../service-controlplane/internal/httpapi/taskplane_handler.go#L745) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/fail` | [failExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L824) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/observe` | [observeExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L663) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/physical-stop` | [confirmExecutionPhysicalStop](../service-controlplane/internal/httpapi/portal_native_stop.go#L46) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/preparing` | [prepareExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L650) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/renew` | [renewExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L620) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/running` | [startExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L680) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/heartbeat` | [heartbeatRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L460) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/state` | [setRuntimeHostState](../service-controlplane/internal/httpapi/taskplane_handler.go#L482) |
| `GET` | `/mcp/collaboration` | [registration](../service-controlplane/internal/httpapi/server.go#L643) |
| `POST` | `/mcp/collaboration` | [collaborationMCP](../service-controlplane/internal/httpapi/collaboration_mcp.go#L170) |

Hands 的机器入口为 `/v1/handss/environments/{environment_id}/tool-tasks/**`；旧 `/api/environments/{environment_id}/tool-tasks/**` 经 Session Portal 转发。具体 claim/renew/output/result 契约见 [ManagedWorkerProtocol](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedWorkerProtocol.java) 与 [Worker HTTP handler](../service-hands/src/main/java/io/agentscope/builder/hands/ManagedWorkerController.java)。这些接口不是客户端工具结果回填 API。

供应商回调由各供应商签名/协议鉴权：`POST /api/channels/{dingtalk|feishu|wecom}/{channel_id}/callback`、`POST /api/channels/{github|gitlab}/{channel_id}/webhook`；WeCom 同时支持 GET callback。仅在集成调度器启用时挂载，见 [transport registration](../service-controlplane/internal/product/scheduler_runtime.go)。自动化 webhook 为 `POST /hooks/v1/automations/{automation_id}/{trigger_id}`，工作来源 GitHub webhook 为 `POST /api/v1/work-sources/{work_source_id}/webhooks/github`。

### 兼容与私有边界

`/api/v1/agent-sessions/**`、`/api/sessions/**`、`/api/v1/sessions/**` 及原生运行路径属于其他/历史契约，不能作为 `/v1/sessions` 的同义入口。新 Managed Session 写入统一使用 `/v1/sessions/{id}/events`，不要改用旧 `POST .../turns`、`user-message`、`steer` 或 `inputs` 路径。Gateway 会将旧 Session 前缀路由到 Session Portal，其可用性由具体后端、权限和迁移状态决定，不能按控制面同名声明推断公网能力。

下面两组旧管理路径仅供已有集成定位，不作为快速体验的接口：

| Method | Path | 处理器 / 契约依据 |
| --- | --- | --- |
| `GET` | `/api/sessions` | [listSessions](../service-controlplane/internal/product/handlers_sessions.go#L312) |
| `POST` | `/api/sessions` | [createSession](../service-controlplane/internal/product/handlers_sessions.go#L183) |
| `DELETE` | `/api/sessions/{id}` | [deleteSession](../service-controlplane/internal/product/handlers_sessions.go#L526) |
| `GET` | `/api/sessions/{id}` | [getSession](../service-controlplane/internal/product/handlers_sessions.go#L359) |
| `PATCH` | `/api/sessions/{id}` | [updateSession](../service-controlplane/internal/product/handlers_sessions.go#L368) |
| `POST` | `/api/sessions/{id}/archive` | [archiveSession](../service-controlplane/internal/product/handlers_sessions.go#L488) |
| `POST` | `/api/sessions/{id}/restore` | [restoreSession](../service-controlplane/internal/product/handlers_sessions.go#L507) |
| `GET` | `/api/v1/sessions` | [listSessions](../service-controlplane/internal/httpapi/overview_handler.go#L46) |
| `DELETE` | `/api/v1/sessions/{sessionId}` | [deleteSession](../service-controlplane/internal/httpapi/session_handler.go#L1012) |
| `GET` | `/api/v1/sessions/{sessionId}` | [getSession](../service-controlplane/internal/httpapi/overview_handler.go#L87) |
| `POST` | `/api/v1/sessions/{sessionId}/abort` | [abortSession](../service-controlplane/internal/httpapi/session_handler.go#L496) |
| `POST` | `/api/v1/sessions/{sessionId}/archive` | [archiveSession](../service-controlplane/internal/httpapi/session_handler.go#L502) |
| `GET` | `/api/v1/sessions/{sessionId}/commands` | [listSessionCommands](../service-controlplane/internal/httpapi/session_handler.go#L881) |
| `POST` | `/api/v1/sessions/{sessionId}/compress` | [compressSession](../service-controlplane/internal/httpapi/session_handler.go#L486) |
| `GET` | `/api/v1/sessions/{sessionId}/context` | [getSessionContext](../service-controlplane/internal/httpapi/session_handler.go#L83) |
| `GET` | `/api/v1/sessions/{sessionId}/events` | [getSessionEvents](../service-controlplane/internal/httpapi/session_handler.go#L331) |
| `GET` | `/api/v1/sessions/{sessionId}/events/stream` | [streamSessionEvents](../service-controlplane/internal/httpapi/session_handler.go#L399) |
| `GET` | `/api/v1/sessions/{sessionId}/messages` | [getSessionMessages](../service-controlplane/internal/httpapi/session_handler.go#L136) |
| `POST` | `/api/v1/sessions/{sessionId}/plan-mode` | [postSessionPlanMode](../service-controlplane/internal/httpapi/session_handler.go#L747) |
| `POST` | `/api/v1/sessions/{sessionId}/restore` | [restoreSession](../service-controlplane/internal/httpapi/session_handler.go#L536) |
| `GET` | `/api/v1/sessions/{sessionId}/subagent-tasks` | [getSessionSubagentTasks](../service-controlplane/internal/httpapi/session_handler.go#L701) |
| `DELETE` | `/api/v1/sessions/{sessionId}/subagent-tasks/{taskId}` | [cancelSessionSubagentTask](../service-controlplane/internal/httpapi/session_handler.go#L722) |
| `GET` | `/api/v1/sessions/{sessionId}/tasks` | [getSessionTasks](../service-controlplane/internal/httpapi/session_handler.go#L655) |
| `POST` | `/api/v1/sessions/{sessionId}/terminate` | [terminateSession](../service-controlplane/internal/httpapi/session_handler.go#L491) |
| `GET` | `/api/v1/sessions/{sessionId}/turns` | [listSessionTurns](../service-controlplane/internal/httpapi/session_handler.go#L469) |
| `POST` | `/api/v1/sessions/{sessionId}/user-message` | [postSessionUserMessage](../service-controlplane/internal/httpapi/session_handler.go#L775) |

`/api/internal/**` 在公开 Gateway 返回 404，组件间仍独立校验服务身份。feature-gated `/api/v1/dp/**` 是旧内部协调协议，不是用户 API；旧数据面/Worker 观察路径也不属于本文推荐接入方式。历史声明归属见[路由清单](session-portal-api-inventory.md)、[Java 声明 CSV](session-portal-java-routes.csv) 和 [Go 声明 CSV](session-portal-go-route-declarations.csv)，这些历史清单不能替代当前入口/能力检查。

### 当前 Session 协议不支持

- Session 列表、修改、archive/restore/delete 等生命周期操作。
- 公共 Files、Artifacts、Budget、Usage 子资源、Checkpoints、restore/fork、Webhooks、snapshot/resource snapshot、trace/export。
- Threads、客户端工具确认/外部结果提交、图片/文件输入、preview/deltas、SSE 续传或 durable delivery。
- 在同一首批 Session 协议中使用非空 Skills、多 Agent 配置和未验证的执行身份。

控制面可管理对应资源，不等于上述 Session API 已开放；Controller、保存的 schema 或 SDK 方法存在也不能改变入口能力。对话流程以 capabilities 与本文可用端点为准。

<a id="implementation-sources"></a>
## 实现依据与维护

更新路由时同步检查注册、Gateway、安全过滤器、处理器和 capabilities；更新公开契约时同步维护中英文 Reference 与快速体验。下列源码决定当前协议：

- [Session controller](../service-session-portal/src/main/java/io/agentscope/builder/portal/api/SessionApiController.java)
- [Session entry filter and errors](../service-session-portal/src/main/java/io/agentscope/builder/portal/config/SessionApiErrors.java)
- [Session creation and representation](../service-session-portal/src/main/java/io/agentscope/builder/portal/session/SessionApi.java)
- [Event validation, reads and SSE](../service-session-portal/src/main/java/io/agentscope/builder/portal/session/ManagedSessionTurnsApi.java)
- [Durable protocol and projection](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedSessionProtocol.java)
- [Go route registration](../service-controlplane/internal/httpapi/server.go)
- [Product registration](../service-controlplane/internal/product/server.go)
- [Gateway](../service-gateway/src/main/resources/application.yml)
- [Protocol verification](../tests/session-protocol/README.md)
