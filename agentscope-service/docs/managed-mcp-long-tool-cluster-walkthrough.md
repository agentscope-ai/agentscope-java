# HTTP MCP 长工具与新会话协议测试

[English](managed-mcp-long-tool-cluster-walkthrough.en.md)

本文使用 Draft 3 新协议；先检查下方协议版本，再执行测试。
当前复查和升级入口见 [Draft 3 审计记录](../tests/session-protocol/draft3-audit.md)，
实际发布、镜像摘要和集群验收结果位于 `release/dist/deploy-draft3-audit/`。
旧版本记录 `release/dist/deploy-agencore-ma/mcp-long-tool/acceptance/report.json` 仅供历史对照。

## 准备

已有集群隔离命名空间为 `agencore-ma`，镜像仓库命名空间为 `agentcore`。
HTTP MCP 服务为 `managed-agents-long-tool-mcp`，工具为 `longwait__long_wait`；
它执行真实等待，不依赖 Sandbox、Environment Worker 或 NAS。

升级后打开测试终端：

```bash
bash managed-agents/scripts/cluster-api-shell.sh --long-tool
api "/v1/sessions/$SESSION_ID/capabilities" | jq '{protocol_revision,extensions,features}'
```

必须看到 `managed-agents-2026-10-19-draft3`，才继续。脚本选中手动测试 Session；
自动验收必须另建 Session，避免消耗为用户准备的空会话。

## 提交 T1 与 T2

```bash
submit_turn() {
  local key="$1" text="$2"
  jq -n --arg text "$text" '{events:[{type:"user.message",content:[{type:"text",text:$text}],managed_agents:{intent:"new_turn"}}]}' \
    | api "/v1/sessions/$SESSION_ID/events" -X POST -H "Idempotency-Key: $key" --data-binary @-
}
submit_turn "$RUN_ID-t1" "只调用一次 longwait__long_wait，marker=$RUN_ID，seconds=90。等待真实结果并报告 marker、call_id、started_at、finished_at。不要重复调用或伪造结果。" > "$D/t1.json"
TURN1=$(jq -er '.data[0].managed_agents.turn_id' "$D/t1.json")
submit_turn "$RUN_ID-t2" "这是排队的 T2，不调用工具，只从前一轮真实结果报告 call_id 和 finished_at。" > "$D/t2.json"
TURN2=$(jq -er '.data[0].managed_agents.turn_id' "$D/t2.json")
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
jq -n --arg turn "$TURN1" '{events:[{type:"user.message",content:[{type:"text",text:"最终回答也注明工具真实完成后才继续。"}],managed_agents:{intent:"inject",turn_id:$turn}}]}' \
  | api "/v1/sessions/$SESSION_ID/events" -X POST -H "Idempotency-Key: $RUN_ID-inject" --data-binary @- > "$D/input.json"
INPUT=$(jq -er '.data[0].managed_agents.input_id' "$D/input.json")
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
工具事件使用 agent.mcp_tool_use/result，Turn/Input 通知使用 managed_agents.*。

自动验收脚本为 `managed-agents/tests/http-mcp-long-tool/verify_session.py`。
它先核对协议版本，再执行新事件协议、独立输出查询和物理日志验证。
2026-10-19 已在双副本集群执行基线与 Pod 故障测试，详见[双副本恢复验收](../tests/http-mcp-long-tool/dual-replica-recovery.md)。
首次测试发现 Tool Worker 在途结果恢复缺口。补齐恢复扩展后的[新版本验收](../tests/http-mcp-long-tool/recovery-v1-acceptance.md)
已验证 Worker/Runtime 重启、原结果查询、Suspend/Resume 和 T2 顺序均通过。
