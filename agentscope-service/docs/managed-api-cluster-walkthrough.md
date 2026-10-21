# AgentScope Service 集群 API 测试手册

按顺序完成登录、创建 Session、提交两轮任务、检查上下文与执行顺序、读取结果及断线续传。最后可单独测试幂等、轮内输入、取消、checkpoint 和预算。

本手册对应 2026 年 10 月 19 日的集群部署，镜像版本为 `2.1.0-test.20261019-100944-all`。Control、Gateway、Portal、Dataplane 和 Hands 各自独立部署，每个组件有两个就绪 Pod。

| 项目 | 当前值 |
| --- | --- |
| Console 和 API 入口 | `http://121.43.66.28` |
| 登录用户名 | `admin`，密码沿用原管理员密码 |
| Tenant 和 Namespace | `default` / `default` |
| 可用文本 Agent | `b65bc0ac-80a1-4979-81b9-da61f358de3b`，部署验收 20261018 |
| 可用文本 Environment | `env_1bec5a78c20a`，文本验收环境（文件和外部工具关闭） |
| 已完成部署验收的 Session | `f4972fa1-209e-59c7-9629-eb87eab452b0` |

第 1 至 9 节使用关闭内置工具的文本验收 Agent。长工具测试另有专用 HTTP MCP 服务、Agent 和 Session，可直接验证 Suspend、Resume 和 T2 顺序，见第 10 节；这条 MCP 路径不需要 Sandbox、Environment Worker 或 NAS。

用户文件上传、file_input 和 Artifact 功能关闭；checkpoint 和大对象使用 PostgreSQL，不依赖 NAS。旧的[本地测试手册](managed-api-local-walkthrough.md)仍保留，但其中 localhost、免鉴权、Docker、本地进程目录及旧 Topic 名称不适用于本集群。

## 1 准备终端并登录

终端 A 只需运行下面一条命令。脚本使用本机已有 kubeconfig 读取测试集群管理员凭据并自动登录，无需输入密码，然后准备测试变量和 `api` 函数并进入测试终端。

```bash
bash /Users/ken/agentscope-3/agentscope-java/agentscope-service/scripts/cluster-api-shell.sh
```

看到 `agentscope-test>` 提示符后，直接继续第 2 节，后续命令都在该终端执行。输入 `exit` 返回原终端。脚本需要 `curl`、`jq`、`uuidgen` 和已获此测试集群访问权限的 `kubectl`；在其他机器可加 `--password` 手动输入真正的管理员密码，密码不是用户名 `admin`。

测试记录自动保存在脚本打印的目录中。`login.json` 和 `active.env` 含登录令牌，文件仅当前用户可读写；分享记录时不要包含它们。令牌失效时退出测试终端，重新执行本节命令登录。

## 2 创建自己的 Session

```bash
jq -n --arg agent "$AGENT_ID" --arg env "$ENV_ID" \
  --arg title "手动验收 $RUN_ID" \
  '{agent:$agent,environment_id:$env,title:$title}' \
  > "$D/session-request.json"

api /v1/sessions \
  -H "Idempotency-Key: session-$RUN_ID" \
  --data-binary "@$D/session-request.json" > "$D/session.json"

SESSION_ID=$(jq -er '.id' "$D/session.json")
save_state
printf 'Session: %s\n' "$SESSION_ID"

api "/v1/sessions/$SESSION_ID" | jq .
api "/v1/sessions/$SESSION_ID/capabilities" | jq .
```

Session 创建后即可提交 Turn。当前 capabilities 中 `files/file_input/artifacts=false`；`checkpoints/checkpoint_restore/fork/budget=true`。恢复与 Fork 还需要满足具体操作的空闲和资源条件，不能仅凭 capability 判断某个 checkpoint 必然可恢复。

Session 固定创建时的 Agent 定义与 Environment 绑定，之后修改 Agent 不会改变这个 Session。

## 3 连续提交两轮并验证上下文

第一轮记住一个本次独有的暗号，第二轮从上下文中取回。收到 T1 的受理响应后就提交 T2，无需等 T1 完成。

