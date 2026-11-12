# Managed Agents 本地 API 测试手册

> 本文保留旧部署的基础设施诊断记录，其中 /turns 写入、snapshot 与持久 SSE 示例不适用于 Draft 3。
> 新源码协议见 [会话事件 API](../../docs/v2/zh/service/claude-session-api.md)；新 MCP 验收见
> [长工具手册](managed-mcp-long-tool-cluster-walkthrough.md)。本轮未升级集群。


从创建 Environment、Agent、Session 开始，提交一个调用 90 秒工具的 Turn，再提交第二个 Turn，验证排队、checkpoint、工具结果回传、推理恢复和结果订阅。

这套流程对应 2026 年 10 月 16 日启动的本地源码环境：入口为 `http://localhost:18080`，启用本地开发模式，无需认证请求头；使用真实模型、PostgreSQL、Redis 和 RocketMQ，工具通过本机 Tool Worker 子进程执行。当前环境未接入 ACS Sandbox，因此本流程验证本地 Agent Harness Runtime 与 Tool Worker 分离链路。

## 1 准备终端和变量

打开终端 A，先进入 Bash。后续命令在同一个 Bash 中依次执行，使用 `curl`、`jq` 和 Docker CLI。

```bash
bash
```

```bash
set -o pipefail
umask 077

REPO=/Users/ken/agentcore/agentcore-managed-agents
RUN_DIR="$REPO/managed-agents/.dev-stack"
D="$RUN_DIR/manual-api"
API="$RUN_DIR/manual-api.sh"
BASE=http://localhost:18080
RUN_ID="$(date +%Y%m%d-%H%M%S)-$$"
TURN1=''
TURN2=''
mkdir -p "$D"

save_state() {
  for name in BASE RUN_DIR RUN_ID ENV_ID AGENT_ID SESSION_ID TURN1 TURN2; do
    printf 'export %s=%q\n' "$name" "${!name-}"
  done > "$D/active.env"
}

db() {
  docker exec -i managed-agents-native-dev-pg \
    psql -X -v ON_ERROR_STOP=1 -U builder -d builder "$@"
}
```

`D` 保存请求、响应和本次测试 ID。执行后续创建步骤会建立一组新资源，并更新 `active.env`；保留已有 Session 时直接参考第 13 节加载变量，不要重新创建。

`API` 指向本次准备的[本地排查脚本](../.dev-stack/manual-api.sh)。该脚本位于 Git 忽略目录，当前机器上可以直接使用，不随仓库克隆提供。主流程保留完整 `curl`；关键排查也给出原始命令。

检查当前服务是否可达：

```bash
curl --fail-with-body -sS "$BASE/api/auth/me" | jq .
```

## 2 创建 Environment 和 Agent

先创建本地 Environment：

```bash
cat > "$D/environment-request.json" <<'JSON'
{
  "name": "API Long Tool Environment",
  "type": "local",
  "config": {}
}
JSON

curl --fail-with-body -sS \
  -X POST "$BASE/api/environments" \
  -H 'Content-Type: application/json' \
  --data-binary "@$D/environment-request.json" \
  > "$D/environment.json"

ENV_ID=$(jq -er '.id' "$D/environment.json")
printf 'Environment: %s\n' "$ENV_ID"
```

创建 Managed Agent，启用 `execute` 和 `read_file`。模型使用当前服务的默认配置，模型密钥不放进这些请求。

```bash
jq -n --arg env "$ENV_ID" --arg key "api-long-tool-$RUN_ID" '
{
  tenant: "default",
  namespace: "default",
  agentKey: $key,
  displayName: "API Long Tool Demo",
  binding: {kind: "managed"},
  definition: {
    name: "API Long Tool Demo",
    system: "你是测试助手。严格按用户要求实际调用工具，等待工具结果后再回答，不得虚构结果；不要重复执行已经提交的命令。",
    defaultEnvironmentId: $env,
    maxIters: 8,
    tools: [{
      type: "agent_toolset",
      defaultConfig: {enabled: false},
      configs: [
        {name: "execute", enabled: true, permissionPolicy: {type: "always_allow"}},
        {name: "read_file", enabled: true, permissionPolicy: {type: "always_allow"}}
      ]
    }]
  }
}' > "$D/agent-request.json"

curl --fail-with-body -sS \
  -X POST "$BASE/api/v1/agents" \
  -H 'Content-Type: application/json' \
  --data-binary "@$D/agent-request.json" \
  > "$D/agent.json"

AGENT_ID=$(jq -er '.agent.id' "$D/agent.json")
printf 'Agent: %s\n' "$AGENT_ID"
```

每一步都应成功返回 ID，再继续下一步。请求体保存在对应的 `*-request.json` 中，可以直接查看、修改和复用。

## 3 创建 Session

```bash
jq -n --arg agent "$AGENT_ID" --arg env "$ENV_ID" '
{
  agent: $agent,
  environment_id: $env,
  title: "API 长工具与 Turn 排队测试"
}' > "$D/session-request.json"

curl --fail-with-body -sS \
  -X POST "$BASE/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: session-$RUN_ID" \
  --data-binary "@$D/session-request.json" \
  > "$D/session.json"

SESSION_ID=$(jq -er '.id' "$D/session.json")
save_state
printf 'Session: %s\n' "$SESSION_ID"

curl --fail-with-body -sS "$BASE/v1/sessions/$SESSION_ID" | jq .
```

