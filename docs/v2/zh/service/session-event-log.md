---
title: "会话、任务与预算"
description: "调整 Managed Turn、回答交互、取消任务，并读取持久结果与预算。"
en_link: /v2/en/service/session-event-log
---

本页面向源码 MQ / Redis Managed 运行时及其 `/v1/sessions` API。Session 固定 Agent 定义与资源绑定，每个 Turn 是一次提交的请求。Portal 受理，Brain 推理并持久提交原生执行状态，Hands 执行远端工具；关闭页面不会取消执行。其他执行后端保留各自合约，见[服务 API](/v2/zh/service/service-api)。

先通过 [Managed Session、Turn 与 Input API](/v2/zh/service/claude-session-api) 创建会话，设置 `SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"`，并保存返回的 `TURN_ID`。以下示例使用本地模式；生产环境使用[部署文档](/v2/zh/service/kubernetes#production-api-access)中的凭据。

挂起会释放活动执行与 MQ 回调，原 Turn 消息仍保留在有序队头；工具结果或答复持久就绪后，独立 Resume 继续同一个 Turn。后续 Turn 继续留在 MQ 中等待。

当前运行时使用全新的 Managed schema 基线，已移除旧 Hands 经 Portal 转发、工具结果回调与独立 workspace-task 协议，不提供旧运行状态转换或消费者组位点迁移。

## Session 与 Turn 的关系

创建 Session 直接保存固定配置，不等待 bootstrap 或 ready 状态。修改 Agent 不会更新已有 Session；Session 更新操作只修改标题和 metadata，不替换固定的定义与 Environment 绑定。

同一 Session 的 Turn 按 MQ 接收顺序执行；API 受理序号用于查询，不决定执行优先级。长任务挂起后仍保留原有序投递，直到完整终态持久提交并由 SDK ACK，后续 Turn 才能推进。不同 Session 可以独立执行。

提交响应不确定时，使用相同 `Idempotency-Key` 和请求体重试。读取已有 Turn 或重连 SSE 可以恢复观察，不需要重新提交任务。

## 读取结果与恢复页面

读取当前 Turn 与固定快照：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
SNAPSHOT=$(curl -sS --fail-with-body "$SESSION_URL/snapshot?limit=100")
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
SNAPSHOT_ID=$(jq -er '.snapshot_id' <<< "$SNAPSHOT")
```

`resources` 中每个集合共享 `snapshot_id`、`as_of` 和 `expires_at`。沿集合的 `next_cursor` 读取 `/resources/{kind}` 时保留同一个 `snapshot_id`，每页 1–256 条。快照有效期十分钟，每个 Session 最多 32 个活动快照；收到 `snapshot_expired` 后重新读取。

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Accept: text/event-stream" --data-urlencode "after=$CURSOR"
```

先恢复快照，再应用增量；事件成功应用后才保存游标。可通过另一个 Portal 副本，使用该游标或 `Last-Event-ID` 续传。事件游标和资源分页游标合约不同，不能混用。累积规则、工具进度与预览限制见 [SSE 与事件续传](/v2/zh/service/sse-events)。

`waiting_tool` 表示 Hands 仍在执行工具，平台将从检查点恢复同一 Turn，不需要用户填写虚构结果。`waiting` 且存在实际 required action 时才需要答复。只有 `completed`、`failed` 或 `cancelled` 能确认 Turn 已进入终态。

## 补充要求或上下文

新问题提交新的 Turn；修改仍开放的 Turn 或补充上下文时提交 Input。显示控制按钮前读取 Turn 的 `/capabilities` 与 `inputs_open`。

```bash
INPUT_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
    -H "Content-Type: application/json" -H "Idempotency-Key: correction-001" \
    --data-binary '{"type":"steer","payload":{"message":"继续之前先检查预算。"}}'
)
INPUT_ID=$(jq -er '.id' <<< "$INPUT_JSON")
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs/$INPUT_ID"
```

同一路径使用 `type=inject` 可以向该 Turn 补充上下文；两者都不创建新 Turn。steer 在安全的推理边界应用，不能改变已发送的模型请求。payload 使用 `message` 或结构化用户输入；[文件引用](/v2/zh/service/files)遵循 Session 不可变文件合约。

Input 的 `status=accepted` 表示持久受理，`delivery_status=delivered` 表示投递，`status=applied` 才表示输入与运行状态一起提交。拒绝后保留 `rejection_reason`。重试同一意图使用原键，改变内容则使用新键。

## 回答 required action

从快照的 `required_actions` 读取真实 `request_id`，将它作为 reply Input 的 `action_id`。确认请求需要 `allow` 与可选原因：

```bash
ACTION_ID="REQUEST_ID_FROM_REQUIRED_ACTION"
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" -H "Idempotency-Key: answer-001" \
  --data-binary @- <<JSON
{"type":"reply","action_id":"$ACTION_ID","payload":{"allow":true,"reason":"用户已确认"}}
JSON
```

若待办要求外部执行结果，则提交实际内容与错误状态：

```json
{"type":"reply","action_id":"ACTUAL_REQUEST_ID","payload":{"content":[{"type":"text","text":"真实执行结果"}],"is_error":false}}
```

外部工具失败时将 `is_error` 设为 `true`。恢复推理时保留工具错误状态，公开工具状态为 `failed`。Agent 可以根据该结果继续推理；工具失败本身不直接决定整个 Turn 失败。

这两种答复不能互换。读取返回的 Input，直到 applied 或 rejected，再继续观察同一个 Turn。受理答复不代表任务完成。等待输入时保存待办与检查点，然后释放活动 Harness 所有权。受理答复与提交挂起两侧都会检查已受理的 reply/cancel，因此两种到达顺序均可安排同一 Turn 恢复。运行时与事务专项已覆盖外部答复和取消，多副本完整验收另行记录。

## 取消与恢复任务

取消也是持久化 Input：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" -H "Idempotency-Key: cancel-001" \
  --data-binary '{"type":"cancel","payload":{}}'
```

受理后继续等待 Turn 的真实终态；已提交的完成可能先于取消生效。租约过期不能证明外部副作用已停止，也不能据此安全重跑。

长工具挂起由平台在 Hands 提交完整覆盖的结果后自动恢复。当前原生 capabilities 声明 `resume=false`，旧失败 Turn 的显式 `/resume` 和命令回执协议不属于这套合约。工具结果未知时，不要启动替代执行来尝试消除不确定性。

必需 MCP 服务尚在发现或初始化阶段时，也可取消已受理但尚未开始推理的 Turn，无需等待该服务恢复。

Hands 持久保存原始工具结果和输出覆盖，并可靠发布 Resume；Brain 提交工具事件 Inbox，校验当前挂起与所有 pending 结果后，才恢复 checkpoint。Resume 先于结果或挂起到达时继续等待，重复投递不能产生另一次物理调用。

<span id="budgets"></span>
<span id="选择限制层次"></span>
<span id="原生会话与子-agent-用量"></span>
<span id="发布服务的配额"></span>
<span id="验证预算生效"></span>

## 用量、子 Agent 与预算

`$SESSION_URL/usage` 读取 Session 共享用量，`$SESSION_URL/turns/$TURN_ID/usage` 读取该 Turn 用量。快照内的用量与资源对应其固定游标。子执行有独立身份，应通过 `resources.subagents` 与已支持的子读取观察，不能将子完成视为父完成。子树汇总反映已报告的用量，并非所有进程同一时刻的原子快照。

替换限制前先读取当前预算：

```bash
BUDGET=$(curl -sS --fail-with-body "$SESSION_URL/budget")
REVISION=$(jq -er '.revision' <<< "$BUDGET")
curl -sS --fail-with-body -X PUT "$SESSION_URL/budget" \
  -H "Content-Type: application/json" -H "If-Match: $REVISION" \
  --data-binary '{"max_model_calls":30,"max_total_tokens":100000}'
```

在根 Session 修改共享预算。版本过期返回 `409`，重新读取后再决定是否覆盖；PUT `{}` 清除限制。后续模型调用根据已报告用量检查，已经开始的并行调用可能超额，费用预算还要求模型计价。提高预算不会开放当前不支持的显式 resume。

## 从 checkpoint 继续试验

`GET $SESSION_URL/checkpoints?limit=20` 列出已提交检查点句柄，只返回元数据，不返回原生 Agent 状态。检查点分页游标与公开事件游标不同；翻页时保持列表水位。

当 capabilities 开放 `checkpoint_restore` 或 `fork` 时，以稳定幂等键提交操作，再通过源 Session 查询回执，直到 `completed`。Fork 目标必须预先登记且为空。操作校验执行已结清及所选目录镜像，再追加新的 Agent 状态基线。已有历史镜像直接复用；没有镜像的旧检查点不能借用被后续工具改变的目录。后续 Turn 使用恢复后的上下文与 Workspace，先前公开事件继续保留。文件资产、Artifact 发布与外部业务副作用是独立资源，恢复上下文不会回退它们。具体见[检查点操作契约](/v2/zh/service/claude-session-api#恢复与-fork)。

归档、解除归档和删除属于另一组 Session 生命周期命令，需要幂等键，回执从 `/lifecycle/{command}` 读取。应持续读取到完成；取消已受理工作不代表物理执行立即停止。删除也不承诺立即清除所有审计记录。

## 后台通知与客户端复用

按页观察使用 `GET /events?after=...&limit=100`，持续观察使用 SSE。Java 与 TypeScript 的原生 SDK 入口是 `ServiceClient.managedSessions()`，Python 为 `managed_sessions()`。保存稳定的写入键和不透明 Session 游标。旧后端的 `/actions`、`/commands`、export 或 trace 示例不能直接替换这些原生路径。

Console 的 Managed 视图使用 `agentSessions.ts` 与 `agentSessionView.ts`。任务适配器可以把事件投影为另一份公开执行视图，但该视图有自己的游标，不能用于读取原 Session。通知与导出必须遵循所选后端的实际能力；公开观察不能包含私有恢复状态或凭据。

## Managed 运行时协议

恢复扫描已提交事件元数据，只加载最后一个 checkpoint 的状态对象；Turn 和待办查询不加载历史 checkpoint。Dispatch 收集与 Recorder 启动校验分别复用各自的投影扫描，导入基线中的历史消息 ID 仍会保留。同一次执行中的日志包装器共享有界读取缓存，故障接管时重新读取。一个原生调用的工具任务一次取齐。这些读取优化保留内核获取 writer、工具结果补齐事务和随后 Recorder 恢复的边界；原生完整历史读取仍返回完整载荷。

原生提交结果不确定时，现有 Guard 失败路径会停止当前 Harness。MQ 重投获取新 epoch，从已提交 head、原生历史和 checkpoint 恢复，不重放历史批次回执。工具结果仍是持久业务事实：`ma.tool_task.result_hash` 与业务结果原子保存原始结果身份；相同结果重投即使在结果 Outbox 清理后仍可确认，内容冲突或执行身份不匹配会被拒绝。Managed SessionLog 不支持永久 `sealWriter`；Redis 执行权、epoch 隔离和本地交接保护继续生效。

一个模型工具可以包含多个 Hands 操作。`tool.operation.completed` 只更新操作，真实模型工具结果才完成工具。Brain 挂起时，输出和进度仍继续，替换后的 Hands 从原执行的持久输出游标续采。缺口、最终水位与预览上限见[工具事件合约](/v2/zh/service/sse-events#managed-工具输出与操作进度)。

副作用未知的工具不会自动重跑。旧 Workspace journal、自动重建 sandbox、`authorization_generation`、`tool-recovery` 与 `workspace-recovery` 协议不在 `/v1/sessions` 中提供。源码接入与部署验收需要分别判断。

## 特权原生 trace

原生 Session API 提供已提交公开事件和检查点元数据，当前不提供旧的特权 `/trace` 接口。内部恢复日志、工具来源字节与检查点对象不属于公开 SSE 或快照。运维应使用部署内部的可观测流程，不能沿用旧 HTTP 修复或 trace 示例。
