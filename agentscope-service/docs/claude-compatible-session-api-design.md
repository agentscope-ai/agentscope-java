# Managed Agents Claude 兼容会话 API 设计方案

状态：已确认设计，首批实施。版本：Draft 3（中立命名版）。日期：2026 年 10 月 19 日；2026 年 10 月 23 日补充在线文本 delta。

本方案采用统一的 Session Event 写入入口，通过事件消息体中的 `managed_agents` 扩展表达新请求、轮内调整、上下文补充和定向取消；Turn 与 Input 作为可独立查询的执行资源。Claude 标准调用不必理解扩展，使用扩展的调用者可以得到明确的请求归属、持久受理和应用结果。

方案适用于原生 Managed 执行，目标是明确列出的 Claude Sessions API 兼容子集及 Managed Agents 扩展，不承诺整个 Claude 平台、全部 Managed Agents 功能或任意版本 SDK 已经兼容。本项目按本方案实施首批协议；兼容声明以实际 schema、固定 SDK 和行为验收为准。部署由独立发布步骤执行。

## 设计前提

Managed Agents 尚未正式发布，自身现有 API 不承担向后兼容义务。实现时可以直接调整路径、请求响应、事件、错误及 SDK 类型，服务端、平台 SDK 和前端按同一目标协议更新。不为兼容开发中的接口增加独立兼容入口、双协议适配、旧写入口、长期并存或历史客户端回退。

兼容承诺针对已声明支持的 Claude API 与客户端行为。幂等、权限、取消、持久受理及执行恢复属于运行正确性要求，不能因接口尚未发布而放松；但这些要求不意味着必须保存现有 HTTP schema 或提供跨版本无损数据迁移。

## 设计结论

1. 新协议的交互写入口统一为 `POST /v1/sessions/{session_id}/events`。业务意图放在单个事件的 `managed_agents` 对象中，不放进 Header，也不从自然语言猜测。
2. `new_turn` 创建独立请求；`steer/inject` 必须指向已存在且输入开放的 Turn。目标失效时拒绝，不能静默创建新 Turn。
3. 保留 Claude 的 `type/content`、`events/data` 和按类型展开的 Event 对象。扩展信息集中在 `managed_agents` 命名空间，禁止复用标准字段承载不同含义。
4. HTTP 成功表示持久受理；Input `applied` 表示已应用；Turn 终态表示执行结果。三者不能互相替代。
5. 首批提供 Claude 历史、普通 SSE 和独立 Turn 结果查询。可靠续传与一致性快照属于后续独立观察能力，不改变 Claude 历史接口的集合与过滤语义，也不作为结果读取的前置条件。
6. 对外统一使用根路径 /v1/sessions 下的目标协议，直接替换开发中的 Service API；不设置额外兼容前缀或两套平行执行入口。
7. 适配、授权与受理归 Agent Service；执行与结果提交继续使用现有 Agent Harness Runtime、Tool Worker、checkpoint 和自动 Resume。Agent Harness Runtime 不反向依赖 Agent Service。
8. 重试先通过当前身份与资源可见性检查，再查幂等记录；仅未命中的新操作进行状态、额度和目标准入校验。受理事实及回执在同一事务内冻结，输出 Header 不改变业务幂等身份。

## 协议范围和兼容承诺

### 兼容级别

| 级别 | 含义 | 对外表述 |
| --- | --- | --- |
| 格式兼容候选 | 已按官方 schema 设计，尚未完成客户端与行为验证 | Claude 风格 API 设计，不宣称可直接替换 |
| 已验证子集兼容 | 指定字段、事件类型、状态条件和固定 SDK 版本通过验收 | 列出已验证子集与不支持项 |
| 扩展能力 | Claude 标准之外的行为，具有独立版本和验收 | Managed Agents Turn Input 扩展 |

兼容基线为 `anthropic-version: 2023-06-01`、`anthropic-beta: managed-agents-2026-04-01`，以及本方案日期可见的 Sessions 文档。实现时必须保存 schema 快照、官方 SDK 精确版本和测试结果；后续版本单独验收，不自动扩展兼容声明。

### 接口兼容矩阵

首批面向单主 Agent、文本交互的 Managed Session，提供持久受理、轮内输入及独立结果读取。未声明的组合返回 unsupported_capability，不能接收后忽略。下表列出首批及后续范围；capabilities 只公布实际通过验收的能力。

首批 Agent 可以使用已支持的平台工具，但其配置不能要求尚未开放的客户端确认或外部工具结果。创建时拒绝不满足条件的配置，不能通过自动批准来绕过原工具权限。

| 接口或能力 | 首批范围 | 后续范围或限制 |
| --- | --- | --- |
| `POST /v1/sessions` | 已存在且可映射的 Agent 引用、Environment、title、metadata | vaults/resources 输入、Agent inline overrides、budget、initial_events 后续逐项开启 |
| `GET /v1/sessions/{s}` | Claude Session 资源形状及真实状态投影 | 无法计算的可选 usage/stats 不伪造为零 |
| `GET /v1/sessions` | 不属于首批承诺 | 分页、排序及过滤单独验收后开启 |
| `POST /v1/sessions/{s}/events` | 已验证的普通 user.message、无 thread 的 user.interrupt；显式 new_turn、steer/inject、定向 cancel | 工具确认、外部结果、system.message、define_outcome、线程 interrupt 后续逐项开启 |
| `GET /v1/sessions/{s}/events` | `page/limit/order/types[]` 和时间过滤，返回 `data/next_page` | 按 Claude 分页与过滤语义实现 |
| `GET /v1/sessions/{s}/events/stream` | 普通公开事件，建连后的实时流 | `event_deltas[]=agent.message` 提供尽力在线文本预览 |
| `GET /v1/sessions/{s}/turns/...` | Turn/Input 查询及独立 `/output` 结果分页 | 不依赖 durable 或 snapshot |
| `POST /v1/sessions/{s}` | 不属于首批承诺 | title/metadata 先按 Claude 合并规则验收；agent、budget、vault 更新后续开启 |
| `POST /v1/sessions/{s}/archive`、`DELETE /v1/sessions/{s}` | 不属于首批承诺 | Claude 前置状态、幂等和删除可见性验收后开启 |
| durable delivery、snapshot | 暂未启用 | 作为独立观察能力交付，不阻塞交互、查询和结果读取 |
| Resources、Threads API | 不属于首批承诺 | 公开 ID、作用域、状态和事件转发均通过专项验收后开启 |
| Agents、Environments 管理 API | 不属于本方案兼容承诺 | 初期通过现有 Managed Agents 管理入口准备资源 |
| Workflow Runs、Outcome、Memory、Vault 管理、Files 全量协议 | 不属于首批承诺 | 不能用 本平台中名字相似的产品对象直接代替 |

标准用户内容首批支持 `text`；`image` 按模型和来源能力开启。Claude 的 document/redacted 等块及 Managed Agents 的 audio/video/data/file 不能只改名后透传。未知或不支持的块返回明确错误。文件功能是否可用由 Session capability 决定，与某次部署是否有 NAS 或 Sandbox 分开。

Claude 的 Session 读取要求 Agent 快照、resources、stats、usage 等结构。投影必须从实际配置和计量数据构造，必填字段不能缺失，也不能为了通过 schema 而伪称执行主体是 Anthropic 管理的身份。不能忠实映射的 Agent 配置或执行身份应暂不进入“已验证子集”；schema 可解析不等于资源含义相同。

### 普通消息和中断的兼容行为

官方已明确输入事件可以等待处理，且 `processed_at` 可以暂为 null；未明确承诺“一条普通 user.message 必定对应一个独立 Turn，并与完整用户任务严格 FIFO”。本方案不能把这个未明确事项写成官方保证。

首批普通调用承诺空闲时发送一条 user.message，以及运行时发送 user.interrupt 或同批的 interrupt 加一条后续 user.message。无扩展消息在活跃执行中的直接追加、多条普通消息的批次合并及调度边界，须通过固定基线验收后另行开启；未开启时返回 400 unsupported_capability，不以“一条消息一轮排队”的候选行为替代 Claude 基线。需要明确的独立请求和排队保证时，使用显式 intent=new_turn；轮内插话使用带 turn_id 的 steer/inject。