```bash
SECRET_WORD="验收-$RUN_ID"
jq -n --arg message "请记住本次暗号：$SECRET_WORD。只回复：已记住。" \
  '{input:{message:$message}}' > "$D/turn1-request.json"

api "/v1/sessions/$SESSION_ID/turns" \
  -H "Idempotency-Key: turn1-$RUN_ID" \
  --data-binary "@$D/turn1-request.json" > "$D/turn1.json"
TURN1=$(jq -er '.id' "$D/turn1.json")

printf '%s\n' '{"input":{"message":"上一轮让你记住的暗号是什么？只回复暗号，不要调用工具。"}}' \
  > "$D/turn2-request.json"
api "/v1/sessions/$SESSION_ID/turns" \
  -H "Idempotency-Key: turn2-$RUN_ID" \
  --data-binary "@$D/turn2-request.json" > "$D/turn2.json"
TURN2=$(jq -er '.id' "$D/turn2.json")
save_state

jq '{id,status}' "$D/turn1.json" "$D/turn2.json"
```

轮询两轮状态，最多观察五分钟。`accepted` 只是受理；成功终态为 `completed`，其他终态为 `failed/cancelled`。

```bash
for ((attempt=0; attempt<150; attempt++)); do
  api "/v1/sessions/$SESSION_ID/turns/$TURN1" > "$D/turn1-status.json" || break
  api "/v1/sessions/$SESSION_ID/turns/$TURN2" > "$D/turn2-status.json" || break
  jq -s 'map({id,status,inputs_open})' "$D/turn1-status.json" "$D/turn2-status.json"
  if jq -es 'all(.[]; .status=="completed" or .status=="failed" or .status=="cancelled")' \
    "$D/turn1-status.json" "$D/turn2-status.json" >/dev/null; then
    break
  fi
  sleep 2
done

api "/v1/sessions/$SESSION_ID/turns?order=asc&limit=100" | jq .
```

预期两轮均为 `completed`。T2 在 T1 完成后才进入 `running`。由于文本调用可能很快，轮询未必能捕捉全部中间状态，执行顺序以第 4 节的持久事件为准。`delivery_status=queued` 是发布账本字段，不能用来判断执行是否完成或 MQ 是否已经 ACK。

## 4 查看最终回复和完整事件

```bash
for TURN_ID in "$TURN1" "$TURN2"; do
  api "/v1/sessions/$SESSION_ID/turns/$TURN_ID/snapshot?limit=100" \
    > "$D/$TURN_ID-snapshot.json"
  jq '{
    final_output: [.resources.items.data[]
      | select(.data.final_output==true) | .data.item.content],
    required_actions: .resources.required_actions.data
  }' "$D/$TURN_ID-snapshot.json"
done
```

第一轮应回复“已记住”，第二轮应返回本次 `SECRET_WORD`。快照各资源集合可能分页；`has_more=true` 时按该集合的 `next_cursor` 和相同 `snapshot_id` 查询 `/resources/{kind}`。

下面保存所有已提交事件，自动处理分页：

```bash
: > "$D/events.jsonl"
AFTER=''
while true; do
  args=(--get --data-urlencode 'limit=100')
  if [ -n "$AFTER" ]; then args+=(--data-urlencode "after=$AFTER"); fi
  api "/v1/sessions/$SESSION_ID/events" "${args[@]}" > "$D/events-page.json" || break
  jq -c '.data[]' "$D/events-page.json" >> "$D/events.jsonl"
  AFTER=$(jq -er '.next_cursor' "$D/events-page.json")
  [ "$(jq -r '.has_more' "$D/events-page.json")" = true ] || break
done

jq -s '[.[]
  | select(.type=="turn.running" or .type=="turn.completed" or .type=="turn.failed" or .type=="turn.cancelled")
  | {type,turn_id:.data.turn_id,created_at}]' "$D/events.jsonl"

jq -s '[.[] | select(.type=="item.completed" and .data.final_output==true)
  | {turn_id:.data.turn_id,content:.data.item.content}]' "$D/events.jsonl"
```

两轮正常完成时，关键顺序应为 `T1 running → T1 completed → T2 running → T2 completed`。事件 JSON 的 `id` 用来去重，`cursor` 用来续传。

## 5 订阅 SSE 和断线续传

终端 A 先执行 `printf '%s\n' "$D/active.env"`，复制输出的绝对路径。终端 B 进入 Bash 后加载它：

```bash
bash
```

```bash
# 将路径替换为终端 A 输出的真实路径
source /实际测试目录/active.env

api "/v1/sessions/$SESSION_ID/events/stream" \
  --max-time 0 --no-buffer -H 'Accept: text/event-stream'
```

从现有快照之后接着观察，可以在终端 B 用下面的方式重连；在终端 A 再提交新 Turn，观察新增事件：

```bash
SNAPSHOT=$(api "/v1/sessions/$SESSION_ID/snapshot?limit=100")
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
api "/v1/sessions/$SESSION_ID/events/stream" \
  --max-time 0 --no-buffer -H 'Accept: text/event-stream' \
  --get --data-urlencode "after=$CURSOR"
```

