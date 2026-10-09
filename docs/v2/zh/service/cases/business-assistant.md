---
title: "在产品中提供连续交互的业务助手"
description: "通过 Session API 将业务输入、执行过程与实际交付连接起来。"
en_link: /v2/en/service/cases/business-assistant
---

用户询问订单 O-1001 为什么延迟，以及能否保证次日送达。助手需要先读取订单事实，解释已知状态，再在用户确认后创建支持工单。应用中的聊天页面与订单页面共用同一业务身份。

本案例使用虚构资料说明接入方式，预期结果是验收要求，并非一次已完成的业务实跑。

## 准备资料和执行目标

下载[请求样例](/examples/service/business-assistant/input.json.txt)并保存为 `input.json`。请求样例只包含用户问题，配套的[订单数据](/examples/service/business-assistant/orders.json.txt)提供虚构订单事实。准备只读订单查询工具和受控工单创建工具，将未知的物流承诺保留为待确认信息。工具实现负责按业务用户检查订单访问权限，不能根据提示词中出现的用户姓名直接授权。

选择 Managed Agent 作为 Session 目标，并为需要确认的写操作配置工具权限。应用按用户和订单保存 Session，在用户继续追问时向同一个 Session 提交新的 Turn，使助手能够沿用之前的对话。更换订单或业务身份时，应重新判断是否需要建立独立 Session。

## 创建 Session 并提交任务

先按[应用接入指南](/v2/zh/service/service-api)准备 Agent ID 和应用凭据。下面的命令使用 Bash、curl 和 jq，并将样例资料整理为 Managed Agent 的文本消息。Service 不会因为资料中写有仓库地址、文件路径或订单编号，就自动获得相应系统的访问权限。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: business-assistant-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: business-assistant-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

返回 `202 Accepted` 表示任务已接收，尚不能据此判断完成。保存 Session 和 Turn ID，网络重试沿用相同 key 与请求内容；新任务才更换 key。需要查看进展时，读取 Session 的 snapshot，再从其 `as_of` 继续订阅事件。

## 接回业务应用

Agent 等待确认时，页面读取 required_actions 并显示实际待执行操作。用户答复后，应用通过对应 Turn 的 actions 接口传回请求 ID 和决定；指定人员审批需要该人员的登录身份。刷新页面时恢复 Session 快照即可，不应重新提交“创建工单”的消息。

## 验收实际交付

验收时检查回答是否忠于订单数据，未确认前是否没有工单写入，确认后是否产生真实工单 ID。重复点击、断网重试和页面刷新不应创建额外 Turn；业务工具本身也需要使用业务幂等标识，避免外部系统发生重复写入。

文件输入和下载见[文件与产物](/v2/zh/service/files)，交互与取消见[Session API](/v2/zh/service/service-api)，回调与重连见[事件与通知](/v2/zh/service/sse-events)。