在首批无其他活跃线程或 Workflow Run 的场景，interrupt 加后续 message 的顺序固定为：持久受理；停止当前模型响应并等待必要工具结清；公开 user.interrupt；公开 session.status_idle，stop_reason.type=end_turn；随后启动后续消息并公开 session.status_running。下一轮已经排队也不能省略这个 idle 边界。工具执行期间中断可以延后生效，不能把 HTTP 200 当成已经停止。[Claude 中断顺序](https://platform.claude.com/docs/en/managed-agents/events-and-streaming#interrupt-the-agent)

若已有显式 new_turn 队列，后续消息不抢占既有发布顺序；这类混合交互的 Turn 归属由扩展定义，不额外承诺与纯 Claude 调度相同。人工待办、预算和生命周期限制仍按已验证基线校验，普通消息不能代替工具答复。多线程和 Workflow Run 可能使 Session 持续 running，属于后续专项能力。

## 接入地址和版本

### 地址

统一 base URL 为 `https://service.example.com`，域名为部署示例：

| 用途 | 请求路径示例 |
| --- | --- |
| Claude 兼容创建与交互 | `POST /v1/sessions`、`POST /v1/sessions/S/events` |
| Claude 兼容历史与实时输出 | `GET /v1/sessions/S/events`、`GET /v1/sessions/S/events/stream` |
| Managed Agents 扩展资源读取 | `GET /v1/sessions/S/turns/T1`、`GET /v1/sessions/S/turns/T1/output` |

Gateway 将统一 /v1/sessions 路径交给 Agent Service。同一 Session 只有一套执行资源和公开协议；Header 仅选择已声明的输出扩展，不选择历史版本的 Service schema。响应内可访问 URL 根据外部 public base URL 生成，路径与上表一致。

目标协议不提供 POST /turns 或 POST /turns/{t}/inputs，全部交互写入 events。现有调用方同步改为目标协议，不保留历史写入口或请求体别名。后续若增加便利写入口，作为新扩展明确设计，并复用同一受理过程。

### Header

| Header | 规则 |
| --- | --- |
| `x-api-key` | 使用本平台应用凭据，不是调用 Anthropic 账户的密钥 |
| `Authorization: Bearer ...` | 允许平台用户令牌；与 x-api-key 同时出现时拒绝，避免身份歧义 |
| `anthropic-version` | 首批仅接受 2023-06-01；其他版本验收后加入，不据此承诺其他 Claude API |
| `anthropic-beta` | Claude 兼容调用携带 Managed Agents Beta；未知 Beta 明确拒绝，不静默启用 |
| `anthropic-workspace-id` | 仅通过已授权的 Workspace 到 Tenant/Namespace 绑定解析；不是任意命名空间选择器 |
| `X-Managed-Agents-Tenant/Namespace` | 原生作用域选择；与 Workspace 绑定冲突时拒绝 |
| `Idempotency-Key` | 见幂等章节；扩展事件要求稳定键 |
| `Managed-Agents-Extensions` | 首批仅支持可选输出扩展 turn-inputs-v1；不参与业务幂等摘要 |
| `Last-Event-ID` | 首批 Claude SSE 不提供可靠续传；后续独立 delivery 接口定义其支持规则 |
| `request-id` | 每次 HTTP 尝试的响应关联号，包含重试尝试 |

业务意图和目标 ID 只能放在消息体。Header 不接受“当前 Turn”“steer”等业务指令。单个事件出现合法的 managed_agents 扩展时，POST 响应自动返回该事件的扩展回执；首版消息体语义固定为 turn-inputs-v1。Claude 既有 GET/SSE 接口增加输出字段或扩展事件时需要显式选择；专用 Turn/Input/output 读接口本身就是扩展资源，不要求该 Header。扩展版本未知时返回 400，不回退为普通用户消息。

SDK 可通过配置 base URL 使用兼容子集。高级调用是否可通过官方 SDK 的额外请求体机制传入，需要逐版本验证；不能因 Python/TypeScript 某一版本接受未知字段而推定所有 SDK 都接受。平台 SDK 应提供类型化封装。

## 资源模型

| 资源 | 身份与含义 |
| --- | --- |
| Session | 固定执行作用域及配置视图，承载多轮工作 |
| Event | 一次输入或公开事实；持久事件有稳定 ID |
| Turn | 一个独立执行请求，具有稳定 ID、状态和结果 |
| Input | 针对某个 Turn 的 steer、inject、reply 或 cancel，具有独立应用回执 |
| Required action | 等待确认或外部结果的业务待办，关联工具事件与实际工具调用 |
| Thread | 独立 Agent 上下文；不等于 Turn |
| Run/Attempt | 内部执行激活或重试记录；不代替 Turn ID |

ID 全部作为不透明字符串处理，示例使用 evt_、turn_、input_ 前缀。工具 Event ID、原生 tool_call_id、Action ID 和 Tool Worker task_id 必须分别保存；客户端不能根据前缀或字符串拼接相互推导。ID 形状由目标协议定义，不以兼容当前生成格式为前提。

一个 new_turn 事件创建一个 Turn；一个显式 steer/inject/cancel 事件创建一个 Input。一个工具确认或外部结果事件关联一个 reply Input。普通 Claude 消息的映射受前述兼容范围约束。持久 Event ID 是输入处理前就分配的身份，不能到 Agent Harness Runtime 生成原生消息后再分配另一份输入事件。

## 创建和查询 Session

### 创建请求

~~~http
POST /v1/sessions
Content-Type: application/json
Idempotency-Key: session-001
~~~

~~~json
{
  "agent": {"type": "agent", "id": "agent_demo", "version": 3},
  "environment_id": "env_demo",
  "title": "性能分析",
  "metadata": {"ticket": "CASE-001"}
}
~~~

首批 agent 接受 ID 或版本引用；其余创建字段按兼容矩阵和 capability 校验。资源 ID 必须属于本平台且可被调用者使用。不能将调用者提供的 metadata、managed_agents 或 Header 用作内部 actor 身份。

不带 initial_events 的 Session 返回空闲状态。若以后开启 initial_events，创建与初始输入的校验、持久保存和失败边界须满足独立契约，不能先返回创建成功后再默默丢弃初始事件。

### Session 响应

响应采用冻结的 Claude Session schema。下例展示单 Agent 配置的目标形状；模型、工具、技能和执行身份仅能使用可忠实映射的真实配置：

~~~json
{
  "id": "S",
  "type": "session",
  "agent": {
    "id": "agent_demo",
    "type": "agent",
    "version": 3,
    "name": "性能分析助手",
    "description": null,
    "execution_identity": {"type": "service_account"},
    "model": {"id": "SUPPORTED_MODEL"},
    "system": "分析性能问题并报告依据。",
    "tools": [],
    "mcp_servers": [],
    "skills": [],
    "multiagent": null
  },
  "environment_id": "env_demo",
  "title": "性能分析",
  "metadata": {"ticket": "CASE-001"},
  "status": "idle",
  "archived_at": null,
  "budget": null,
  "resources": [],
  "outcome_evaluations": [],
  "stats": {},
  "usage": {},
  "vault_ids": [],
  "created_at": "2026-10-19T07:00:00Z",
  "updated_at": "2026-10-19T07:00:00Z"
}
~~~

execution_identity 的示例只适用于实际存在且通过兼容验证的 service-account 执行身份。模型配置的实际必填子字段以冻结 schema 为准；本示例不是对任意模型配置的放行。可选统计暂不可用时省略其子字段，不补零。未实现的 Outcome 功能不能接受创建请求，仅在无相关记录时返回空列表。

启用 turn-inputs-v1 后增加 managed_agents：

~~~json
{
  "schema_version": 1,
  "runtime_kind": "managed",
  "lifecycle": "active",
  "current_turn_id": null,
  "pending_turn_count": 0,
  "configuration_revision": "3"
}
~~~

这个对象位于 Session 响应的 `managed_agents` 字段，不取代标准 `status`。Session 更新不属于首批；后续先开放 title/metadata，并统一按 Claude 基线实现 metadata 合并，不再提供另一套整体替换语义。

后续 Session 列表能力开启时返回 `data/next_page/prev_page`，默认 desc；公开 limit 默认 100、范围 1..256，这是本平台服务限制，不标称为 Claude 官方默认。page 绑定身份可见范围、协议版本、排序与过滤。过滤必须在完整结果集上应用，不能只过滤一页后错误报告分页结束。

## 统一事件写入

### 请求结构

~~~json
{
  "events": [
    {
      "type": "user.message",
      "content": [{"type": "text", "text": "分析项目性能"}],
      "managed_agents": {
        "intent": "new_turn"
      }
    }
  ]
}
~~~

首版建议限制每批 1..50 个事件、JSON 总大小不超过 1 MiB；这是本平台设计限额，最终以 capabilities 公布并参与 SDK 验证。限制不适用于已授权的大文件上传接口。50 并非从 Claude send-events 文档推定的上限。

| 字段 | 规则 |
| --- | --- |
| events | 必填，非空数组，保留数组顺序 |
| events[].type | Claude 输入事件类型；首批仅接受已公布类型 |
| events[].content | 对用户消息为非空 content block 数组，不接受字符串快捷形式 |
| events[].managed_agents | 可选对象；出现时启用本事件的扩展语义 |
| managed_agents.intent | user.message 的扩展对象中必填，值为 new_turn、steer、inject；user.interrupt 的扩展对象中必填 cancel |
| managed_agents.turn_id | steer/inject/cancel 必填；不得填 current、latest 或空字符串 |

role 由事件类型表达，不再增加可任意填写的 role。客户端不得发送 agent.*、session.*、span.*、managed_agents.* 输出事件。标准字段与扩展字段都做白名单校验，空 managed_agents 或未知扩展字段直接报错；后续工具答复的可选 turn_id 约束按工具章节单独定义。

new_turn 禁止带 turn_id；steer/inject 不允许 session_thread_id；定向 cancel 不允许同时指定 session_thread_id。首版不接受客户端自定义应用时机、优先级、强制执行或绕过权限参数，减少未定义组合。

### 独立新请求

~~~json
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "另外生成招聘说明"}],
    "managed_agents": {"intent": "new_turn"}
  }]
}
~~~

new_turn 永远创建独立 Turn，不修改现有 Turn。只要 Session 生命周期和额度允许，即使前一轮正在推理、waiting_tool 或等待人工答复，也可持久受理，等待前一轮结清。队列满返回 429，不伪称排队成功。

同一 Session 一次只推进一个主 Turn；T1 挂起不会使 T2 越过 T1。并发 HTTP 请求不以网络到达时间定义全序；执行顺序以平台持久发布顺序为准。admission_sequence 用于受理查询，不可直接宣称是 Broker 执行顺序。批内多个 new_turn 必须保留数组顺序发布，不能被同批并行发布打乱。

### 调整和补充当前请求

~~~json
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "优先检查数据库，先不要改缓存"}],
    "managed_agents": {"intent": "steer", "turn_id": "T1"}
  }]
}
~~~

inject 使用相同格式，只将 intent 改为 inject。两者都不创建新 Turn，不自动取消已有工具调用，也不覆盖历史用户消息或系统提示词。steer 表达后续执行约束或方向；inject 表达补充事实。首批保留这两个意图标签并共用安全推理边界追加机制，不为 inject 增加独立调度或状态机，也不承诺 steer 具有强制重规划或高于系统权限的优先级。