返回 Session ID 后即可提交 Turn。相同幂等键用于重试相同请求；新请求应使用新的键。

## 4 订阅 Session 事件

另开终端 B，加载终端 A 保存的 Session，保持 SSE 连接打开：

```bash
source /Users/ken/agentcore/agentcore-managed-agents/managed-agents/.dev-stack/manual-api/active.env

curl --fail-with-body -NsS \
  "$BASE/v1/sessions/$SESSION_ID/events/stream"
```

`Ctrl+C` 只关闭订阅，不会取消 Turn。断开后可以重新订阅，或通过事件历史查看已提交结果。

## 5 提交长工具 Turn 和排队 Turn

回到终端 A。第一轮要求 Agent 实际调用一次 `execute`：先输出 `tool-start`，等待 90 秒，再输出 `tool-finished` 并写入文件。`timeout` 设置为 150 秒，超过工具等待时间。

```bash
cat > "$D/turn1-request.json" <<'JSON'
{
  "input": {
    "message": "请只调用一次 execute 工具，timeout 参数设置为 150 秒，执行以下完整命令：\n```sh\nmkdir -p output\nprintf 'tool-start\\n'\nsleep 90\nprintf 'tool-finished\\n' | tee output/long-tool.txt\n```\n请等待工具执行完成，根据实际输出简短汇报执行结果和文件路径，不要重复执行命令。"
  }
}
JSON

curl --fail-with-body -sS \
  -X POST "$BASE/v1/sessions/$SESSION_ID/turns" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: curl-long-$RUN_ID" \
  --data-binary "@$D/turn1-request.json" \
  | tee "$D/turn1.json" | jq .

TURN1=$(jq -er '.id' "$D/turn1.json")
save_state
```

收到第一轮回执后，立即提交第二轮：

```bash
cat > "$D/turn2-request.json" <<'JSON'
{
  "input": {
    "message": "这是第二轮。请使用 read_file 读取 output/long-tool.txt，结合上一轮上下文，报告上一轮是否完成以及文件的实际内容。不要重新执行上一轮长命令。"
  }
}
JSON

curl --fail-with-body -sS \
  -X POST "$BASE/v1/sessions/$SESSION_ID/turns" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: curl-next-$RUN_ID" \
  --data-binary "@$D/turn2-request.json" \
  | tee "$D/turn2.json" | jq .

TURN2=$(jq -er '.id' "$D/turn2.json")
save_state
```

返回 `accepted` 仅代表已受理。本文通过 `curl` 提交 Turn，后续使用脚本的查询命令即可；再执行脚本的 `turn1`、`turn2` 会使用另一组请求键，产生额外 Turn。

## 6 查看 Turn 状态和业务排队账本

```bash
curl --fail-with-body -sS \
  "$BASE/v1/sessions/$SESSION_ID/turns?limit=100&order=asc" | jq .

curl --fail-with-body -sS \
  "$BASE/v1/sessions/$SESSION_ID/turns/$TURN1" | jq .
```

模型调用和工具启动需要少量时间。第一轮长工具运行期间，第一轮通常进入 `waiting_tool`，第二轮保持 `accepted`。工具完成后，第一轮恢复推理并进入 `completed`，随后第二轮才开始运行。

使用竖排格式查看数据库账本，避免宽表格挤出终端：

```bash
"$API" queue

# 完整 ID、完整输入和挂起任务列表
"$API" queue --full
```

不使用脚本时，可以直接执行以下 SQL：

```bash
db -x -P pager=off -v sid="$SESSION_ID" <<'SQL'
SELECT t.publication_sequence,
       left(t.turn_id,22)||'…' AS turn_id,
       t.status,t.delivery_status,t.broker_message_id
FROM ma.turn t JOIN ma.session s USING(scope_key)
WHERE s.session_id=:'sid'
ORDER BY t.publication_sequence NULLS LAST;

SELECT left(p.turn_id,22)||'…' AS turn_id,
       p.status,p.resume_phase,p.checkpoint_sequence,
       json_array_length(p.pending_tasks::json) AS pending_task_count
FROM ma.turn_suspension p JOIN ma.session s USING(scope_key)
WHERE s.session_id=:'sid';
SQL
```

| 字段 | 查看方式 |
| --- | --- |
| `publication_sequence` | 本 Session 的发布顺序 |
| Turn `status` | 公共 API 成功终态为 `completed`；当前数据库内部成功状态为 `succeeded` |
| `delivery_status` | 发布账本字段；当前执行完成后仍可能保持 `queued`，不能据此判断是否完成或 ACK |
| 挂起记录 `status` | `waiting` 表示仍在等待，`resumed` 表示该历史挂起已经恢复 |
| `resume_phase` | 等待工具结果时为 `APPLY_TOOL_RESULTS`；历史挂起记录保留该阶段 |
| `pending_task_count` | 该挂起点记录的工具数量；已恢复记录仍保留原数量 |

## 7 查看 checkpoint 和 Agent 上下文

### 7.1 公共 checkpoint 元数据

```bash
curl --fail-with-body -sS \
  "$BASE/v1/sessions/$SESSION_ID/checkpoints?limit=100" | jq .

# 自动处理分页，并保存 checkpoints.jsonl
"$API" checkpoints
```

单个 checkpoint 的查询接口为：

```bash
CP_ID=$(jq -sr 'last.checkpoint_id' "$D/checkpoints.jsonl")
curl --fail-with-body -sS \
  "$BASE/v1/sessions/$SESSION_ID/checkpoints/$CP_ID" | jq .
```

