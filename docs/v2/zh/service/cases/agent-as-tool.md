---
title: "供上层 Agent 调用的专业服务"
description: "通过 Session API 将业务输入、执行过程与实际交付连接起来。"
en_link: /v2/en/service/cases/agent-as-tool
---

采购助手希望把供应商 V-101 的资料核验交给专门的 Agent，再根据结论继续组织自己的工作。专业 Agent 可以被多个应用复用，而工具包装由上层应用提供。

本案例使用虚构资料说明接入方式，预期结果是验收要求，并非一次已完成的业务实跑。

## 准备资料和执行目标

下载[请求样例](/examples/service/agent-as-tool/input.json.txt)并保存为 `input.json`。样例中的员工数量和网页支持来自供应商自述，独立安全审查与交付 SLA 的证据尚未提供。预期结果应分别说明已确认事实、缺失证据和需要追问的问题，不批准采购，也不扩大调用方的资料访问权限。

为上层应用签发只允许使用这个专业 Agent 的凭据。工具适配器校验供应商与用户权限后，创建 Session 并提交 Turn。专业任务可能持续较久，因此工具可以先返回包含 Session ID 和 Turn ID 的任务句柄，再提供查询、答复和取消工具。

## 创建 Session 并提交任务

先按[应用接入指南](/v2/zh/service/service-api)准备 Agent ID 和应用凭据。下面的命令使用 Bash、curl 和 jq，并将样例资料整理为 Managed Agent 的文本消息。Service 不会因为资料中写有仓库地址、文件路径或订单编号，就自动获得相应系统的访问权限。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: agent-as-tool-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: agent-as-tool-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

返回 `202 Accepted` 表示任务已接收，尚不能据此判断完成。保存 Session 和 Turn ID，网络重试沿用相同 key 与请求内容；新任务才更换 key。需要查看进展时，读取 Session 的 snapshot，再从其 `as_of` 继续订阅事件。

## 接回业务应用

适配器将上层工具调用标识与 Session/Turn 持久关联。相同工具调用重试时使用原来的幂等键，并先查找已保存的任务。上层任务取消后，适配器按记录取消下游工作并确认最终状态；任意应用之间的父子取消和累计预算关系不会仅因为使用了同一个 API 就自动建立。

## 验收实际交付

用重复调用、越权供应商、缺失证据和上层取消分别验收。如果希望通过 MCP 提供这些工具，由应用实现并发布 MCP 包装；Service 的内部协作 MCP 与这个面向上层应用的工具接口用途不同。

文件输入和下载见[文件与产物](/v2/zh/service/files)，交互与取消见[Session API](/v2/zh/service/service-api)，回调与重连见[事件与通知](/v2/zh/service/sse-events)。