| 目标状态 | steer/inject 规则 |
| --- | --- |
| accepted、preparing 且输入开放 | 可受理，在首次相关模型请求前应用 |
| running 且输入开放 | 可受理，在下一安全推理边界应用 |
| waiting_tool | 可受理；当前工具结果结清并恢复时，在下一模型调用前应用 |
| waiting 人工答复 | 可受理，但不能代替 reply；必需答复齐全后再应用并推理 |
| completed、failed、cancelled 或输入已关闭 | 新请求返回 409 input_closed |
| Session archived/deleted | 拒绝；原键重试的可见性另按幂等规则处理 |

安全边界定义为：必要工具结果已持久提交、并行工具组所需结果已齐备、下一模型请求尚未构造或发送。多条普通 Input 按其 sequence 应用；cancel 的停止检查优先于未应用的 steer/inject。已发送的模型请求不会被追溯改写。

受理 Input 与关闭输入必须串行化。若关闭先提交，立即 409；若受理先提交，运行时必须应用或持久拒绝它，不能在有未结清 Input 时直接遗忘并终结 Turn。如果本轮没有下一推理机会，记录 rejected/turn_finished，不为了兑现插话而隐式启动新的 Turn。

现有等待工具机制不会因 steer/inject 自动伪造 Resume。本方案要求它们在真实恢复后及时应用；“长工具未返回时立即再启动推理”不属于首版能力。

### 工具审批和外部执行结果

本节定义后续工具交互能力；首批关闭相应 capability 时返回 unsupported_capability。能力开启后保留 Claude 的输入形式：

~~~json
{
  "events": [{
    "type": "user.tool_confirmation",
    "tool_use_id": "evt_tool_1",
    "result": "allow"
  }]
}
~~~

~~~json
{
  "events": [{
    "type": "user.custom_tool_result",
    "custom_tool_use_id": "evt_custom_tool_1",
    "content": [{"type": "text", "text": "实际查询结果"}],
    "is_error": false
  }]
}
~~~

Agent Service 通过公开工具 Event ID 解析真实 Session、Turn、Action 和工具调用，不信任客户端填写的物理任务 ID。确认映射为 allow/reason；外部结果映射为 content/is_error。若调用者可选填写 managed_agents.turn_id，只作为一致性约束，必须与实际关联相同，不改变路由。

普通 Tool Worker 工具由平台返回结果，客户端不能用 user.tool_result 接管。只有实际要求外部执行的待办可以接受外部结果；user.tool_result 还必须符合其 self_hosted 场景约束。失败工具结果保留 is_error，不自动等同于整个 Turn 失败。

审批检查 approve，外部结果检查 interact；两者不能用“统一事件入口”混成 invoke。答复 Input 应用后继续同一个 Turn；等待多个动作时可以分批答复，未齐备前不提前调用模型。

工具结果还具有业务级去重，不能只依赖请求键。原键重试重放原回执；对于新的请求键，Claude 已明确的重复 custom result 无效果成功规则须在兼容路径保留，不能一律转换成 action_already_resolved。第二次结果不得覆盖第一次有效结果或再次触发推理；若携带冲突内容，按基线验证结果处理，并明确已生效结果保持不变。扩展回执关联已有效的 Input，是否创建新的纯审计 Event 由冻结的兼容响应规则确定。首次答复正在应用时的竞争同样必须去重。

### 定向取消

~~~json
{
  "events": [{
    "type": "user.interrupt",
    "managed_agents": {"intent": "cancel", "turn_id": "T1"}
  }]
}
~~~

这是 Managed Agents 的精确取消扩展，不是对 Claude user.interrupt 的重新定义。它固定取消 T1，不取消已排队 T2，不归档 Session，不回退外部副作用。T1 可处于排队、准备、执行或等待阶段；已终结目标的新取消请求返回 409 turn_terminal。原键重复取消返回原受理回执。

不带 managed_agents 的 user.interrupt 首批只接受无 session_thread_id 的单主线程场景，停止当前轮但不删除已排队请求。受理时持久固定目标，重试不能重新绑定到下一轮。开启线程能力后，指定 thread 针对相应线程，不指定时按已验证范围处理非归档线程；未开启的线程或 Workflow Run 语义返回 unsupported_capability。

需要取消后改做新任务时，可在同一批次提交 interrupt 和后续 user.message/new_turn。后续工作不能在停止事实尚未结清时抢跑；标准中断路径必须先公开 idle/end_turn，再开始下一轮。已有排队 Turn 不因这个批次被静默删除。若要替换整个队列，客户端必须逐个取消，首批没有隐式 replace_all。

## 批次受理与返回

### 原子边界

扩展批次先按幂等章节完成认证、规范化和请求键查询。已命中的请求重放原回执，不再做目标状态、输入开放或额度检查；只有未命中的新批次才全量校验、逐项授权并在单事务内持久受理。任一新事件的目标、权限或准入失败，整批不受理。原子范围只包括受理记录、ID、额度、冻结回执及 Outbox，不包括模型和外部工具执行。

批内事件按索引绑定身份、目标与请求键。首版只能引用提交前已存在的 Turn/工具事件，不能引用同批尚未返回的服务端 ID。工具回复与 steer 可以共同指向已存在 Turn，并按校验后的顺序处理。定向 cancel 与同一目标的新 steer/inject 在同批出现时拒绝 ambiguous_batch，避免“已知不能应用却仍受理”的组合。

无扩展的 Claude 批次要通过官方兼容验证；若其失败边界与上述设计不同，应为标准模式保留基线行为。对外必须区分“扩展批次的原子受理保证”和“Claude 已验证的批次行为”，不能把本平台的设计说成 Claude 保证。

### 成功响应

写入成功返回 HTTP 200 和 data 数组；不改成直接返回 Turn 或 Input，不用 202 代表模型还在工作。每个已受理事件都有稳定 Event ID，数组与请求对应。

~~~json
{
  "data": [{
    "id": "evt_user_1",
    "type": "user.message",
    "content": [{"type": "text", "text": "分析项目性能"}],
    "processed_at": null,
    "managed_agents": {
      "schema_version": 1,
      "intent": "new_turn",
      "turn_id": "T1",
      "receipt": {"type": "turn", "id": "T1", "status": "accepted"}
    }
  }]
}
~~~

steer/inject 的扩展回执形式：

~~~json
{
  "data": [{
    "id": "evt_steer_1",
    "type": "user.message",
    "content": [{"type": "text", "text": "优先检查数据库"}],
    "processed_at": null,
    "managed_agents": {
      "schema_version": 1,
      "intent": "steer",
      "turn_id": "T1",
      "input_id": "I1",
      "receipt": {"type": "input", "id": "I1", "status": "accepted"}
    }
  }]
}
~~~

request-id 在响应 Header 中。回执在受理事务内冻结，首次响应和重试均由这份快照呈现；不得在提交后读取最新状态替换首次响应。新建 Turn/Input 的初始回执为 accepted；业务去重关联既有 Input 时，保存当时的既有状态。客户端通过 REST 查询后续最新状态。

processed_at 表示输入事件已被相应处理路径消费，不表示 Turn 完成：初始用户消息在成为执行输入时设置；steer/inject 在 applied 提交时设置；被拒绝的 Input 保留 null，并通过 Input 状态解释。工具结果若基线要求“收到时已处理”，则其 processed_at 可在校验并持久登记结果时设置，Input applied 仍单独表示运行状态已提交。不得为了填时间而提前伪造应用事实。

兼容历史可以看到已受理但尚未处理的输入 Event。该 Event 的处理投影更新后，历史查询可返回非空 processed_at；不需要修改底层不可变事实日志。实时输出仅在相应消费边界发送普通 user.* 处理事件；受理通知使用扩展事件，防止“受理”和“处理”各发一条相同逻辑用户消息。

## Turn 和 Input 查询

### 查询接口

| 方法和路径 | 返回 |
| --- | --- |
| `GET /v1/sessions/{s}/turns` | Turn 列表，支持 after、limit、order、status、input_event_id |
| `GET /v1/sessions/{s}/turns/{t}` | 单个 Turn |
| `GET /v1/sessions/{s}/turns/{t}/output` | 已提交最终输出，支持 after、limit；首批可用且不要求 durable |
| `GET /v1/sessions/{s}/turns/{t}/inputs` | Input 列表，支持 after、limit、status、event_id |
| `GET /v1/sessions/{s}/turns/{t}/inputs/{i}` | 单个 Input |
| `GET /v1/sessions/{s}/snapshot` | 后续观察能力：Session 一致性资源快照 |
| `GET /v1/sessions/{s}/turns/{t}/snapshot` | 后续观察能力：指定 Turn 一致性资源快照 |
| `GET /v1/sessions/{s}/resources/{kind}` | 后续观察能力：指定 snapshot_id 的资源分页 |
| `GET /v1/sessions/{s}/capabilities` | 协议、兼容子集和运行能力 |

这些是同一 base URL 下的扩展读接口，不是 Claude 已有接口。输入 Event ID 可以用于反查 Turn/Input，即使调用方未启用扩展输出，也能在授权范围内查询关联。

### Turn 格式

~~~json
{
  "id": "T1",
  "type": "turn",
  "session_id": "S",
  "input_event_ids": ["evt_user_1"],
  "status": "waiting_tool",
  "inputs_open": true,
  "cancel_requested": false,
  "admission_sequence": 12,
  "execution_sequence": 9,
  "delivery_status": "queued",
  "created_at": "2026-10-19T07:00:01Z",
  "updated_at": "2026-10-19T07:00:03Z",
  "started_at": "2026-10-19T07:00:02Z",
  "completed_at": null,
  "output_available": false,
  "output_url": "https://service.example.com/v1/sessions/S/turns/T1/output",
  "error": null
}
~~~