列表按保存顺序返回；若 `has_more=true`，需要继续传入 `next_cursor` 作为 `after`，并保留同一 `as_of` 作为 `through`。不要将第一页最后一项直接当作全局最新 checkpoint。`workspace_available=false` 不代表没有 Agent 上下文，它表示该 checkpoint 没有可用的 workspace 快照。

### 7.2 本机私有 checkpoint 内容

公共 API 返回 checkpoint 句柄和元数据。要检查原始上下文，在终端 A 使用本机运维方式读取已提交的私有文件：

```bash
"$API" context
```

也可以直接执行：

```bash
CHECKPOINT_REF=$(db -At -v sid="$SESSION_ID" <<'SQL'
SELECT checkpoint FROM ma.session WHERE session_id=:'sid';
SQL
)

CHECKPOINT_FILE="$RUN_DIR/managed-runtime-blobs/checkpoints/$(
  printf '%s\n' "$CHECKPOINT_REF" | jq -er '.storageRef'
)"
CP_ID=$(printf '%s\n' "$CHECKPOINT_REF" | jq -er '.identity.checkpointId')
cp "$CHECKPOINT_FILE" "$D/$CP_ID.json"

jq '{identity,continuation:{
  resumePhase:.continuation.resumePhase,
  pendingTaskIds:.continuation.pendingTaskIds,
  appliedInputSeq:.continuation.appliedInputSeq,
  modelAnchor:.continuation.modelAnchor,
  terminalStatus:.continuation.terminalStatus
}}' "$D/$CP_ID.json"

jq -r '.nativePayloadJson | fromjson | .stateJson' "$D/$CP_ID.json" \
  | jq '{session_id,user_id,cur_iter,summary,context}'
```

建议在 `waiting_tool` 期间和全部完成后各保存一次。等待时应看到 `APPLY_TOOL_RESULTS` 和待完成任务；最终 checkpoint 应进入 `TERMINAL`。文件按 checkpoint ID 保存，可以比较恢复前后的上下文。

## 8 查看 RocketMQ 消费进度和原始消息

### 8.1 消费进度

```bash
"$API" mq
```

原始命令如下。Session 的 MQ 标识是数据库里的 `scope_key`，不是直接使用 Session UUID：

```bash
SCOPE_KEY=$(db -At -v sid="$SESSION_ID" <<'SQL'
SELECT scope_key FROM ma.session WHERE session_id=:'sid';
SQL
)

mqadmin() {
  docker exec managed-mq-redis-lite-min-offset-broker-1 \
    sh /home/rocketmq/rocketmq-5.5.1/bin/mqadmin "$@"
}

mqadmin getLiteTopicInfo -n namesrv:9876 \
  -p ma_local_turns_v3 -l "$SCOPE_KEY"

mqadmin consumerProgress -n namesrv:9876 \
  -c ma_local_turns_v3 -g ma_local_brain_v3 \
  -t "%LMQ%\$ma_local_turns_v3\$$SCOPE_KEY"
```

对于新建 Session 且只提交了两轮的情况，典型消费位点如下。Broker 的消费位点更新可能存在短暂延迟：

| 阶段 | Broker Offset | Consumer Offset | Diff |
| --- | --- | --- | --- |
| 第一轮等待长工具，第二轮排队 | 2 | 0 | 2 |
| 两轮完成并 ACK | 2 | 2 | 0 |

`Diff` 包括正在运行但尚未 ACK 的第一轮。这里必须查询 Session 对应的 LiteTopic；直接查看父 Topic 的普通消费位点不能表示该 Session 的实际进度。

长工具挂起时保留原 Turn 投递，工具结果提交后通过独立 Resume Topic 恢复同一个 Turn；整个 Turn 终态后才完成原投递 ACK。

本机四条逻辑通道的根父 Topic 和消费组为：

| 通道 | 父 Topic | 消费组 |
| --- | --- | --- |
| Turn | `ma_local_turns_v3` | `ma_local_brain_v3` |
| Agent Harness Runtime 运行事件 | `ma_local_runtime_events_v3` | `ma_local_hands_events_v3` |
| 工具事件 | `ma_local_tool_events_v3` | `ma_local_hands_tool_events_v3` |
| Resume | `ma_local_turn_resumes_v3` | `ma_local_brain_resumes_v3` |

本例与子 Session 共用同一个 Turn Topic 和消费组；深度只作为冻结业务元数据，不再预建 `_d1` 至 `_d16` Topic。当前本机 Broker 使用显式测试修正版，正式部署能力边界见[运行架构](managed-runtime-dataplane.md)。

### 8.2 消息内容

```bash
"$API" message
"$API" message "$TURN2"
```

脱离脚本时，先查 Broker 消息 ID，再只读查询消息体：

```bash
BROKER_ID=$(db -At -v sid="$SESSION_ID" -v tid="$TURN1" <<'SQL'
SELECT t.broker_message_id
FROM ma.turn t JOIN ma.session s USING(scope_key)
WHERE s.session_id=:'sid' AND t.turn_id=:'tid';
SQL
)

mqadmin queryMsgById -n namesrv:9876 -t ma_local_turns_v3 \
  -i "$BROKER_ID" > "$D/mq-$BROKER_ID.txt"

docker exec managed-mq-redis-lite-min-offset-broker-1 \
  cat "/tmp/rocketmq/msgbodys/$BROKER_ID" | jq .
```

