---
title: "周期研究：按业务对象提交后台任务"
description: "用外部调度器提交独立 Job，管理并发、重试、回调与批次对账。"
en_link: /v2/en/service/cases/scheduled-research
---

销售运营每天研究一组客户，只把有依据的变化交给销售。调度器按客户提交独立 Job，不需要有人打开聊天窗口；业务系统保留批次进度，Service 执行每个研究任务。

## 1. 从一个客户的固定快照开始

下载[请求样例](/examples/service/scheduled-research/input.json.txt)为 `input.json`。A-101、日期 2026-10-05、策略 research-v1 都是固定测试输入：

- release 来源：虚构客户在 10 月 4 日上线网页客服。
- CRM rev-3：客户咨询过订单查询，预算和采购日期未知。
- 交付：有来源的变化摘要、明确区分的销售假设与待确认项，不自动联系客户。

内联快照让本地验收不依赖外部网站。生产任务需要获授权的数据工具，记录检索时间与来源版本，不把缺少证据写成“没有变化”。

## 2. 准备研究服务与批次账本

[发布 job Endpoint](/v2/zh/service/endpoints) `account-research`，接受 request、account_id、research_date、strategy_version、sources。指令要求区分观察事实与推断，不虚构预算、购买意愿或日期。

应用保存以下**业务记录**，这些字段不是 Service 自带的批次 API：

| 字段 | 用途 |
| --- | --- |
| batch_id、account_id、research_date、strategy_version | 标识这次研究意图 |
| attempt、idempotency_key、request_digest | 区分业务重做与网络重试 |
| invocation_id、state、last_checked_at | 跟踪已接受的调用 |
| result_version、notification_id | 防止重复写回与重复通知 |

在数据库给业务意图和 attempt 建立唯一约束。配置 Application / Endpoint 并发与 token 限制，并准备 Bash、curl、jq、BASE_URL 及后端 invoke/read 凭证。

## 3. 提交一个对象，再扩展到批次

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/account-research/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: A-101-2026-10-05-research-v1-attempt-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

多个客户分别提交，不把一个超大名单塞进无法逐对象对账的请求。提交超时且不知道是否接单时，保留原 key 和正文重试；返回 429 时按 Retry-After 退避，并限制应用调度并发。

任务已经失败后，业务决定重做应使用新 attempt 和新 key；同 key 只会重放原调用。记录失败原因再重做，避免无限循环。改变日期、来源或策略也属于新的业务意图。

## 4. 收集结果并通知

后台查询每个 Invocation；需要回调时注册 Webhook，并按原始 body 验签、按事件 ID 去重。订阅后主动查询一次状态，处理任务在订阅前已完成的情况。回调到达时重新读公开状态与结果，而非把通知本身当交付。

只有核对结果来源、研究日期和业务版本后才写回 CRM。通知使用应用自己的去重标识。用户重点查看有证据的变化、失败与待确认项，不需要接收每个工具事件。

平台 Automation 当前支持 Issue / Workflow 等动作；它不直接调用公开 Endpoint。本例外部调度器明确走 Application、Release、Invocation 链路，见[统一 API](/v2/zh/service/service-api)。

## 5. 故障与验收矩阵

| 实验 | 合格条件 |
| --- | --- |
| 同一触发投递两次 | 应用账本只保留一个 attempt，Service 返回同一个 Invocation |
| 提交后网络超时 | 相同 key 恢复调用，不漏账、不多建 |
| 单个客户失败 | 批次保留该失败，其他结果继续收集 |
| 进程重启 | 从持久账本恢复查询，不重新提交全部客户 |
| 重复 Webhook | 不重复写回或通知 |
| 修改来源日期 | 新请求可追溯；旧批次结果不被覆盖 |
| 达到预算 | 展示配额或取消状态，不把缺失报告当完成 |
| 固定样例 | 只确认网页客服上线与订单查询兴趣；预算和采购日期仍未知 |

token budget 基于已上报用量，会有延迟；实际费用需另行核对。样例未执行真实定时计划或批量生产任务，上线前测量对象数量、耗时分布、成本和重启恢复。