execution_sequence 映射已经分配的发布顺序，尚未分配时为 null；不能用它预测未发布请求的顺序。delivery_status 沿用实际投递账本的 pending/queued 等枚举，并由 capability/schema 固定；不能用 queued 判断模型是否开始或 MQ 是否 ACK。

output_available 仅在真实最终输出已经提交且可由 output_url 读取时为 true。failed/cancelled 不保证有最终输出，部分历史输出仍可查询。结果通过独立 /output 接口分页获取，避免把不受限内容嵌入每次状态轮询；平台 SDK 提供 final_output 辅助读取。该能力不要求 durable、snapshot 或客户端先遍历事件历史。

### Turn 状态

| 状态 | 语义 |
| --- | --- |
| accepted | 独立请求已经持久受理，尚未开始执行 |
| preparing | 准备执行资源，不能假称模型已输出 |
| running | 当前执行正在推进 |
| waiting_tool | 等待平台工具结果，仍属于同一个 Turn |
| waiting | 等待真实人工确认或外部结果 |
| completed | 执行成功终态 |
| failed | 执行失败终态 |
| cancelled | 已有停止和结清事实的取消终态 |

主要路径为 accepted → preparing/running → waiting_tool/waiting → running → completed/failed/cancelled。中间状态可以重复；恢复不产生新 Turn。终态不被重试、迟到工具结果或旧执行者回退。

取消进度通过 cancel_requested 和 Input 回执表达，不新增一个与终态竞争的 cancelling 主状态。cancel applied 表示运行时已提交该取消操作的处置，不保证最终一定是 cancelled；若完成事实先提交，Turn 可能保持 completed，Input 的 resolution_reason 记录 already_completed。工具副作用未明时继续等待核对，不提前释放成 cancelled。

### Input 格式

~~~json
{
  "id": "I1",
  "type": "input",
  "session_id": "S",
  "turn_id": "T1",
  "event_id": "evt_steer_1",
  "input_type": "steer",
  "status": "applied",
  "delivery_status": "delivered",
  "sequence": 3,
  "action_id": null,
  "created_at": "2026-10-19T07:00:04Z",
  "applied_at": "2026-10-19T07:01:35Z",
  "rejected_at": null,
  "rejection_reason": null,
  "resolution_reason": null
}
~~~

Input 状态只有 accepted → applied 或 accepted → rejected；accepted 是持久受理，delivered 只表示投递，applied 才表示相应输入处置与运行状态已提交。暂时投递失败不改成 rejected，也不要求客户端重新提交新 Input。

rejection_reason 是稳定机器码，例如 turn_finished、action_not_pending、permission_revoked；人类可读解释另放 error.message，客户端不解析解释文本。cancel 的处置原因放 resolution_reason，不与输入拒绝混淆。

Turn/Input 列表返回 data/next_cursor/has_more；默认 limit=100、范围 1..256。Turn 默认 desc，Input 固定 sequence 升序。游标绑定 Session、Turn、排序及过滤，列表尾 next_cursor=null。它们不同于 Claude Event page，也不同于实时 cursor。

## Session 状态和生命周期

Claude Session 状态只输出 idle、running、rescheduling、terminated，不能把 waiting_tool、accepted 或 archived 填入标准 status。精确状态在 Turn 和 managed_agents.lifecycle 中表达。

| 实际情形 | Session 投影 |
| --- | --- |
| 无活跃工作且无待启动请求 | idle，可接受下一次普通交互 |
| 推理、平台工具执行或等待其真实结果 | running |
| 等待人工确认或客户端工具结果，且没有其他活跃线程 | idle，状态事件携带 requires_action 和阻塞 Event ID |
| 瞬时错误后实际进入自动重试 | rescheduling；普通请求排队不使用该状态 |
| archived/deleted 或不可恢复终止 | terminated；具体生命周期由扩展或读取可见性表达 |

waiting_tool 不是 requires_action，不能要求用户填平台工具结果。单主线程自然完成或标准中断时先提交并公开 idle/end_turn 边界，随后可立即为可推进的 T2 提交 running；不能因队列非空而吞掉标准中断的 idle 事件。不可恢复错误按真实事实进入 terminated，不伪造正常结束。Session GET 返回读取时的最新状态，因此可能已是 running；这与历史中保留前一轮的 idle 不矛盾。队列和精确请求归属通过 Turn 资源及扩展事件表达。

新协议须单独投影状态，不能直接复用当前 runtime() 中“有 current_turn_id 就 running”的简化判断。该判断会把人工等待与 Claude idle/requires_action 混淆。

archive/delete 统一遵循已验证的 Claude 空闲及未结清动作检查；有活跃工作时返回基线要求的状态错误，提示先中断。实际内部清理可以异步，但对外前置条件和响应语义不能据此改变。

删除成功的外部可见性与后台清理分开：只有删除契约允许的资源已经不可读、后续输入被拒绝时，才返回 session_deleted；后台安全清理不能借此宣称物理副作用回滚。首批不提供 restore；后续需要时作为 Managed Agents 扩展设计，不伪称 Claude 已有 API。

预算暂停、Workflow Run、子线程汇总涉及额外状态机，未通过专项验收时不开放。不能把“提高预算自动恢复”和“失败 Turn 显式 resume”混为一谈。

## 公开事件格式和投影

### 标准事件

公开事件按 Claude 类型展开数据：

~~~json
{
  "id": "evt_answer_1",
  "type": "agent.message",
  "content": [{"type": "text", "text": "主要瓶颈是数据库全表扫描。"}],
  "processed_at": "2026-10-19T07:01:36Z"
}
~~~

启用 turn-inputs-v1 后，同一对象增加：

~~~json
{
  "managed_agents": {
    "schema_version": 1,
    "session_id": "S",
    "turn_id": "T1",
    "item_id": "item_answer_1"
  }
}
~~~

这只是新增字段片段。公开时间统一采用 UTC RFC3339 字符串；内部毫秒时间须正确转换后再写入 processed_at。processed_at 的可选性与意义按事件类型定义，不能把所有输入时间一律当成执行完成时间。

### 扩展事件

只有启用 turn-inputs-v1 才发送以下类型：

| 类型 | 关键数据 |
| --- | --- |
| managed_agents.turn.accepted | turn_id、input_event_ids、受理时间 |
| managed_agents.turn.preparing | turn_id、status |
| managed_agents.turn.running | turn_id、status；恢复时可再次出现 |
| managed_agents.turn.waiting_tool | turn_id、公开工具引用 |
| managed_agents.turn.requires_action | turn_id、待答复公开 Event ID |
| managed_agents.turn.completed | turn_id、output_event_ids、独立 output_url |
| managed_agents.turn.failed | turn_id、公开错误 |
| managed_agents.turn.cancelled | turn_id、取消处置结果 |
| managed_agents.input.accepted | input_id、turn_id、event_id、input_type |
| managed_agents.input.applied | input_id、turn_id、应用时间 |
| managed_agents.input.rejected | input_id、turn_id、rejection_reason |
| managed_agents.item.finalized | item_id、turn_id、最终输出引用 |

扩展事件同样有自身的稳定 id、type、processed_at，业务内容放在 managed_agents 对象：

~~~json
{
  "id": "evt_input_applied_1",
  "type": "managed_agents.input.applied",
  "processed_at": "2026-10-19T07:01:35Z",
  "managed_agents": {
    "schema_version": 1,
    "session_id": "S",
    "turn_id": "T1",
    "input_id": "I1",
    "event_id": "evt_steer_1",
    "input_type": "steer",
    "status": "applied"
  }
}
~~~

data/managed_agents 命名不得同时承载两套平行 payload。未启用扩展时，严格兼容流不发送未知 managed_agents.* 类型，也不强迫官方 SDK 处理这些类型。

### 投影映射

| 已提交原生事实 | 新协议投影 | 约束 |
| --- | --- | --- |
| 输入受理 | 扩展 accepted 事件 | 与受理和 Outbox 同事务；不伪称已被模型消费 |
| 原生 message/user | 对应已分配 ID 的 user.message | 关联原输入，不能再次生成独立用户消息 |
| item.completed，assistant message | agent.message | 完整内容以该消息的提交为准 |
| item.completed，user 或 tool_result | 对应 user/tool 事件 | 不能一律改名为 agent.message |
| item.finalized | 扩展 item.finalized，标记最终输出 | 不再重复发送相同 agent.message |
| tool.requested/dispatched/completed | 对应 agent.tool_use/tool_result 或 MCP/custom 类型 | 按真实执行归属分类；不能按工具名字猜测 |
| required_action.created/resolved | 工具事件、requires_action 及答复结果 | Action ID 与工具 Event ID 保持持久映射 |
| turn.running/waiting/terminal | Session 标准状态边界及扩展 Turn 状态 | 保留单主线程 end_turn/中断 idle；未来多线程需按实际活动汇总 |
| model.started/usage.recorded | span.model_request_start/end | 配对 start ID；使用真实模型计量 |
| 内部 run.started/run.ended | 通常不投影成 workflow_run.* | 内部执行激活不是 Claude Workflow Run |

一个原生事实可以产生多个不同公开事实，但每种投影的 ID 必须由稳定关联生成，例如原生身份加投影种类及分项身份。恢复和重复投递不能再次分配新 ID。工具完成按逻辑工具调用归并，不能将一次挂起误报为工具完成。

首批维护公开逻辑 Event 及其处理投影，兼容历史包含已受理的 pending 用户事件。普通 SSE 只在处理边界投递该 user.message；accepted 扩展通知用独立 ID 引用它。内部可以复用现有已提交日志与投影索引，无须为首批另建完整的 durable delivery 产品协议。