Turn 消息体包含 `scope`、`turnId` 和 `publicationSequence` 等引用；完整用户输入保存在数据库账本中，可通过 `queue --full` 查看。上述 MQ 查询不消费消息，也不修改消费位点。

## 9 查看工具执行和结果

### 9.1 公共 API

```bash
curl --fail-with-body -sS --get \
  --data-urlencode "turn=$TURN1" \
  --data-urlencode 'limit=100' \
  "$BASE/v1/sessions/$SESSION_ID/tool-tasks" | jq .

curl --fail-with-body -sS \
  "$BASE/v1/sessions/$SESSION_ID/turns/$TURN1/snapshot?limit=100" \
  | jq '.resources.tools.data[].data | {name,status,progress,output,operations}'
```

工具列表主要提供任务状态；工具输出通过 snapshot 或 SSE 查看。SSE 中 `tool.delta` 提供输出片段，`tool.completed` 表示逻辑工具结束，工具的模型可用结果还会体现在相应的 `item.completed` 中。

当前本机长工具运行期间可能暂时显示 `unknown` 和 `result_status=pending`。应结合 Turn 状态、stdout 和最终结果判断，不要因此重新执行同一条命令。

### 9.2 数据库和实际 Tool Worker 进程

```bash
"$API" tools
```

该命令同时输出公共工具状态、数据库中的物理执行记录，以及实际进程目录。以下命令可单独定位第一轮的用户工具执行；过滤掉 `framework_` 调用是为了排除框架加载工作目录资料的辅助命令。

```bash
EXECUTION_ID=$(db -At -v sid="$SESSION_ID" -v tid="$TURN1" <<'SQL'
SELECT t.execution_id
FROM ma.tool_task t JOIN ma.session s USING(scope_key)
WHERE s.session_id=:'sid' AND t.turn_id=:'tid' AND t.tool_name='sandbox.exec'
  AND t.arguments::json->>'native_tool_call_id' NOT LIKE 'framework_%'
ORDER BY t.task_id LIMIT 1;
SQL
)

JOB_DIR="$RUN_DIR/sandbox/$SCOPE_KEY/.managed-tools/$EXECUTION_ID"
printf 'Tool Worker process directory: %s\n' "$JOB_DIR"
jq '{execution,tool,arguments,operation}' "$JOB_DIR/request.json"
if [ -f "$JOB_DIR/output" ]; then cat "$JOB_DIR/output"; fi
if [ -f "$JOB_DIR/result.json" ]; then jq . "$JOB_DIR/result.json"; fi
```

如果还没有 `EXECUTION_ID` 或进程目录，先等待工具启动。工具运行期间可以单独执行下面这条命令，观察 stdout；`Ctrl+C` 仅停止查看：

```bash
tail -f "$JOB_DIR/output"
```

工具完成后，`result.json` 应包含 `state=SUCCEEDED`、`exitCode=0`，输出包含 `tool-start` 和 `tool-finished`。文件也可从本机查看：

```bash
cat "$RUN_DIR/sandbox/$SCOPE_KEY/output/long-tool.txt"
```

### 9.3 Redis 结果信箱

```bash
"$API" redis
"$API" redis-result
```

`redis-result` 解码第一轮长工具的结果信箱，输出 `taskId`、`executionId`、`status`、`output` 和 `exitCode`。信箱当前保留 5 分钟；过期后，数据库和已提交公开事件中的结果仍然可查。

直接查看本 Session 的 Redis key 和 Input Stream：

```bash
docker exec managed-mq-redis-redis-1 redis-cli \
  --scan --pattern "ma:{$SCOPE_KEY}:*"

docker exec managed-mq-redis-redis-1 redis-cli \
  XRANGE "ma:{$SCOPE_KEY}:inputs:$TURN1" - + COUNT 20
```

## 10 查看最终回复和完整事件

```bash
"$API" status
"$API" final
"$API" history
"$API" mq
```

直接查询两轮最终回复：

```bash
for TURN_ID in "$TURN1" "$TURN2"; do
  curl --fail-with-body -sS \
    "$BASE/v1/sessions/$SESSION_ID/turns/$TURN_ID" \
    | jq '{id,status,inputs_open}'

  curl --fail-with-body -sS \
    "$BASE/v1/sessions/$SESSION_ID/turns/$TURN_ID/snapshot?limit=100" \
    | jq '{
        tools: [.resources.tools.data[].data | {name,status,output}],
        final_output: [.resources.items.data[]
          | select(.data.final_output==true) | .data.item.content]
      }'
done
```

直接查询一页事件历史：

```bash
curl --fail-with-body -sS \
  "$BASE/v1/sessions/$SESSION_ID/events?limit=100" | jq .
```

事件超过一页时使用 `after=next_cursor` 继续读取。脚本的 `history` 自动翻页并保存完整的 `events.jsonl`，可以验证第一轮 `turn.completed` 先于第二轮 `turn.running`：

```bash
jq -s '[.[]
  | select(.type=="turn.running" or .type=="turn.waiting_tool" or .type=="turn.completed")
  | {type,created_at,turn_id:.data.turn_id}]' "$D/events.jsonl"
```

本例的通过条件是：两轮公共状态均为 `completed`；第一轮长工具成功且退出码为 0；第二轮实际通过 `read_file` 读到 `tool-finished`；第二轮在第一轮结束后才运行；MQ 对应 LiteTopic 的 `Diff` 最终为 0。

