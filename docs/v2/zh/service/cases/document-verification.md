---
title: "将文档核验接入业务流程"
description: "通过 Session API 将业务输入、执行过程与实际交付连接起来。"
en_link: /v2/en/service/cases/document-verification
---

文档处理流水线需要核对发票 DOC-101 的抽取字段与原文是否一致。Agent 负责定位问题和证据，应用负责决定文档是否继续流转，以及哪些结果需要人工复核。

本案例使用虚构资料说明接入方式，预期结果是验收要求，并非一次已完成的业务实跑。

## 准备资料和执行目标

下载[请求样例](/examples/service/document-verification/input.json.txt)并保存为 `input.json`。样例保留了原文页码、抽取结果和规则。原文中的 4 件商品、32 美元单价与 128 美元总价应共同用于核验；发生矛盾时，结果应指出具体字段、来源页和判断依据。扫描质量不足的页面也应列为待复核，不能当作核验通过。

先配置只读核验 Agent，并明确它能够读取的资料与规则版本。下面的文本示例将小型资料快照作为消息发送；处理原始文件时，可以先上传 Session File，再用内容块引用它。OCR、文档权限和领域规则的准备仍属于接入方的业务流水线。

## 创建 Session 并提交任务

先按[应用接入指南](/v2/zh/service/service-api)准备 Agent ID 和应用凭据。下面的命令使用 Bash、curl 和 jq，并将样例资料整理为 Managed Agent 的文本消息。Service 不会因为资料中写有仓库地址、文件路径或订单编号，就自动获得相应系统的访问权限。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: document-verification-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: document-verification-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

返回 `202 Accepted` 表示任务已接收，尚不能据此判断完成。保存 Session 和 Turn ID，网络重试沿用相同 key 与请求内容；新任务才更换 key。需要查看进展时，读取 Session 的 snapshot，再从其 `as_of` 继续订阅事件。

## 接回业务应用

应用保存文档版本、抽取批次、规则版本和 Session/Turn 的关联，并将当前文档保持在待核验状态。收到结果后，先检查输出结构，再把发现的问题和原文定位放在同一界面。发现业务矛盾是一次有效核验结果，与工具无法读取文件等执行故障应分别处理。

## 验收实际交付

用已知正确、明确矛盾、缺页和无法读取的样例分别验收，检查是否给出预期问题及证据。需要结构化结果时，应验证实际 result，而不是仅根据回复中出现 JSON 字样判断成功。人工复核通过后，再由应用推进后续流程。

文件输入和下载见[文件与产物](/v2/zh/service/files)，交互与取消见[Session API](/v2/zh/service/service-api)，回调与重连见[事件与通知](/v2/zh/service/sse-events)。