Managed-Agents-Extensions 仅增加所选扩展字段和通知类型，不移除标准历史中的 pending 输入，也不改变标准 types、排序或时间过滤。后续 delivery 若提供不同的可投递事实集合，使用独立接口和游标；不能通过 Header 将 GET /events 切换成另一套历史集合。

来源原始字节、checkpoint、内部 prompt、凭据、租约令牌与私有 thinking 不进入公开投影。内容先经过现有公开字段过滤，再进行 Claude 结构映射。

## 历史事件和 SSE

### Claude 兼容历史

`GET /v1/sessions/{s}/events?page=...&limit=100&order=asc` 返回：

~~~json
{
  "data": [
    {
      "id": "evt_answer_1",
      "type": "agent.message",
      "content": [{"type": "text", "text": "主要瓶颈是数据库全表扫描。"}],
      "processed_at": "2026-10-19T07:01:36Z"
    }
  ],
  "next_page": null
}
~~~

支持重复 types[] 和 created_at[gt/gte/lt/lte]。Claude 文档使用 processed_at 比较这些时间条件；适配器不能改为按内部 HTTP 受理时间过滤。相同时间的稳定分页、未处理事件的 null 排序以及查询期间处理状态变化，均须用冻结 schema 与兼容样本验证；通过前不能宣称完整历史行为兼容。

新 page 是绑定协议、身份范围、过滤、排序和固定读取上界的不透明 token。保留过的第一页可以读完同一前缀，新增事实不会插入旧页而导致漏读。pending 输入后续变为 processed 的查询可见性须由版本化投影保证，不能只按当前表值翻页。

### 默认实时流

默认 `GET /events/stream` 从建连时捕获的公开提交位置之后开始，不回放历史。建连和捕获水位必须有一致性边界，不能在“先查 head，后建立通知”之间漏事件。持久日志与补偿回读仍是事实来源，通知仅用于唤醒。

~~~text
event: agent.message
data: {"id":"evt_answer_1","type":"agent.message","content":[{"type":"text","text":"主要瓶颈是数据库全表扫描。"}],"processed_at":"2026-10-19T07:01:36Z"}

: keepalive
~~~

Claude 基线的具体 SSE 帧字段由固定 SDK 验收确定，不声明 Last-Event-ID 是 Claude 原生续传保证。首批 /events/stream 收到 after 或续传请求时返回 400 unsupported_capability；不会因输出 Header 改为历史回放。

基础客户端恢复时先打开新流并缓冲，再分页读取历史，然后按持久 Event ID 去重并消费缓冲。先读完历史后才开一个仅接新事件的流会产生空窗，不能作为无漏读流程。

### 后续可靠续传能力

可靠观察后续以独立的 `/v1/sessions/{s}/delivery` 和 `/delivery/stream` 读接口设计，不复用 Claude GET /events 的集合或分页语义。首批 capabilities 中 durable-events-v1=false，相应接口返回 unsupported_capability；Turn/Input 查询与 /output 继续可用。

该能力另行冻结完整请求响应。其约束是：可投递事实具有稳定 Event ID 和不可变位置；一个源事实拆成多个公开事实后仍可从中间续传；REST delivery、SSE 与 snapshot.as_of 对齐；位置绑定作用域及投影版本，与 Claude page 区分。该能力初版不增加过滤游标、任意输出组合或网络 exactly once 保证。

客户端只有成功应用事件后才保存位置。keepalive、preview 和运输错误不推进持久位置；断线允许重复投递，依靠 Event ID 去重。这些规则不要求普通 Claude SSE 具备原生可靠续传能力。

### Preview delta

2026-10-23 修订：显式 `event_deltas[]=agent.message` 启用 event_start/event_delta 和 content_delta 结构；完整 agent.message 覆盖临时累积。仅支持公开助手文本，不支持 thinking 或工具预览。Messages API 的 content_block_delta 不能直接充当 Managed Agents preview。

~~~text
event: event_start
data: {"type":"event_start","event":{"type":"agent.message","id":"evt_answer_1"}}

event: event_delta
data: {"type":"event_delta","event_id":"evt_answer_1","delta":{"type":"content_delta","index":0,"content":{"type":"text","text":"主要瓶颈"}}}
~~~

Preview 不分配自身的 Event ID/processed_at/cursor，不进入兼容历史或 durable log，也不按被预览 Event ID 丢重所有片段。durable 流中的 preview 不设置 SSE id，只由已提交持久事实推进水位。重连不承诺重放丢失 preview，客户端靠完整事件或模型请求结束收敛。

预览从模型响应适配器中的公开文本构造，只向模型开始时已鉴权、已选择的在线连接尽力发送，经 Redis 定向传输，不等待执行提交，也不进入可靠 MQ 或投影包。它不能授权工具或驱动恢复；连接缺口或慢消费停止预览，完整事件仍须经过执行提交与持久投影。

### 流结束和异常

Turn 结束不关闭 Session SSE。Session terminated/deleted 才形成相应终止事实。关闭浏览器、取消订阅或网络断开不取消执行。

握手前错误返回正常 HTTP JSON。HTTP 200 已提交后的运输错误不能再伪装成 HTTP 409/503。首批通过断连和重新补历史恢复，不发送自定义运输错误类型。后续独立 delivery 流可以定义无 ID 的控制帧；它不进入持久历史，也不表示 Session/Turn 失败。

运行失败使用已提交的 session.error 与相应 Turn 终态事实，不能把 Agent Service 连接错误记录成执行失败。后续 delivery 回放过大时返回/报告 event_replay_limit，要求先分页补齐；授权失效停止流，重连重新鉴权。

## Turn 最终结果和后续快照

### 独立结果读取

首批提供 `GET /v1/sessions/{s}/turns/{t}/output?limit=100&after=...`，使用当前 read 权限检查。调用方不必打开 SSE、选择输出 Header 或先取得 snapshot；durable-events-v1=false 时行为完全相同。

~~~json
{
  "type": "turn_output",
  "session_id": "S",
  "turn_id": "T1",
  "turn_status": "completed",
  "output_available": true,
  "output_event_ids": ["evt_answer_1"],
  "data": [{
    "id": "evt_answer_1",
    "type": "agent.message",
    "content": [{"type": "text", "text": "主要瓶颈是数据库全表扫描。"}],
    "processed_at": "2026-10-19T07:01:36Z"
  }],
  "next_cursor": null,
  "has_more": false
}
~~~

data 只包含已提交的最终输出消息，按最终输出顺序排列；output_event_ids 与当前页的 data 一一对应，ID 与历史/SSE 中同一公开事实一致。最终输出集合提交后不可追加或改写，分页 cursor 绑定 Session、Turn 和该集合，不是 SSE 恢复位置。limit 默认 100、范围 1..256；输出集合及公开投影须在 output_available=true 前可读。

最终输出尚未提交时返回 HTTP 200，output_available=false、data/output_event_ids 为空、next_cursor=null、has_more=false；这不表示 Turn 失败或完成。终态没有最终输出时同样返回这个形状，并通过 turn_status 表明终态；客户端此时停止等待最终输出。部分输出仍从已开放的事件历史读取。不存在或不可见的 Turn 返回 404。

终态输出判断须读取对应的已提交最终输出引用。若引用存在但公开输出投影暂不可读，/output 返回可重试的 503 service_unavailable，不能将投影延迟伪装成“终态无输出”；客户端保持原查询重试，不重新提交 Turn。

最终输出以真实 item.finalized 或等价已提交最终输出标记为准，不把任意 agent.message、tool.completed 或模型 span 结束当作整个请求完成。SDK final_output 在需要等待时轮询 Turn，终结后分页读取 /output；它不订阅 durable、不构造 snapshot，也不触发新执行。

### 后续一致性快照

一致性快照属于后续可靠观察能力，使用固定资源视图：

~~~json
{
  "snapshot_id": "SN1",
  "as_of": "ACUR_SNAPSHOT",
  "expires_at": "2026-10-19T07:12:00Z",
  "resources": {
    "turns": {"data": [], "next_cursor": null, "has_more": false},
    "inputs": {"data": [], "next_cursor": null, "has_more": false},
    "items": {"data": [], "next_cursor": null, "has_more": false},
    "tools": {"data": [], "next_cursor": null, "has_more": false},
    "required_actions": {"data": [], "next_cursor": null, "has_more": false}
  },
  "usage": {}
}
~~~

示例仅列出主要集合。每个集合允许分页；后续资源请求必须携带同一 snapshot_id 和该集合 next_cursor，资源项统一使用目标 Event/Turn/Input 视图。

快照能力开启后要求 durable-events-v1；该要求仅约束快照与 delivery 的一致恢复，不约束 Turn 结果读取。快照冻结资源和 as_of；在全部必要资源页恢复后从 as_of 订阅独立 delivery 流，不能只加载第一页就宣称页面已完整恢复。

快照过期返回 410 snapshot_expired，重新获取快照。资源页 cursor、output cursor、Claude Event page、delivery cursor 和 checkpoint cursor 互不通用。后续快照不可用或过期不影响首批 /output 接口。

## 幂等和持久受理

### 请求键

| 项目 | 规则 |
| --- | --- |
| 键长度 | 非空 1..256 字符 |
| 标准兼容请求 | 可不带键；不承诺自动识别跨请求重试 |
| 包含 managed_agents 交互意图的请求 | 必须带键，缺失返回 400 idempotency_key_required |
| 新协议创建和生命周期操作 | 接受稳定键；平台 SDK 默认生成并在同一意图重试时复用 |
| 作用域 | 平台 principal、Tenant、Namespace、Session（创建时为创建作用域）和逻辑操作 |
| 内容校验 | 同键同规范请求返回原受理结果；异内容返回 409 idempotency_conflict |
| 保留期 | 活跃 Session 的成功受理键持续有效；删除后仅保留去重所需 tombstone 至少 30 天，不保留输入正文或完整回执；创建键至少 30 天，关联存活 Session 时继续有效 |