## 11 可选的运行中输入

在第一轮尚未结束且 `inputs_open=true` 时，发送补充输入：

```bash
curl --fail-with-body -sS \
  -X POST "$BASE/v1/sessions/$SESSION_ID/turns/$TURN1/inputs" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: inject-$RUN_ID" \
  --data-binary '{"type":"inject","payload":{"message":"工具完成后的最终回复，请以“本次验证”开头。"}}' \
  | jq .

curl --fail-with-body -sS \
  "$BASE/v1/sessions/$SESSION_ID/turns/$TURN1/inputs?limit=100" | jq .
```

接收成功和实际应用是两个阶段，继续查看 Input 的 `status` 和 `delivery_status`。运行中的输入在可处理的位置生效，不能假定立即中断正在进行的模型或工具调用。Turn 已结束后提交会返回 `409`。

同一接口可分别提交 `steer` 或 `cancel`。建议另建 Session 单独验证取消，因为取消第一轮会改变上面的成功链路：

```bash
# 在仍运行的第一轮中测试 steer
curl --fail-with-body -sS \
  -X POST "$BASE/v1/sessions/$SESSION_ID/turns/$TURN1/inputs" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: steer-$RUN_ID" \
  --data-binary '{"type":"steer","payload":{"message":"工具完成后，只汇报文件路径和退出码。"}}' \
  | jq .

# 单独测试取消时执行
curl --fail-with-body -sS \
  -X POST "$BASE/v1/sessions/$SESSION_ID/turns/$TURN1/inputs" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: cancel-$RUN_ID" \
  --data-binary '{"type":"cancel","payload":{}}' \
  | jq .
```

## 12 服务日志

### 12.1 各组件的日志位置

当前服务由本机 Java 和 Go 进程运行，启动脚本将标准输出和标准错误重定向到独立文件，目录为：

```bash
RUN_DIR=/Users/ken/agentcore/agentcore-managed-agents/managed-agents/.dev-stack
API="$RUN_DIR/manual-api.sh"
LOG_DIR="$RUN_DIR/logs"
ls -lh "$LOG_DIR"
```

| 组件 | 当前端口 | 日志文件 | 主要用途 |
| --- | --- | --- | --- |
| Gateway | `18080` | `gateway.log` | 入口路由、连接和转发异常 |
| Control Plane | `8081` | `control.log` | Agent、Environment 等资源定义与配置 |
| Agent Service | `8084` | `portal.log` | Session、Turn、Input 受理，查询与 SSE |
| Dataplane / Agent Harness Runtime | `8082` | `data.log` | Agent 构建、模型调用、工具调用与恢复后的推理 |
| Tool Worker | `8085` | `hands.log` | 事件消费、checkpoint 提交、工具执行和恢复异常 |
| Go Orchestration Runtime | `8086` | `orchestration.log` | 独立编排运行时；本例的 Agent Harness Runtime 在 Dataplane 中运行 |
| Scheduler | `8083` | `scheduler.log` | 调度、外部消息和 Webhook 投递相关问题 |

每个文件均位于 `LOG_DIR`。例如，在两个终端分别跟踪 Agent Harness Runtime 和 Tool Worker：

```bash
"$API" logs data
```

```bash
"$API" logs hands
```

将参数改成表中的组件名即可查看其他组件。查看全部七个组件：

```bash
"$API" logs all
```

原始命令使用 `tail -F`，可以跟随文件重新创建；一次跟随多个文件时，终端会显示文件名：

```bash
tail -n 100 -F "$LOG_DIR/data.log" "$LOG_DIR/hands.log" "$LOG_DIR/portal.log"
```

这些命令会持续运行，`Ctrl+C` 仅停止跟踪。启动和迁移失败时另外查看 `schema-control.log`、`schema-agent-service.log`、`schema-agent-harness-runtime.log`、`schema-runtime.log` 和 `schema-grants.log`。

### 12.2 查找一轮任务相关的日志

先加载本次 ID，再搜索已有记录：

```bash
source "$RUN_DIR/manual-api/active.env"

rg -n -F -e "$SESSION_ID" -e "$TURN1" -e "$TURN2" \
  "$LOG_DIR/data.log" "$LOG_DIR/hands.log" "$LOG_DIR/portal.log"
```

普通框架日志没有在每一行携带 Session 和 Turn ID，搜索不到不代表任务没有执行。新增的 `managed_runtime` 结构化日志及其过滤方式见第 13.4 节；需要重建并重启目标服务后生效。已有的 Agent 推理日志通常记录工具调用 ID，可从公共任务接口取得后检索：

```bash
TOOL_CALL_ID=$(curl --fail-with-body -sS --get \
  --data-urlencode "turn=$TURN1" \
  "$BASE/v1/sessions/$SESSION_ID/tool-tasks" \
  | jq -er '.data[0].tool_call_id')

rg -n -F "$TOOL_CALL_ID" "$LOG_DIR/data.log"
```

工具尚未创建时，上面的 ID 查询不会得到值，等待 `tool-tasks` 出现记录后再执行。持续查看已有推理阶段和异常关键词：

```bash
tail -n 100 -F "$LOG_DIR/data.log" "$LOG_DIR/hands.log" \
  | rg --line-buffered 'PRE_CALL|PRE_REASONING|POST_REASONING|PRE_ACTING|POST_ACTING|POST_CALL|ManagedToolWaiting|unacknowledged|unconfirmed|retry/reconciliation'
```

