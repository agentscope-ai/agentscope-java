---
title: "从故障告警生成可审查的修复"
description: "通过 Session API 将业务输入、执行过程与实际交付连接起来。"
en_link: /v2/en/service/cases/incident-to-pr
---

告警 INC-204 指出订单查询没有正确处理状态筛选和分页。研发平台归并重复告警后，创建一次修复工作，让 Agent 在指定仓库与提交上调查问题、修改代码并提供测试证据。

本案例使用虚构资料说明接入方式，预期结果是验收要求，并非一次已完成的业务实跑。

## 准备资料和执行目标

下载[请求样例](/examples/service/incident-to-pr/input.json.txt)并保存为 `input.json`。下载请求样例后，将 repository 和 base_commit 替换为练习仓库及真实提交。练习还提供 [OrderQuery.java](/examples/service/incident-to-pr/OrderQuery.java.txt)、[测试文件](/examples/service/incident-to-pr/OrderQueryTest.java.txt)和 [CI 配置](/examples/service/incident-to-pr/ci.yml.txt)。这些文件定义了先筛选再分页、保留顺序与边界参数的验收条件。

可以使用配置了代码工具的 Managed Agent，也可以使用已经接入 Runtime Host 的 Hosted Coding Agent。执行环境需要预先准备仓库访问、依赖和测试命令。如果希望把诊断、修复和审查分开，再用 Team 或 Workflow 组织执行；创建 Session 时选择相应目标即可。

## 创建 Session 并提交任务

先按[应用接入指南](/v2/zh/service/service-api)准备 Agent ID 和应用凭据。下面的命令使用 Bash、curl 和 jq，并将样例资料整理为 Managed Agent 的文本消息。Service 不会因为资料中写有仓库地址、文件路径或订单编号，就自动获得相应系统的访问权限。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: incident-to-pr-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: incident-to-pr-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

返回 `202 Accepted` 表示任务已接收，尚不能据此判断完成。保存 Session 和 Turn ID，网络重试沿用相同 key 与请求内容；新任务才更换 key。需要查看进展时，读取 Session 的 snapshot，再从其 `as_of` 继续订阅事件。

## 接回业务应用

研发平台保存告警编号、base commit、修复轮次和 Session/Turn 的关联。同一轮修复的网络重试沿用相同幂等键，新的修复意见才提交下一轮任务。后台可以订阅 Session 回调，在完成后重新读取结果并链接到研发平台，前端无需一直保持连接。

## 验收实际交付

验收时应检查实际代码差异、运行过的测试、测试输出和 PR 地址。保留原有测试并补充边界覆盖，再由仓库授权审查者决定是否合并。Agent 声称已修复或 Turn 返回 completed，都不能替代这些代码与测试证据。

文件输入和下载见[文件与产物](/v2/zh/service/files)，交互与取消见[Session API](/v2/zh/service/service-api)，回调与重连见[事件与通知](/v2/zh/service/sse-events)。