不会为了无键请求的每一次重试生成不同键后声称“幂等”。服务端可为一次无键 HTTP 尝试分配内部 request identity，但跨尝试语义仍是新请求。客户端在响应不明时若没有原键，应先检查历史/Turn，不自动再次提交副作用工作。

请求摘要保留事件数组顺序、content 顺序、文本原值、目标、意图和工具答复字段；仅对 JSON 对象键序等无语义差异规范化。不裁剪用户文本、不做 Unicode 或数值的有损归一化。影响业务含义的输入协议语义版本参与摘要。

输出 Header、输出扩展集合和 request-id 不参与业务摘要。同键同业务请求变更合法输出选择仍命中原操作，不返回 idempotency_representation_conflict。服务端保存统一受理事实及冻结回执，按本次合法输出选择呈现；相同输出选择得到相同业务字段，不承诺 JSON 属性顺序等字节形式。消息体自带的显式 intent 回执始终返回，Header 不能将它关闭。

### 处理顺序

所有带键写请求使用同一受理过程；Controller 不得先做易变状态的准入检查，再补做幂等：

1. 校验当前凭据、principal 绑定、scope 和资源可见性。已删除 Session 的普通输入按下述删除例外返回 404。必要的当前授权检查仍须通过，不能凭请求键越权读取原回执。
2. 校验不可变请求格式和已支持的协议版本，规范化业务请求并计算摘要。输出选择只参与响应呈现校验，不进入摘要。此时不检查 Turn 是否终结、inputs_open、Action 是否已答复、Session 是否已归档或剩余额度。
3. 在受理事务内按请求键串行化，再查询持久记录：同键异摘要返回 409 idempotency_conflict；同键同摘要返回原冻结受理事实的视图，不重新解析“当前轮”，不占额度、不再次发布。同键竞争者先等待原事务提交或回滚，超时返回 request_in_progress；不能无锁查空后先按目标新状态拒绝，漏掉刚提交的原结果。
4. 仅未命中的新操作解析和固定目标，校验当前操作权限、生命周期、Turn/Action 状态和额度。新扩展批次有一项失败则整体拒绝。
5. 在同一受理事务内保持请求键和目标状态的必要锁，唯一地提交 Event、Turn/Input、额度、目标集合、冻结回执和 Outbox。唯一约束竞争的失败方回到请求键查询并读取胜出方结果；不能再受理第二份操作。新操作被拒绝时回滚，不留下成功键或额度占用。
6. 事务提交后由冻结快照生成 HTTP 响应；异步执行及状态查询独立推进。发布或网络失败只能恢复原操作，不回到步骤 4 创建新记录。

例如 cancel 已受理但响应丢失，Turn 随后 cancelled；原键重试返回原 accepted 回执，新键才返回 turn_terminal。steer 的目标已关闭或工具待办已答复也遵循相同优先级。Session 后来归档时，在当前身份仍有回执可见性的前提下重放原回执；归档状态只阻止新操作。

### 重试结果

新协议成功受理时，同事务保存 HTTP 状态、原始 data 顺序、Event/Turn/Input ID、受理时 processed_at 和回执状态；不在提交后重新读取状态拼装首次响应。同键同内容且输出选择相同时，首次响应和重试得到相同 HTTP 200 业务主体，即使后台已推进。变更合法输出选择只改变可选表示，不刷新冻结状态；request-id Header 属于本次尝试。当前状态通过 REST 查询。

提交前的参数、权限、限流拒绝不占用成功键；相同合法请求在条件恢复后可以受理。已经提交但响应丢失、发布暂时失败或 Agent Service 重启时，原键必须找回原受理记录，不能创建第二份 Turn/Input。

并发同键请求由持久唯一约束和事务协调：一个成功，其他读取同一原结果；协调超时可返回可重试的 request_in_progress，但不能让两个请求同时越过受理门。事务结果不明时返回可重试错误并要求保留原键，不能把“未观察到结果”当作未提交。

所有重试仍需当前凭据认证与作用域可见性校验；幂等键不是访问令牌。已删除 Session 的普通输入或关联创建键重试返回 404，不复活 Session；删除命令自身在授权允许时可重放仅含删除身份与结果的回执。过期键 tombstone 到期后的保证边界必须写入发布文档，不能承诺永久识别。

### 一个批次的持久化

扩展批次需要把以下内容作为一个受理事务提交：请求键及摘要、每项 Event ID、目标解析结果、Turn/Input、额度占用、冻结回执和待投递记录。批内索引作为内部子操作身份的一部分。恢复时从这些记录继续，不能再次运行对客户端请求的“选择当前 Turn”逻辑。

事务提交前不得启动模型或物理工具。提交后发布失败由 Outbox 和补偿投递恢复。执行器的 Inbox、checkpoint 和事件身份继续承担去重；HTTP 幂等并不等于对任意外部非幂等工具承诺物理 exactly once。结果未知的工具沿用现有核对规则，不自动重跑。

## 错误和授权

错误基础信封保留 type/error/request_id：

~~~json
{
  "type": "error",
  "request_id": "req_example",
  "error": {
    "type": "conflict_error",
    "message": "The target turn no longer accepts input.",
    "code": "input_closed",
    "details": {"event_index": 0, "turn_id": "T1"}
  }
}
~~~

code/details 是扩展请求或已选择扩展输出时提供的稳定信息；严格兼容错误按冻结的 Claude schema 返回，不依赖 SDK 接受未知字段。message 可变化，客户端只分支判断 status/type/code。

| HTTP | 扩展 code | 语义及操作 |
| --- | --- | --- |
| 400 | invalid_event / invalid_extension | schema、组合或版本非法；修正请求 |
| 400 | idempotency_key_required | 扩展缺少稳定键 |
| 400 | unsupported_capability | 当前协议能力/Session 不支持该能力；不静默降级 |
| 400 | invalid_cursor / incompatible_query | 游标格式、scope、投影版本或查询组合不一致 |
| 401 | authentication_required | 凭据失效或缺失 |
| 403 | permission_denied | 已知可见资源但不具备该操作权限 |
| 404 | session_not_found / turn_not_found / action_not_found | 资源不存在或按既有策略不可见 |
| 409 | input_closed / turn_terminal | 目标不可再接受此操作；不能自动改为 new_turn |
| 409 | idempotency_conflict | 同键内容不同 |
| 409 | action_reply_pending / action_already_resolved | 待办已有人答复或已结清 |
| 409 | session_inactive / ambiguous_batch | 生命周期或批次语义冲突 |
| 409 | request_in_progress | 同键正在协调；使用原请求稍后重试 |
| 410 | snapshot_expired / event_cursor_expired | 重新获取快照，不能将过期 cursor 当成日志起点 |
| 413 | request_too_large | 超出公布大小限制 |
| 429 | admission_limited | 队列、额度或请求频率限制，准入未成功 |
| 503 | service_unavailable | 无法确认服务状态；按原键重试，不假定执行失败 |

标准请求遇到 pending action、预算或生命周期限制时按已验证 Claude 基线返回错误，不能把所有冲突都强制改成 409。运输失败与业务拒绝分别处理。Retry-After 只在平台能提供可用重试提示时设置，不把一切 409 视为瞬时错误。

| 操作 | 权限 |
| --- | --- |
| 创建独立 Turn、steer/inject | invoke |
| 读取 Session/Turn/Input/output/事件及后续快照 | read |
| 取消或中断 | cancel，不以 invoke 仍有效为前提 |
| 工具确认 | approve |
| 外部工具结果 | interact |
| 修改元信息及生命周期 | 既有 interact；涉及停止活动执行时还需 cancel |

混合批次逐项授权，拥有 invoke 不自动拥有 approve/cancel。原始调用者身份与目标 scope 随受理记录保存；后台投递不以内部凭据替换调用者。权限撤销、目标过期等后续失败必须有持久拒绝/处置记录；临时授权源不可用继续恢复，不捏造永久拒绝。

SSE 定期复核 read，REST 每次重新授权。Body 中的 metadata、managed_agents 和任何 Event ID 都不能改变租户、Namespace 或原始调用者。

## 能力发现和 SDK

capabilities 应同时回答协议支持和当前动作可用性，不能只返回一个 managed=true：

~~~json
{
  "session_id": "S",
  "protocol": "claude-managed-sessions",
  "protocol_revision": "managed-agents-2026-10-19-draft3",
  "compatibility": {
    "baseline": "managed-agents-2026-04-01",
    "anthropic_version": "2023-06-01",
    "status": "candidate",
    "verified_sdk_versions": []
  },
  "extensions": {
    "turn-inputs-v1": true,
    "durable-events-v1": false
  },
  "supported_input_events": [
    "user.message",
    "user.interrupt"
  ],
  "supported_intents": ["new_turn", "steer", "inject", "cancel"],
  "content_types": ["text"],
  "limits": {"max_events": 50, "max_request_bytes": 1048576},
  "features": {
    "turn_output": true,
    "snapshots": false,
    "plain_message_while_running": false,
    "multiple_plain_messages": false,
    "tool_confirmation": false,
    "custom_tool_results": false,
    "session_list": false,
    "session_update": false,
    "session_lifecycle": false,
    "event_deltas": true,
    "system_message": false,
    "outcomes": false,
    "threads": false,
    "files": false,
    "explicit_resume": false
  }
}
~~~