长工具挂起时，当前 Agent Trace 会将 `ManagedToolWaiting: Inline tool wait expired; the original Tool Worker operation remains active` 记录在带 `ERROR` 字样的消息中。这是内联短等待结束的信号；应结合公共 Turn 的 `waiting_tool`、挂起 checkpoint 和后续恢复判断，不能仅凭这行判定任务失败。

升级前的 Tool Worker 进程正常运行时可能长期只有启动信息；包含新增日志的构建会记录工具启动、查询已有执行、结果提交和工具事件发布；Resume 发布日志位于 Agent Harness Runtime。工具 stdout 仍在第 9 节的 `JOB_DIR/output`，完整工具结果在数据库和 `result.json` 中，结构化生命周期日志不打印这些正文。

### 12.3 基础设施日志

以下是当前这套源码环境使用的容器。每次选择一个容器执行跟踪命令：

| 组件 | 容器名 |
| --- | --- |
| PostgreSQL | `managed-agents-native-dev-pg` |
| Redis | `managed-mq-redis-redis-1` |
| MQ NameServer | `managed-mq-redis-lite-min-offset-namesrv-1` |
| MQ Broker | `managed-mq-redis-lite-min-offset-broker-1` |
| MQ Proxy | `managed-mq-redis-lite-min-offset-proxy-1` |

```bash
COMPONENT_CONTAINER=managed-mq-redis-lite-min-offset-proxy-1
docker logs --timestamps --tail 100 -f "$COMPONENT_CONTAINER"
```

RocketMQ 部分运行日志写在容器文件中，`docker logs` 不一定包含全部 Broker 细节。查看当前 Broker 的运行和 LiteTopic 日志：

```bash
docker exec managed-mq-redis-lite-min-offset-broker-1 sh -c \
  'tail -n 100 -F "$HOME/logs/rocketmqlogs/broker.log" "$HOME/logs/rocketmqlogs/lite.log"'
```

## 13 多实例与跨机器恢复的观察方式

本节是后续验证准备，不表示已完成多实例或跨机器验收。当前运行的 Dataplane 和 Tool Worker 各一个实例。

### 13.1 区分物理实例

每个进程使用独立日志文件，跨机器时记录主机名、进程启动时间和部署标签，例如 `dataplane-a`、`dataplane-b`、`hands-a`、`hands-b`。不要让两个进程重定向到同一个日志文件。

下一次启动各个 Java 实例时，为结构化日志配置独立物理实例标签。下面仅展示日志配置片段，应用到完整部署环境后再启动进程；执行这些 `export` 不会修改已经运行的 JVM：

```bash
export MANAGED_AGENTS_INSTANCE_ID=dataplane-a

# 可选：在普通日志前缀中也展示标签
export LOGGING_PATTERN_CONSOLE="%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [${MANAGED_AGENTS_INSTANCE_ID}] [%thread] %logger{36} : %msg%n"
```

在其他实例上分别更换标签，并将 stdout、stderr 收集到各自的文件或容器日志中。远程 JVM 的日志应由其所在机器或部署平台采集，不会自动写回当前机器的 `.dev-stack/logs`。

新增 JSON 中的 `instance_id` 使用 `MANAGED_AGENTS_INSTANCE_ID`，也支持 JVM 参数 `-Dmanaged-agents.instance-id=dataplane-a`，未指定时使用主机名和 PID。`boot_id` 在每次 JVM 启动时重新生成，可区分同一个部署标签下的重启；`host`、`pid` 同时记录。

已有 `Built data-plane agent ... instanceId=dpa-...` 日志里的 `instanceId` 是逻辑 Agent 标识，不能当作 Dataplane 物理实例标识。新增的 `brain.consumer.started`、`brain.callback.registered` 和运行日志将真实消费者 ID、投递 ID 与物理进程关联。

### 13.2 共享与独立配置

| 配置或资源 | 多实例要求 |
| --- | --- |
| PostgreSQL | 连接同一组业务和 Managed 账本，不能各自创建独立数据库 |
| Redis | 使用同一服务和作用域，保证执行 Guard、Input 和结果信箱可见 |
| RocketMQ | 使用同一套 Topic；相同职责的副本加入同一消费组，不能为每个实例另建业务消费组 |
| `MANAGED_AGENTS_RUNTIME_BLOB_DIR` | Agent Service、Agent Harness Runtime、Tool Worker 能读取同一份私有 batch 和 checkpoint 文件；当前实现基于文件路径，需要真正的共享文件系统，只有相同目录字符串不够 |
| 工作区与资源文件 | 定义文件、资源和需要共享的产物使用一致的可访问存储，不能依赖另一台机器的本地目录 |
| Sandbox | 各 Tool Worker 副本能通过持久绑定访问同一个远程 Sandbox 和稳定 `execution_id` |
| 模型及资源配置 | Agent Harness Runtime 副本都能解析同一冻结配置，并具备可用的模型连接 |
| 日志、进程、监听端口 | 每实例独立；同机监听端口不能冲突 |
| 服务地址 | PostgreSQL、Redis、MQ Proxy、Control Plane 等地址从每台机器均可达；本机配置中的 `localhost` 不能原样用于跨机部署 |

当前 `local-test-mode` 在 Tool Worker 本机启动子进程，其执行目录、进程身份和进程控制依赖宿主机。同机多个 Tool Worker 若使用它，需要共享同一个实际 Sandbox 根目录；跨机器不能仅复制这些目录或挂载共享盘，就将其视为远程 Sandbox 的等价验证。跨机工具接管应使用各副本均可重新连接的远程 Sandbox。