`Ctrl+C` 仅断开观察，不取消任务。实际客户端保存最后成功应用的 SSE 帧 `id`，再用 `Last-Event-ID` 重连；不要用事件 JSON 的 `id`。15 秒左右的 keepalive 注释不表示任务完成。

## 6 验证请求幂等

在终端 A 重发与第一轮完全相同的请求及幂等键：

```bash
api "/v1/sessions/$SESSION_ID/turns" \
  -H "Idempotency-Key: turn1-$RUN_ID" \
  --data-binary "@$D/turn1-request.json" > "$D/turn1-retry.json"

jq -n --arg original "$TURN1" \
  --arg retried "$(jq -er '.id' "$D/turn1-retry.json")" \
  '{original:$original,retried:$retried,same:($original==$retried)}'
```

预期 `same=true`，不会多执行一轮。相同键配不同内容应返回 `409`。提交超时或 HTTP 5xx 后，重试同一意图必须保留原键与原请求体；主动提交新任务才换键。

## 7 轮内补充和取消

需要选仍开放的 Turn。可另提交一轮较长文本任务，保存为 `TARGET_TURN`，随后立即查看交互能力：

```bash
api "/v1/sessions/$SESSION_ID/turns" \
  -H "Idempotency-Key: interactive-$RUN_ID" \
  --data-binary '{"input":{"message":"请写一篇详细的分布式 Agent 系统测试方案，逐项解释至少三十个场景，不使用工具。"}}' \
  > "$D/interactive-turn.json"
TARGET_TURN=$(jq -er '.id' "$D/interactive-turn.json")

api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN/capabilities" | jq .
```

在 `inputs_open=true` 且 `available_commands` 包含相应能力时选择一种操作。下面例子使用 inject；将 `type` 改成 `steer` 可测试修改当前目标。

```bash
api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN/inputs" \
  -H "Idempotency-Key: inject-$RUN_ID" \
  --data-binary '{"type":"inject","payload":{"message":"最终回复请使用中文，并补充多副本故障恢复场景。"}}' \
  > "$D/input.json"
INPUT_ID=$(jq -er '.id' "$D/input.json")
api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN/inputs/$INPUT_ID" | jq .
```

输入 `accepted` 表示持久受理，`applied` 才表示真正应用；`rejected` 时查看 `rejection_reason`。输入在执行边界生效，不能改写已经发出的模型请求。文本任务可能先完成，关闭输入后返回 `409`，此时换一个仍开放的新 Turn 测试。

取消使用同一个 Input 接口。选择一个仍未结束的 `TARGET_TURN`：

```bash
api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN/inputs" \
  -H "Idempotency-Key: cancel-$RUN_ID" \
  --data-binary '{"type":"cancel","payload":{}}' | jq .

api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN/inputs?limit=100" | jq .
```

受理取消后继续查询到 Turn 的真实终态。已完成的提交可能先于取消生效；取消回执本身不等于 `cancelled`。

## 8 读取 checkpoint 和预算

```bash
api "/v1/sessions/$SESSION_ID/checkpoints?limit=100" | jq .
api "/v1/sessions/$SESSION_ID/usage" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TURN1/usage" | jq .
api "/v1/sessions/$SESSION_ID/budget" | jq .
```

checkpoint 接口只返回句柄和元数据，不公开 Harness 的私有状态。`workspace_available=false` 表示没有已保存的 Workspace 镜像，不表示没有 Agent 上下文。若 `has_more=true`，以 `next_cursor` 作为 `after`、保留同一 `as_of` 作为 `through` 翻页；第一页最后一条未必是全局最新 checkpoint。

可在本次新建 Session 上设置模型调用次数上限，先读 revision，再更新：

```bash
BUDGET=$(api "/v1/sessions/$SESSION_ID/budget")
REVISION=$(jq -er '.revision' <<< "$BUDGET")
api "/v1/sessions/$SESSION_ID/budget" -X PUT \
  -H "If-Match: $REVISION" \
  --data-binary '{"max_model_calls":20}' | jq .
```

上限作用于本 Session 已累计用量；版本不一致返回 `409`。若要清除本次限制，重新读取 revision 后 PUT `{}`。

全部 Turn、工具和 Input 结清后，可选择已提交的 checkpoint 测试恢复。恢复会改变此 Session 后续使用的上下文，应在本次专用测试 Session 中操作：

