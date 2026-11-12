# Agent 交互协议：Managed Agents 文本子集 v1



English: [English contract](agent-protocol.md)。

## 1. 格式基线

遵循 Claude **Managed Agents** 事件格式，不是 Messages API 的流式格式。
不创建 `turn.finished`、`tool.completed` 等同义事件，不添加统一的 `data` 外壳。
beta 标识为 `managed-agents-2026-04-01`，本地 profile 为 `managed-agents-text-v1`。
profile、session ID、case ID、trial/attempt、配置与源码摘要放在 `binding.json` 等评测侧文件中，
不添加到官方事件对象。事件由所属 session/thread 流关联，不能假定每个事件都带 session ID。

精确字段参考固定 Anthropic Python SDK 提交和文件 blob SHA，记录于
[`schemas/upstream.json`](../schemas/upstream.json)。这是明确的兼容子集，不宣称完整 SDK/API 兼容。
Schema 使用 JSON Schema 2020-12，入口为 [`managed-agents.schema.json`](../schemas/managed-agents.schema.json)。

文档中的状态概述并不穷尽最新 SDK：本次固定 SDK 还包括 `retries_exhausted` 和 `refusal`
停止原因。Schema 保留这些已验证类型，不能把它们归为正常成功。

## 2. 初始化与输入

初始化配置与发送事件分开。初始化 manifest 记录 Agent 实现/版本/镜像、原生 profile、模型与
credential reference、工作区 fixture 摘要、业务工具/网络权限、能力要求、时间及轮次限制。
连接凭据只由受信执行器解析。不给 Agent 传答案、隐藏 verifier、评分 rubric 或数据选择依据。
`describe`/能力回执属于适配层配置，不伪装成 Anthropic 标准事件。

发送请求：

```json
{"events":[{"type":"user.message","content":[{"type":"text","text":"只修复空输入，保留函数签名。"}]}]}
```

发送参数没有服务端 `id` 或 `processed_at`。本 profile 支持：

| 输入 | 字段 |
| --- | --- |
| `user.message` | `content`（仅 text） |
| `user.interrupt` | 可选 `session_thread_id` |
| `user.custom_tool_result` | `custom_tool_use_id`，可选 `content`、`is_error` |
| `user.tool_confirmation` | `tool_use_id`、`result=allow/deny`；拒绝时可有 `deny_message` |

`system.message`、outcome、self-hosted `user.tool_result`、图片/文档/搜索结果块及 workflow
在首版不支持，必须明确拒绝，不能降级成普通用户文本。后续按原生能力和官方类型扩展。
一轮只发送新增内容，历史由原生会话维护；不能用重新启动并重放全部历史冒充会话恢复。

## 3. 正式事件

正式事件直接使用官方顶层字段。示例：

```json
{"id":"sevt_answer","type":"agent.message","processed_at":"2026-11-07T12:00:00Z","content":[{"type":"text","text":"已修改。"}]}
{"id":"sevt_idle","type":"session.status_idle","processed_at":"2026-11-07T12:00:01Z","stop_reason":{"type":"end_turn"}}
```

输入事件的历史回显也有 `id`；`processed_at` 可省略或为 null，处理完成后更新。
模型、Agent 和 session 事件必须有 `id`、RFC3339 `processed_at` 和准确 `type`。
本地 profile 不增加自定义时间字段，也不把客户端收包时间当作原生处理时间。

| 事件组 | 首版范围 |
| --- | --- |
| 消息 | `agent.message`；text/redacted 输出；user text 回显 |
| 状态 | `session.status_running/idle/rescheduled/terminated` |
| 工具 | `agent.tool_use/tool_result`、`agent.mcp_tool_use/mcp_tool_result`、`agent.custom_tool_use` |
| 用户动作回显 | message、interrupt、custom_tool_result、tool_confirmation |
| 诊断 | `agent.thinking`（无正文）、`agent.thread_context_compacted` |
| 模型 span | `span.model_request_start/end`，结束引用 `model_request_start_id` |
| 错误 | `session.error`，首版仅 `unknown_error`，原生详情单独归档 |

工具权限支持 `evaluated_permission=allow/ask/deny/null`；细分 `evaluation` 对象暂不在 profile。
MCP 调用包含 `mcp_server_name`；结果使用 `mcp_tool_use_id`，内置工具使用 `tool_use_id`，
自定义工具使用 `custom_tool_use_id`。这些关联字段不能混用，也不能把内部已经执行的工具
改为外部执行并重复产生业务副作用。

首版限制为逐线程、顺序模型请求；跨线程事件合并和 workflow 生命周期待后续实现。
无法观测原生模型 span/压缩/思考进度时不生成相应事件，并在 binding 中说明观测缺口。
`agent.thinking` 不承载推理内容。对于未知官方类型/字段，本 profile 校验器显式报 unsupported/profile
错误；不要静默丢弃，后续升级版本后再接入。

## 4. 状态、工具暂停与中断