`dev-up.sh` 是整套单机开发环境启动脚本，会检查并清理服务端口，还会执行迁移作业。后续增加副本应单独启动目标组件或调整部署配置，不能直接再运行一遍它作为第二个 Dataplane 或 Tool Worker 实例。

### 13.3 用持久记录跟进卸载与恢复

以下 SQL 在终端 A 执行，沿用第 1 节的 `db` 函数。原生日志查询只投影关联元数据，不展开整个上下文：

```bash
db -x -P pager=off -v sid="$SESSION_ID" -v tid="$TURN1" <<'SQL'
SELECT e.sequence,
       e.payload::json->>'type' AS event_type,
       e.payload::json->>'executionRunId' AS run_id,
       e.payload::json->>'occurredAt' AS occurred_at
FROM ma.native_event e JOIN ma.session s USING(scope_key)
WHERE s.session_id=:'sid' AND e.payload::json->>'turnId'=:'tid'
  AND e.payload::json->>'type' IN
      ('run/start','run/end','recovery/applied','state/checkpoint','turn/resumed','tool/result')
ORDER BY e.sequence;

-- FIFO head and active callbacks live in the RocketMQ SDK; no callback mailbox table exists.

SELECT p.status,p.execution_epoch,p.checkpoint_sequence,
       p.checkpoint::json->'identity'->>'checkpointId' AS checkpoint_id,
       p.pending_tasks,p.resume_phase
FROM ma.turn_suspension p JOIN ma.session s USING(scope_key)
WHERE s.session_id=:'sid' AND p.turn_id=:'tid';
SQL
```

`recovery/applied`、同一 Turn 的新 run、挂起记录和 checkpoint 可以证明存在持久恢复过程，再通过结构化日志关联物理实例。`consumerId` 是各通道消费者独立生成的 UUID；同一 JVM 的 Turn 和 Resume 消费者本来就有不同 ID，不能仅凭二者不同推断发生了跨机迁移。

当前还缓存构建好的 Harness Agent。`waiting_tool` 表示活动推理挂起，并不证明整个 Agent 缓存及其全部堆内存已经释放。`brain.run.exited` 和 `brain.execution.released` 表示本次运行退出和活动执行释放；后者的 `evicts_agent_cache=false` 表示该路径不清理构建缓存。真正的构建缓存移除和 Agent 关闭以 `brain.agent.cache.closed` 为准，这也不等同于 JVM 已完成垃圾回收。

后续跨实例验收应串起以下证据：

1. 明确 Dataplane A 开始本 Turn 的物理实例与 run ID，保存其挂起 checkpoint ID、序号和待完成工具执行 ID。
2. 确认 checkpoint 已持久提交，工具仍在运行，第二轮仍排队；按测试安排让 A 退出或不再执行恢复，不能只依赖随机消费分配来假定迁移发生。
3. 明确 Dataplane B 实际加载了同一挂起 checkpoint，并从同一个工具结果继续推理，Turn ID 保持不变。
4. 分别验证 Tool Worker A 启动和 Tool Worker B 查询或接管同一个远程执行；确认稳定执行 ID 未变化，长命令没有再次启动。
5. 第一轮提交终态并 ACK，随后第二轮实际读取原产物；保存两边日志、公开事件、原生记录和消费位点。

这些步骤应按 Agent Harness Runtime 切换、Tool Worker 切换、两者共同切换分开验证，最后再测试非正常退出。

### 13.4 结构化生命周期日志

新增日志在 INFO 级别输出，消息前缀为 `managed_runtime `，随后是单行 JSON。必须使用包含此变更的构建并重启 Dataplane 和 Tool Worker；旧进程不会自动加载新代码。日志只包含标识和生命周期元数据，不包含模型上下文、工具参数、工具完整结果、凭据或 Guard token。

这轮只在 Service 增加观测，不修改 Core、Harness 接口、数据库格式或 Turn 与 Resume 协议。关联标识在异步边界显式传递，不依赖线程局部的 Session 状态。MQ 重投可能再次输出同一 checkpoint 或工具结果的提交确认，应按 checkpoint、batch 或来源事件标识关联去重，不能将日志条数当作实际工具启动次数。

