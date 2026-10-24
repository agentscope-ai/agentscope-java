---
title: "周期研究与批量后台任务"
description: "通过 Session API 将业务输入、执行过程与实际交付连接起来。"
en_link: /v2/en/service/cases/scheduled-research
---

每天的计划为客户 A-101 整理公开资料中的变化，并将可核对的事实与销售假设分开保存。此类任务由调度器发起，不依赖用户保持页面在线。

本案例使用虚构资料说明接入方式，预期结果是验收要求，并非一次已完成的业务实跑。

## 准备资料和执行目标

下载[请求样例](/examples/service/scheduled-research/input.json.txt)并保存为 `input.json`。样例包含 research_date、strategy_version 和已获取的来源快照。相同客户、日期和策略版本代表同一个业务批次；采集新资料或调整策略后，应明确是否产生新的任务版本。Agent 需要引用来源，不应把模型推断描述成已发生的客户事实。

准备一个能够读取授权资料的研究 Agent，让调度器为每个独立客户任务创建 Session，并用业务批次生成稳定的创建与提交幂等键。不同客户可以使用不同 Session 并行处理，应用仍需根据平台配额限制提交速度。

## 创建 Session 并提交任务

先按[应用接入指南](/v2/zh/service/service-api)准备 Agent ID 和应用凭据。下面的命令使用 Bash、curl 和 jq，并将样例资料整理为 Managed Agent 的文本消息。Service 不会因为资料中写有仓库地址、文件路径或订单编号，就自动获得相应系统的访问权限。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: scheduled-research-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: scheduled-research-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

返回 `202 Accepted` 表示任务已接收，尚不能据此判断完成。保存 Session 和 Turn ID，网络重试沿用相同 key 与请求内容；新任务才更换 key。需要查看进展时，读取 Session 的 snapshot，再从其 `as_of` 继续订阅事件。

## 接回业务应用

调度器记录哪些对象已经提交、仍在执行或需要重试，通过回调或定期查询核对结果。收到通知后先读取 Turn 的最终状态，再更新业务记录；同一个回调重复到达时不重复写入。平台 Automation 也能组织内部工作，但直接使用 Session API 的调度器仍负责自己的批次与业务补偿。

## 验收实际交付

验收应覆盖同一批次重复触发、部分对象失败、资料来源不可用和通知丢失。每个对象都应能对应到明确的 Session、Turn、输入版本与结果；研究结束后不自动联系客户，是否跟进由业务应用决定。

文件输入和下载见[文件与产物](/v2/zh/service/files)，交互与取消见[Session API](/v2/zh/service/service-api)，回调与重连见[事件与通知](/v2/zh/service/sse-events)。