- `end_turn`：本轮结束，包括中断后的结束，不等于题目成功。
- `requires_action`：`event_ids` 列出等待结果或确认的调用。多个调用分别响应。
- `budget_reached`：货币预算相关暂停，不用于时间或轮次数超限。
- `retries_exhausted` / `refusal`：保留原因，不作为正常成功。

自定义工具结果可以早于 `requires_action` idle 回显发送；已解决的调用不能重新挂起。
相同调用的第二份结果不再执行副作用。本 profile 仅被动记录/关联，不负责实际执行工具。
所有等待项解决后，由实际运行组件发出后续 running/idle，观察器不自行启动 Agent。

`user.interrupt` 入队不代表已经停止；处理时间、后续状态与实际进程共同确认中断完成。
超时、取消、工作进程丢失、状态未知等执行结论写入 result，而不是造新的官方 stop reason。
`session.error.error.retry_status` 是带 `type` 的对象，可为 retrying/exhausted/terminal。
收到 error 并不一定意味着 session 已终止；以实际后续状态为准。评分始终由独立 verifier 完成。

## 5. 流式预览与重连

正式传输可为 SSE；离线归档采用每行一个完整事件的 JSONL。JSONL 按 LF 分帧，不能把字符串内
的 Unicode U+2028/U+2029 当换行。SSE 支持注释、多行 data、CRLF；断连时未结束的 SSE 帧丢弃。

```json
{"type":"event_start","event":{"type":"agent.message","id":"sevt_answer"}}
{"type":"event_delta","event_id":"sevt_answer","delta":{"type":"content_delta","index":0,"content":{"type":"text","text":"已"}}}
```

预览没有自己的 `id` 或 `processed_at`，只引用将要产生的正式事件。按 `(event_id,index)`
累积，完整 `agent.message` 替换预览，不能再次追加。预览可能只覆盖前缀；若请求中断，完整消息
可能永远不出现。模型 span 结束或断连后清理遗留预览。`agent.thinking` 只预告 start，不接收文本 delta。

先打开并缓冲流，再完整分页读取历史，再处理实时数据；按事件 ID 去重。相同 ID 的内容变化报错，
唯独输入事件 `processed_at` 允许从空变为时间戳；旧的 queued 回显不能覆盖新状态。
本 profile `EventProjection` 实现这种合并，不执行 HTTP 连接、分页、自动重连或请求幂等。
预览只在内存中，`events.jsonl` 中仅写正式事件。原始传输可另存 raw 便于诊断。

## 6. 用量、产物与评分边界

官方 `span.model_request_end.model_usage` 要求 input/output/cache creation/cache read 四个计数。
不知道缓存用量时，不能为了通过 Schema 填零；适配器对这类请求不生成标准模型 span 对，
用评测侧观测记录保存真实可得字段及来源，避免留下人为缺失结束事件的 span。
`session.usage` 含累计计费语义，本 profile 暂不实现，避免跨 provider 误映射。
后续不能把累计快照和逐请求用量重复相加。

case/trial/attempt ID、实际 Agent 版本、模型别名是否锁定、能力、原生到标准事件 ID 映射，
均位于 binding/manifest。文件 diff、资源指标、额外诊断在 artifacts/observations；正式分数和
评分异常在 score。报告明确区分 execution、scoring validity 与 reward，未知值为 null。

重复输入去重、工具副作用 exactly-once、持久化事件日志和崩溃恢复不由内存 projection 保证。
实际执行层需要单独的请求账本、稳定原生 ID 映射和执行状态验证，状态不明时不自动重试。

## 7. 可运行验收

从仓库根目录运行：

```sh
python3 -m pip install -r benchmarks/harness/requirements-protocol.txt
python3 -m unittest discover -s benchmarks/harness/tests -p 'test_*.py' -v
python3 benchmarks/harness/eval.py protocol-smoke --output benchmarks/harness/artifacts/protocol-smoke
python3 benchmarks/harness/eval.py protocol-check /path/to/events.jsonl
python3 benchmarks/harness/eval.py protocol-check /path/to/events.sse --format sse
python3 benchmarks/harness/eval.py request-check /path/to/send-request.json
```

推荐在 `benchmarks/harness/.venv` 中安装依赖。输出目录必须不存在，防止覆盖旧证据。
smoke 是本地 fixture 回放，不调用模型，不是 Agent 能力测试，也不验收执行后端。
测试覆盖 queued→processed、重放冲突、预览与完整消息、工具关联、提前返回结果、中断、
错误重试语义、非法字段、SSE 分帧与 Unicode；详细阶段状态见 implementation-status。

## 8. 上游依据

- [发送、流式、暂停与中断](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)
- [增量预览](https://platform.claude.com/docs/en/managed-agents/event-deltas)
- [事件目录](https://platform.claude.com/docs/en/managed-agents/reference)
- [固定 SDK 类型](https://github.com/anthropics/anthropic-sdk-python/tree/4c7b598466b88559583f98216139be8e9b34c1fc/src/anthropic/types/beta/sessions)

更改 profile 必须同时更新 Schema、EN/ZH 文档、来源记录和正反例测试。