这是首批目标协议的示例，不是当前部署响应。设计期 compatibility 为 candidate；只有测试通过后才能公布 verified_subset、精确字段范围和 SDK 版本。某 capability=false 时请求相应能力必须失败，不能返回成功的空结果。plain_message_while_running 仅控制没有先中断的直接追加；multiple_plain_messages 仅控制多条普通消息，二者均不排除已声明支持的 interrupt 加单条 message。capabilities 不增加历史接口迁移或回填状态。

Turn 的可用操作继续结合 inputs_open 和实际状态判断；查询后与提交之间仍有竞争，服务端准入是最终判定。文件/模型能力来自实际配置，不能把测试环境的 files=false 固化到产品 schema。

平台 SDK 首批提供 send_events、submit_turn、steer、inject、cancel_turn、get_turn、get_input、get_output/final_output。交互封装最终发送 events，查询返回对应资源。confirm_tool、return_tool_result 与可靠 observe 在相应 capability 上线后增加；基础事件订阅只承诺普通 SSE 和历史补齐。

SDK 每个业务意图生成一次键，在超时及重试中保持原业务请求体和键；不能在每次 HTTP 尝试生成 UUID。合法输出选择可变，不能据此换键重新提交。后续可靠 observe 只有在调用方确认应用后才推进恢复位置；订阅失败不能合成为服务端 Turn failed。

## 开发期接口统一与实施

### 接口和调用方统一

Service 对外 API 直接采用本文定义的目标协议，交互统一写入 /v1/sessions/{s}/events，Turn/Input/output 使用扩展 REST 查询。现有 Controller、DTO、SDK、前端和测试手册按目标协议一起调整，不维护历史请求体别名、平行写入口、双事件 decoder 或跨协议幂等域。

首批覆盖 Managed 执行。其他后端的功能交付范围另行确定，其当前路径不构成兼容约束；不能仅为维持历史 API 而增加另一套 Managed 公开协议。内部继续复用合适的执行机制，不要求按现有 HTTP DTO 划分执行模块。

### 执行状态和开发数据

历史开发 API 的 ID 派生、cursor、错误和序列化格式不构成对外稳定性承诺。开发环境数据是否保留、一次性转换或重建，由实际升级需要决定；首批不建设在线历史回填、Session 迁移准入状态机或双版本数据投影，也不以无损迁移全部测试会话作为验收条件。任何实际数据处理在实现升级时明确安排，本方案不触发清理。

已持久受理工作的运行正确性独立保留。升级若需继续这些工作，应保留其账本、操作身份和恢复所需状态；也可以安排暂停受理、等待活跃工作结清后更新。接口调整不能把同一已受理工作重新当作新请求投递，更不能因此重复外部工具副作用。这里要求的是执行可靠性，不是继续运行历史 HTTP 接口。

平台 SDK、前端与服务端以同一目标协议版本交付；本协议的幂等规则约束该版本内的业务操作及其重试，不额外承诺不同开发版本的键格式互认。需要跨升级保留的工作按上述运行安排处理，不增加客户端双入口重试流程。

### 实施顺序

| 阶段 | 工作 | 完成条件 |
| --- | --- | --- |
| 协议冻结 | 确认统一路径、意图、状态、幂等与 Claude 子集；固定 schema 和 SDK 基线 | 本文首批契约一致，待验证项有明确范围 |
| 统一实现 | 调整 Agent Service/Gateway、受理与投影，平台 SDK、前端及测试同步更新 | 所有调用方使用目标请求响应，无历史协议分支 |
| 首批验收 | 文本交互、中断边界、Turn/Input/output、历史和普通 SSE | Claude 子集、幂等、授权、排队、边界应用及结果读取验收通过 |
| 扩大兼容范围 | 工具答复、Session 列表/更新/生命周期、image 等逐项上线 | 各自状态、权限及 schema 验收通过，不反向成为首批门槛 |
| 可靠观察 | 独立 delivery、snapshot、恢复位置及可选 preview | 单独冻结协议并通过恢复验收，不改变 Claude 历史接口 |

## 组件职责和实现约束

| 组件 | 本方案要求 |
| --- | --- |
| Gateway | 统一 /v1/sessions 路由、公共 Header 保留及内部 Header 清理；不解析消息决定 Turn 归属 |
| Agent Service | schema 校验、原始身份授权、扩展归一、原子受理、幂等响应、查询与公开投影 |
| Agent Harness Runtime | 消费原有 Turn/Input、边界应用、原生提交和自动恢复；不理解公共 HTTP Header |
| Tool Worker | 真实工具结果及事件 Outbox；Runtime 汇合结果并可靠发布 Resume，不因公开协议变更重复工具 |
| Control Plane | 资源和作用域授权、管理接口；不作为事件字段适配后的第二个执行器 |

适配器归一到 new_turn、steer、inject、reply、cancel 等现有执行语义。需要补充的请求回执、公开 ID 关联和投影索引属于协议持久化，不是重写分布式执行协议。

首批源码缺口包括批次原子受理、幂等顺序及冻结回执、跨投影稳定身份、Claude 历史分页、标准中断的 idle 边界，以及独立 Turn 输出查询。metadata 合并、工具答复、线程 interrupt、生命周期差异和可靠观察分别随后续能力实现，不混入首批发布门槛。

保留运行时边界、取消竞争、授权复核和未知工具结果保护。任何“立即响应”“兼容中断”要求都不能绕过终态前的 Input/工具结清检查。队列、投影、SSE 连接和回放均保持有界；超限必须是可观察的拒绝或可恢复断连。

## 验收标准

以下是实现后的验收计划，不代表本次已运行。“首批”是首批发布门槛；“后续”随相应能力验收，不能反向阻塞首批。既检查客户端解析，也检查真实状态和执行事实。

| 编号 | 阶段 | 场景 | 必须满足 |
| --- | --- | --- | --- |
| C01 | 首批 | 固定版本官方 Python/TypeScript/Java SDK 创建和读取 Session | 声明字段可解析，必填 schema 完整，执行身份与真实配置映射明确 |
| C02 | 首批 | 空闲普通消息及未开启的活跃追加/多消息组合 | 已支持场景符合冻结基线；未支持组合明确拒绝，不隐式变为 steer 或逐条 new_turn |
| C03 | 首批 | Event 分页、过滤和相同时间戳 | 完整读遍，无重复漏项；开启 turn-inputs 输出不丢 pending 标准事件、不改变标准过滤 |
| C04 | 首批 | interrupt 加后续 message，下一轮已排队 | user.interrupt → idle/end_turn → 下一轮 running；模型响应可停止，工具真实结清前不抢跑 |
| C05 | 首批 | 不支持的工具答复、预算及生命周期组合 | 明确 unsupported；首批 Agent 配置不能要求未开放的人工答复 |
| C06 | 首批 | 标准模式输出 | 无额外未知事件类型，符合固定客户端解析规则 |
| C07 | 后续 | 相同或冲突工具结果以新键重复答复 | 保留基线无效果成功规则，不覆盖有效结果或重复恢复推理 |
| T01 | 首批 | T1 运行中提交显式 T2 | 返回独立 T2，T1 结束前 T2 不执行 |
| T02 | 首批 | T1 waiting_tool 90 秒，T2 已受理 | 工具真实完成后自动恢复同一个 T1，T2 继续等待 |
| T03 | 首批 | T1 工具期间收到 steer/inject | 下一相关模型请求前应用，不等到 T2，不产生新 Turn；两种标签共用应用机制 |
| T04 | 首批 | 并行工具和多条 Input | 必需结果齐备后按 Input sequence 应用，不追溯修改已发调用 |
| T05 | 首批 | Input 受理与关闭同时发生 | 明确拒绝或持久应用/拒绝，没有丢失的 accepted |
| T06 | 后续 | waiting 期间 inject 及分批工具答复 | inject 不替代 reply，答复齐备后仍恢复 T1 |
| T07 | 首批 | 对指定 T1 取消，已有 T2 | 不误取消 T2，真实结清前不提前写 cancelled |
| T08 | 首批 | cancel 与 completed 竞争 | 原终态不回退，回执说明真实处置 |
| R01 | 首批 | durable=false、未打开 SSE、无 snapshot | Turn 成功后可独立读取全部最终输出及正确 Event ID |
| R02 | 首批 | 输出未提交、失败无输出、结果多页 | 空结果不冒充完成；终态可停止等待；分页集合稳定，不夹入部分输出 |
| I01 | 首批 | HTTP 超时、提交后断连和 Agent Service 重启 | 原键找回原操作及冻结回执，无第二份执行 |
| I02 | 首批 | 同键异内容或目标、并发同键且原操作快速终结 | 单一受理或明确冲突；竞争方先协调请求键，不按目标终态误拒绝，不重复占用额度 |
| I03 | 首批 | 新批次一项格式/权限/额度失败 | 整批未受理，无部分工具执行 |
| I04 | 首批 | 批次受理后发布/执行失败 | 已受理记录保留并恢复，不回滚已发生的外部副作用 |
| I05 | 首批 | 原键重试时 Turn 已终结、输入关闭或额度耗尽 | 当前身份仍合法时先命中原记录，不误报新操作的准入错误 |
| I06 | 首批 | 首次响应前执行已推进、重试时变更输出 Header | 回执状态仍为事务内冻结值；合法表示变更命中原操作，不产生表示冲突 |
| I07 | 后续 | 归档或删除后的原键重试 | 归档仍按当前可见性重放；删除普通输入 404，删除命令可按授权重放 |
| E01 | 首批 | user 输入受理再应用、item 完成再 finalized | 不重复完整消息，Event ID 稳定 |
| E02 | 首批 | 同一已提交事实重复投递或再次投影 | 公开 Event ID 稳定，只生成一份投影，不重新执行工作 |
| S01 | 后续 | 独立 delivery 跨 Agent Service 断线恢复 | 相同位置恢复正确，应用后才推进水位，不改 Claude GET /events |
| S02 | 首批 | 普通 SSE 开流与历史补齐竞争 | 无订阅空窗，重复可通过 Event ID 收敛 |
| S03 | 后续 | snapshot 多页及过期 | 固定上界恢复完整，过期可重建；不影响独立 /output |
| S04 | 后续 | preview 与完整消息、模型中断 | 预览不入历史、不推进 cursor，完整事件或 span end 收敛 |
| S05 | 首批 | SSE 授权撤销和慢客户端 | 有界资源，断连不等于 Turn failed，原工作可继续 |
| A01 | 首批 | invoke/cancel 权限及跨 scope ID | 不越权，内部凭据不替代原 actor；approve/interact 随工具能力补验 |
| U01 | 首批 | 统一版本的 Java/TS/Python SDK、前端及测试手册 | 全部调用目标路径、请求响应及事件；不以历史 Service API 回归作为兼容门槛 |