```bash
CP_ID='替换为选中的 checkpoint_id'
jq -n --arg checkpoint "$CP_ID" '{checkpoint_id:$checkpoint}' \
  > "$D/restore-request.json"
api "/v1/sessions/$SESSION_ID/checkpoints/restore" \
  -H "Idempotency-Key: restore-$RUN_ID" \
  --data-binary "@$D/restore-request.json" > "$D/restore.json"
OPERATION_ID=$(jq -er '.operation_id' "$D/restore.json")
api "/v1/sessions/$SESSION_ID/checkpoint-operations/$OPERATION_ID" | jq .
```

持续查询操作回执，直到 `completed/rejected/cancelled`。只有 completed 后才提交新的 Turn 检查恢复的上下文。Fork 接口为 `POST /v1/sessions/{source}/fork`，请求体为 `{"checkpoint_id":"...","target_session_id":"..."}`；目标必须预先创建、定义兼容且为空，回执仍从源 Session 查询。恢复和 Fork 不回退外部业务副作用。

## 9 回答实际的待办

当前纯文本 Agent 不会自动产生工具审批或外部结果待办。本节仅在快照确实有 `required_actions`、Turn 处于等待状态时使用。

```bash
api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN/snapshot?limit=100" \
  | jq '.resources.required_actions.data'
```

读取实际待办的 `request_id`，作为 reply 的 `action_id`。审批待办使用：

```bash
ACTION_ID='替换为真实 request_id'
jq -n --arg action "$ACTION_ID" \
  '{type:"reply",action_id:$action,payload:{allow:true,reason:"手动测试确认"}}' \
  > "$D/reply-request.json"
api "/v1/sessions/$SESSION_ID/turns/$TARGET_TURN/inputs" \
  -H "Idempotency-Key: reply-$RUN_ID" \
  --data-binary "@$D/reply-request.json" | jq .
```

只有明确要求外部执行结果的待办才使用 `payload={"content":[{"type":"text","text":"真实结果"}],"is_error":false}`；它与审批答复不能互换。Hands 正在执行的普通工具不需要客户端填写结果。答复后查询 Input 应用状态及同一个 Turn 的进度。

## 10 HTTP MCP 长工具挂起和自动 Resume

已为此场景配置独立的两个 HTTP MCP Pod，以及专用 Agent 和手动测试 Session。直接运行下面一条命令，自动登录并选中专用资源：

```bash
bash /Users/ken/agentscope-3/agentscope-java/agentscope-service/scripts/cluster-api-shell.sh --long-tool
```

随后按[集群 HTTP MCP 长工具测试手册](managed-mcp-long-tool-cluster-walkthrough.md)提交 T1 和 T2，观察 90 秒真实等待期间 T1 为 waiting_tool、T2 保持 accepted；工具完成后 T1 自动恢复，T1 completed 后 T2 才 running。

这项测试不需要 Sandbox、Environment Worker 或 NAS。工具由平台 Hands 直接通过 HTTP 调用，只等待并返回唯一调用编号与时间，不执行 Shell 或文件操作。原文本测试 Session 保留，`--long-tool` 使用另一份专用 Session。Shell/Sandbox、进程故障接管和 Broker 重启不属于这项测试的验收范围。

## 11 记录异常和测试结果

2026 年 10 月 19 日部署验收已完成旧 Session 恢复及新 Session 两轮真实模型调用。此前空响应 HTTP 500 对应 Gateway 转发到 Portal 时的连接重置。Helm revision 8 已增加连接池 10 秒空闲上限及每 5 秒后台回收；两个 Gateway 的空闲回收、40 次查询、原幂等键创建重试与 30 秒 SSE 均通过验证。遇到相同现象请记录时间、请求路径、HTTP 状态和响应体，提交请求始终使用原幂等键重试。

可用下面的方式单独保存一次查询的响应头、响应体和 HTTP 状态；文件可能包含测试内容，按需分享：

```bash
curl -sS --connect-timeout 10 --max-time 30 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'X-AgentScope-Tenant: default' \
  -H 'X-AgentScope-Namespace: default' \
  -D "$D/query-headers.txt" -o "$D/query-body.json" \
  -w 'HTTP %{http_code}\n' \
  "$BASE/v1/sessions/$SESSION_ID/turns/$TURN1"
date -Iseconds
```

主流程通过条件：登录与创建成功；两轮均 completed；T2 正确取回暗号；事件中 T1 completed 先于 T2 running；相同幂等键返回同一 Turn；SSE 断线后仍能续读已提交事件。文本主流程不代表已经验收 Worker、长工具恢复、故障切换或 Broker 重启场景。