| 事件 | 含义和主要关联字段 |
| --- | --- |
| `brain.consumer.started` / `hands.consumer.started` | 消费者与物理实例映射；包括 channel、Topic、消费组和子深度 |
| `brain.callback.registered` | Session、Turn、consumer、delivery、Broker 消息 ID；Resume 消息还包括挂起点和 Resume ID |
| `brain.execution.started` | 本次 epoch、消费投递与选中的 checkpoint；checkpoint 可能来自前一轮 |
| `brain.checkpoint.loaded` | 已校验并读取本 Turn 的挂起 checkpoint、加载 continuation；包括 checkpoint 的原 run、序号和恢复阶段 |
| `brain.run.started` / `brain.run.exited` | 当前 run ID、epoch、checkpoint；退出区分 `suspended`、`returned`、`aborted` |
| `brain.recovery.applied` | 恢复事实已应用并发布到原生日志通道；其持久提交仍由 Tool Worker 完成 |
| `brain.checkpoint.published` | Broker 已接受 checkpoint 批次；挂起批次包括 `pending_task_ids` 和 `suspension_id`，此时不能认定 DB 已提交 |
| `hands.checkpoint.committed` | Tool Worker 已提交 checkpoint；包括 batch、来源消息、checkpoint ID、序号、run、epoch 和终态或挂起信息 |
| `brain.execution.released` | 活动执行退出并调用 Guard 释放；`handed_off` 表示已发布交接批次，是否持久提交另看 Tool Worker 日志；构建缓存并不因此移除 |
| `brain.agent.cache.resolved` / `brain.agent.cache.closed` | 缓存解析和实际移除关闭；用 `cache_entry` 和 `logical_agent_id` 关联，关闭可能发生在活跃 Turn 之外 |
| `hands.sandbox.attached` | Session 与 Sandbox 绑定；远程 Sandbox 区分 `created` 和 `reconnected` |
| `hands.tool.execution.claimed` | worker 取得工具执行权，包含 Session、Turn、task、execution、checkpoint 序号和 `first_launch` |
| `hands.tool.start.requested` / `hands.tool.started` | 向 Sandbox 请求启动和启动句柄已记录；稳定身份重试会标记 `mode=stable_identity_retry` |
| `hands.tool.querying_existing` | 查询原 execution，并不表示重新运行长命令 |
| `hands.tool.outcome_unknown` / `hands.tool.execution.released` | 外部结果不明或本次工具 worker 退出；不要将 unknown 当作可以重跑的证明 |
| `hands.tool.result.captured` / `hands.tool.result.committed` | 原始结果已冻结、结果已持久提交；都只记录 task、execution 等元数据 |
| `hands.tool.result.notified` / `hands.tool.stored_result.reused` | 结果已投递信箱，或恢复 worker 复用已冻结结果 |
| `hands.resume.published` | 独立 Resume 已发布，关联 Turn、挂起点和挂起 epoch |
| `hands.callback.notification.published` / `brain.callback.notification.received` | 匹配原 callback 的完成或替换通知 |
| `brain.callback.returning` / `hands.callback.returning` | Agent Harness Runtime 回调返回 `SUCCESS` 或 `FAILURE`；Tool Worker 只记录失败的回调，避免逐输出批次刷日志；回调返回不是 Broker ACK 已确认的证明，ACK 完成继续看第 8 节消费位点 |

用 JSON 字段实时跟踪当前 Session：

```bash
tail -n 200 -F "$LOG_DIR/data.log" "$LOG_DIR/hands.log" \
  | sed -u -n 's/^.*managed_runtime //p' \
  | jq --unbuffered -c --arg sid "$SESSION_ID" \
      'select(.session_id==$sid) | {
        timestamp,event,instance_id,boot_id,pid,session_id,turn_id,
        run_id,epoch,checkpoint_id,checkpoint_sequence,
        task_id,execution_id,consumer_id,delivery_id,resume_id
      }'
```

查询历史时，将 `tail ...` 换为 `cat "$LOG_DIR/data.log" "$LOG_DIR/hands.log"`。跨机器先分别收集日志，再用相同字段合并分析。消费者启动、SDK 回调返回以及缓存关闭事件可能没有当前 Session 或 Turn 字段，应分别通过 `consumer_id`、`delivery_id`、`cache_entry` 与带完整作用域的记录关联，不能因为上面的 Session 过滤未显示它们而判定事件没有发生。

验证 A 到 B 恢复时，比较提交该挂起点的 Tool Worker 实例的 `hands.checkpoint.committed` 与 Dataplane B 的 `brain.checkpoint.loaded` 中的 checkpoint ID，并确认同一个 Turn 在 B 上有新的 run 和 epoch。工具接管则比较 `execution_id`，结合 `hands.tool.querying_existing`、结果提交和最终退出状态判断，不能只数启动请求日志；启动请求也可能是稳定 ID 的幂等重试。

## 14 继续已有测试和常用命令

重新打开终端时，进入 Bash 并加载已保存的变量，无需重新创建资源：

```bash
source /Users/ken/agentcore/agentcore-managed-agents/managed-agents/.dev-stack/manual-api/active.env
D="$RUN_DIR/manual-api"
API="$RUN_DIR/manual-api.sh"
```

| 目的 | 命令 |
| --- | --- |
| Turn 状态 | `"$API" status` |
| 竖排业务队列 | `"$API" queue` |
| 完整输入和 ID | `"$API" queue --full` |
| Broker 消费位点 | `"$API" mq` |
| 第一轮原始 MQ 消息 | `"$API" message` |
| 第二轮原始 MQ 消息 | `"$API" message "$TURN2"` |
| checkpoint 元数据 | `"$API" checkpoints` |
| 保存并查看最新上下文 | `"$API" context` |
| 工具执行及结果 | `"$API" tools` |
| Redis key 和 Input Stream | `"$API" redis` |
| 解码工具结果信箱 | `"$API" redis-result` |
| 最终输出 | `"$API" final` |
| 完整事件导出 | `"$API" history` |
| 已提交的补充输入 | `"$API" inputs` |
| Dataplane 日志 | `"$API" logs data` |
| Tool Worker 日志 | `"$API" logs hands` |
| 全部应用组件日志 | `"$API" logs all` |

本次本机实测已经覆盖 90 秒长工具、挂起 checkpoint、独立 Resume、第二轮排队、第二轮读取工具产物和 SSE 输出。此手册对应本地基本流程；ACS 执行和多实例故障恢复不属于这次实测范围。