官方 SDK 测试必须记录精确版本和可复现命令；平台源码测试使用本地 fake/fixture，不用真实凭据。已知基线差异需形成兼容矩阵中的明确限制，不通过放宽断言将差异隐藏。

## 首批发布边界

首批按已验证子集发布，边界固定如下：

1. 单主 Agent Session、文本交互、普通中断的 idle 边界、显式意图、Turn/Input 查询及独立 /output 共同形成最小闭环。
2. 未验证的普通消息并发/批次组合不开放，不以候选调度冒充兼容；显式 new_turn 的独立排队语义固定。扩展批次原子受理与标准批次基线分别验收。
3. 幂等先查原记录再校验新操作的易变准入，回执在事务内冻结；合法输出选择不改变业务身份。
4. durable、snapshot、preview、工具答复及 Session 管理扩展不是首批前置。后续能力逐项开放，不提供任意 priority、抢占长工具或隐式 replace_all。
5. Managed Agents 自身 API 可直接重构；服务端、SDK、前端和文档统一更新，不设置历史 API 共存期、客户端迁移窗口或开发数据全量回填门槛。

发布前仍须冻结实际 Agent/执行身份映射、schema 快照和 SDK 精确版本，并通过所有首批验收项；这些验证决定对外可声明的精确兼容范围。

## 当前源码依据

以下定位对应本方案日期的工作区，包含未提交修改，用于识别可复用的运行机制和改造点。当前 HTTP 路由、DTO 和事件形状是实现现状，不构成必须保留的对外合约。

| 依据 | 源码位置 |
| --- | --- |
| 原生公开路由、Turn/Input 写入及观察接口 | [SessionApiController.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/api/SessionApiController.java) |
| 持久 Turn 受理与发布失败补偿 | [ManagedSessionTurnsApi.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/session/ManagedSessionTurnsApi.java) |
| Input 类型、审批校验和权限映射 | [ManagedSessionTurnsApi.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/session/ManagedSessionTurnsApi.java) |
| 当前事件信封及 cursor | [ManagedSessionTurnsApi.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/session/ManagedSessionTurnsApi.java) |
| 当前原生 Session 状态简化投影 | [ManagedSessionTurnsApi.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/session/ManagedSessionTurnsApi.java) |
| 当前 metadata 替换与生命周期 | [SessionApi.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/session/SessionApi.java)、[SessionApi.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/session/SessionApi.java) |
| Input 幂等、输入关闭及真实待办校验 | [ManagedAdmissionStore.java](../common/src/main/java/io/agentcore/managed/server/runtime/managed/ManagedAdmissionStore.java) |
| admission_sequence 与执行顺序的区别 | [ManagedAdmissionStore.java](../common/src/main/java/io/agentcore/managed/server/runtime/managed/ManagedAdmissionStore.java) |
| 现有 steer/inject 同在推理边界追加消息 | [ManagedRuntimeInputMiddleware.java](../agent-harness-runtime/java/adapter-agentscope/src/main/java/io/agentcore/managed/server/runtime/managed/ManagedRuntimeInputMiddleware.java) |
| 原生事件到公开事实，含 item.finalized | [CommittedSessionEventProjector.java](../agent-harness-runtime/java/adapter-agentscope/src/main/java/io/agentcore/managed/server/web/managed/CommittedSessionEventProjector.java) |
| 当前输入 message/input 二选一及 user role | [AgentSessionInput.java](../common/src/main/java/io/agentcore/managed/server/web/managed/AgentSessionInput.java) |
| 当前错误信封与状态映射 | [SessionApiErrors.java](../agent-service/src/main/java/io/agentcore/managed/server/agentservice/config/SessionApiErrors.java) |
| Gateway 向 Agent Service 路由 | [application.yml](../gateway/src/main/resources/application.yml) |
| 当前手册中最终输出过滤与投影的差异 | [managed-api-cluster-walkthrough.md:134](managed-api-cluster-walkthrough.md) |
| MCP 长工具真实挂起和同 Turn 自动恢复验收记录 | [managed-mcp-long-tool-cluster-walkthrough.md:155](managed-mcp-long-tool-cluster-walkthrough.md) |
| 原生 Java/TypeScript/Python 客户端 | [ManagedSessionClient.java](../java-client/src/main/java/io/agentcore/managed/client/ManagedSessionClient.java)、[managed-session-client.ts](../control-plane/sdk/dsh/src/managed-session-client.ts)、[managed.py](../control-plane/sdk/python/managed_agents/managed.py) |

当前手册中的历史最终输出筛选使用 item.completed 加 final_output，而当前投影在 item.finalized 标记 final_output；本方案记录该差异，不在此次改动中修正文档或运行代码。

## Claude 官方依据

| 官方页面 | 用于核实的范围 |
| --- | --- |
| [CLI Send Events](https://platform.claude.com/docs/en/api/cli/beta/sessions/events/send) | 用户最初给出的入口；CLI 命令对应 HTTP Sessions API，不是 Messages API |
| [HTTP Send Events](https://platform.claude.com/docs/en/api/beta/sessions/events/send) | events 数组、输入类型、工具事件 ID 和请求头 |
| [Create Session](https://platform.claude.com/docs/en/api/beta/sessions/create) | Session 创建参数及响应 schema |
| [Get Session](https://platform.claude.com/docs/en/api/beta/sessions/retrieve) | Session/Agent 快照、usage/stats 和状态字段 |
| [List Sessions](https://platform.claude.com/docs/en/api/beta/sessions/list) | page、排序和过滤，next_page/prev_page |
| [List Events](https://platform.claude.com/docs/en/api/beta/sessions/events/list) | 事件平铺结构、page、types 和时间条件 |
| [Stream Events](https://platform.claude.com/docs/en/api/beta/sessions/events/stream) | SSE、event_deltas 选择器及事件联合类型 |
| [Session event stream](https://platform.claude.com/docs/en/managed-agents/events-and-streaming) | 事件等待处理、processed_at、先开流再补历史、中断和待办 |
| [Event deltas](https://platform.claude.com/docs/en/managed-agents/event-deltas) | 临时预览、完整事件收敛、非持久化和 thinking start-only |
| [Session operations](https://platform.claude.com/docs/en/managed-agents/session-operations) | 更新、归档及删除的状态条件 |
| [Session threads](https://platform.claude.com/docs/en/managed-agents/session-threads) | 线程作用域、中断和工具答复路由 |
| [Workflow runs](https://platform.claude.com/docs/en/managed-agents/workflow-runs) | Run 不等于 Turn，interrupt 不结束所有后台 Run |
| [API errors](https://platform.claude.com/docs/en/api/errors) | 基础错误信封及 request-id |

官方事实仅用于 Claude 兼容范围；managed_agents 字段、独立查询、批次原子受理、可靠续传、幂等保留期与开发期实施步骤均为本平台设计。未通过上述验收前，不将该方案或示例视为已经上线的 API。

## 本项目首批实施记录

- 自有字段为 `managed_agents`，通知为 `managed_agents.*`，输出选择为 `Managed-Agents-Extensions: turn-inputs-v1`。
- 固定 SDK：Python anthropic 1.15.0、TypeScript @anthropic-ai/sdk 0.135.0、Java com.anthropic:anthropic-java 2.73.0。完整验收记录见 `../tests/session-protocol/verification.md`；版本固定本身不构成兼容保证。
- 接受官方 SDK 自动附带的 `beta=true` 传输查询参数，它不参与历史过滤或业务幂等。
- 批次请求、输入关联、版本化公开投影和输出集合使用新增协议表；现有执行表、MQ 及二进制契约保留。
- 控制台首批以创建、按 ID 打开、历史与实时消息、Turn/Input 和结果为闭环。列表、生命周期、文件、工具答复和可靠观察按 capability 关闭。
- 首批实施验证保留在 `../tests/session-protocol/verification.md`；本轮全局复查、验证和独立升级见 `../tests/session-protocol/draft3-audit.md`。升级保留现有记录，不回填旧版本公开历史。
- 公共 Agent 投影和事件受理复用带 scope 校验的加密固定配置读取，不降级为空白配置；MCP Header 等私有认证不进入公共响应。
- Agent 工具配置输出完整的 `configs`、`default_config` 和实际 enabled/permission_policy；原生工具默认 always_allow，MCP 未配置时默认 always_ask，需要客户端确认的有效配置明确拒绝。未验证映射的 Skills、工具类型和非 URL MCP 配置不开放。
- 三套 SDK 的 Turn/Input 过滤统一使用 `input_event_id`、`status`、`event_id`；final_output 只读查询，503 重试保持原查询，覆盖分页的等待期限，并拒绝循环或不完整分页。终态无输出返回空集合。
- 控制台独立轮询所选非终态 Turn，观察中断和结果投影暂时不可用时继续查询；cancel 的不确定响应重用原幂等键。
