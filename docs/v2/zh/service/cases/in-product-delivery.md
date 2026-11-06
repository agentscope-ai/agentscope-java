---
title: "在 CRM 中生成客户方案"
description: "通过 Session API 将业务输入、执行过程与实际交付连接起来。"
en_link: /v2/en/service/cases/in-product-delivery
---

商机 OPP-104 的用户希望生成方案初稿，离开页面后再回来审阅。应用将商机版本与 Session 关联，让生成过程成为产品原有工作的一部分，而不要求用户进入另一套 Agent 控制台。

本案例使用虚构资料说明接入方式，预期结果是验收要求，并非一次已完成的业务实跑。

## 准备资料和执行目标

下载[请求样例](/examples/service/in-product-delivery/input.json.txt)并保存为 `input.json`。样例包含 80 家门店的需求，以及尚未确认的 SSO 协议、数据区域和峰值并发。要求 Agent 生成 proposal.md 和 open-questions.md，保留资料来源与版本，不代替业务人员向客户作出承诺。

首先配置能读取授权商机资料并写入工作目录的 Managed Agent。应用负责验证当前用户能否访问该商机，再把已授权资料或受控工具交给 Agent。内容需要多种专业能力时，可以在新 Session 中改用 Team；调用方仍保存同样的 Session 和 Turn 记录。

## 创建 Session 并提交任务

先按[应用接入指南](/v2/zh/service/service-api)准备 Agent ID 和应用凭据。下面的命令使用 Bash、curl 和 jq，并将样例资料整理为 Managed Agent 的文本消息。Service 不会因为资料中写有仓库地址、文件路径或订单编号，就自动获得相应系统的访问权限。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: in-product-delivery-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: in-product-delivery-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

返回 `202 Accepted` 表示任务已接收，尚不能据此判断完成。保存 Session 和 Turn ID，网络重试沿用相同 key 与请求内容；新任务才更换 key。需要查看进展时，读取 Session 的 snapshot，再从其 `as_of` 继续订阅事件。

## 接回业务应用

打开旧商机页面时，读取原 Session 的快照和事件，恢复生成进度、问题与文件。客户需求发生变化后，应提交带有新需求版本的 Turn，保留之前的结果，避免旧任务完成后覆盖新方案。文件工具写入成功只说明工作目录中存在文件；交付给用户前还需要将内容保存为可下载文件或登记 Artifact。

## 验收实际交付

核对两个文件是否可以实际取得，关键承诺是否有来源，未知信息是否仍被标明。应用在业务人员复核后才将结果写入正式交付记录；Turn 完成表示执行结束，不代表方案已获业务批准。

文件输入和下载见[文件与产物](/v2/zh/service/files)，交互与取消见[Session API](/v2/zh/service/service-api)，回调与重连见[事件与通知](/v2/zh/service/sse-events)。
