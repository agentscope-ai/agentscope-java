# HTTP MCP 长工具与新会话协议测试

[English](managed-mcp-long-tool-cluster-walkthrough.en.md)

本文使用 Draft 3 新协议；先检查下方协议版本，再执行测试。

## 准备

示例隔离命名空间为 `agentscope-test`，镜像仓库由部署者指定。
HTTP MCP 服务为 `agentscope-service-long-tool-mcp`，工具为 `longwait__long_wait`；
它执行真实等待，不依赖 Sandbox、Environment Worker 或 NAS。

升级后打开测试终端：

```bash
bash agentscope-service/scripts/cluster-api-shell.sh --long-tool
api "/v1/sessions/$SESSION_ID/capabilities" | jq '{protocol_revision,extensions,features}'
```

必须看到 `agentscope-service-2026-10-19-draft3`，才继续。脚本选中手动测试 Session；
自动验收必须另建 Session，避免消耗为用户准备的空会话。

## 提交 T1 与 T2

```bash
submit_turn() {
  local key="$1" text="$2"
  jq -n --arg text "$text" '{events:[{type:"user.message",content:[{type:"text",text:$text}],agentscope_service:{intent:"new_turn"}}]}' \
    | api "/v1/sessions/$SESSION_ID/events" -X POST -H "Idempotency-Key: $key" --data-binary @-
}
submit_turn "$RUN_ID-t1" "只调用一次 longwait__long_wait，marker=$RUN_ID，seconds=90。等待真实结果并报告 marker、call_id、started_at、finished_at。不要重复调用或伪造结果。" > "$D/t1.json"
TURN1=$(jq -er '.data[0].agentscope_service.turn_id' "$D/t1.json")
submit_turn "$RUN_ID-t2" "这是排队的 T2，不调用工具，只从前一轮真实结果报告 call_id 和 finished_at。" > "$D/t2.json"
TURN2=$(jq -er '.data[0].agentscope_service.turn_id' "$D/t2.json")
save_state
api "/v1/sessions/$SESSION_ID/turns/$TURN1" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TURN2" | jq .
```

T1 应经历 accepted → preparing/running → waiting_tool → running → completed。
T1 未结清时，T2 保持 accepted；T1 完成后 T2 才运行。HTTP 200 只表示持久受理。
响应不明时使用原 key 和原正文重试。

## 挂起期间补充输入

T1 为 waiting_tool 时执行：

```bash
jq -n --arg turn "$TURN1" '{events:[{type:"user.message",content:[{type:"text",text:"最终回答也注明工具真实完成后才继续。"}],agentscope_service:{intent:"inject",turn_id:$turn}}]}' \
  | api "/v1/sessions/$SESSION_ID/events" -X POST -H "Idempotency-Key: $RUN_ID-inject" --data-binary @- > "$D/input.json"
INPUT=$(jq -er '.data[0].agentscope_service.input_id' "$D/input.json")
api "/v1/sessions/$SESSION_ID/turns/$TURN1/inputs/$INPUT" | jq .
```

输入先为 accepted，工具结果结清并 Resume 后才为 applied。它不启动另一轮推理，也不取消工具。
新 key 对关闭输入的 Turn 会收到 409；原 key 重试仍返回冻结受理回执。

## 观察与读取结果

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N --max-time 180
```

实时流只发送建连后的事件。断开不会取消执行，不能用 Last-Event-ID 续传。
恢复观察时先建新流并缓冲，再分页补历史、按 Event ID 去重。历史示例：

```bash
api "/v1/sessions/$SESSION_ID/events?limit=100&order=asc" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TURN1/output?limit=100" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TURN2/output?limit=100" | jq .
```

历史用 `page/next_page`；最终输出用 `after/next_cursor`，两者不能混用。结果读取不依赖
SSE 或 snapshot。比较两轮输出的真实 call_id 和时间，再检查工具日志只出现一次物理调用。
工具事件使用 agent.mcp_tool_use/result，Turn/Input 通知使用 agentscope_service.*。

自动验收脚本为 `agentscope-service/tests/http-mcp-long-tool/verify_session.py`。
它先核对协议版本，再执行新事件协议、独立输出查询和物理日志验证。
请在自行部署的隔离集群执行。此次回迁执行的是本地夹具测试，集群故障切换验收需在部署后另行完成。
详细范围与结果见[回迁验证记录](master-sync-20261021.md)。
