---
title: "应用内交付：在 CRM 中生成客户方案"
description: "从商机页面提交 Job，恢复执行进度，下载文件并复核后交付。"
en_link: /v2/en/service/cases/in-product-delivery
---

销售在商机页点击“生成方案”，应用提交客户需求和产品资料。Agent 在后台整理方案与待确认问题，用户离开后再回来仍能查看同一任务，复核后决定是否发送。这个模式也适用于营销简报、调研报告和演示材料。

本例从一个专业 Agent 开始。CRM 负责登录、商机权限、版本和发送；Service 负责持久执行、交互与产物。需要更多专业分工时再增加 Team，业务调用方式保持一致。

## 1. 准备一份固定输入

下载[请求样例](/examples/service/in-product-delivery/input.json.txt)，保存为 `input.json`。这是虚构商机 OPP-104 的完整 Job 请求，三份短资料直接包含在 input 中，不依赖另行上传或 Memory 配置。

| 来源 | 固定事实 | 应体现在交付中 |
| --- | --- | --- |
| customer / customer-v1 | 80 家门店，需要网页客服和只读订单查询；SSO、数据地域和峰值未知 | 区分已知需求与待确认问题 |
| catalog / product-v1 | 支持网页知识问答；订单查询需集成；不支持语音和离线 App | 不承诺缺失能力或准确率 |
| delivery / delivery-v1 | 建议四周 PoC，从权限和样例就绪后起算 | 不把建议写成生产上线承诺 |

## 2. 准备并发布服务

配置一个能够读取输入、写文件和发布 Artifact 的 Agent，先验证文件可以通过 Service 下载。指令要求生成 `proposal.md`、`open-questions.md`，引用来源 ID 和版本，明确未确认条件。文件生成和产物上传必须由实际工具完成。

按[发布 Endpoint](/v2/zh/service/endpoints)发布 job 模式的 `proposal`。输入 schema 应接受样例的 request、opportunity_id、revision 和 sources；输出可约定摘要和来源列表，实际文件通过 Artifact 交付。若配置 output schema，先验证运行目标的真实输出结构，再设置 resultMapping。

准备 Bash、curl、jq，设置 `BASE_URL` 和后端保存的 `ENDPOINT_KEY`，凭证至少具有 invoke、read；需要交互或取消时再加入对应 scope。

## 3. 从商机页提交

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/proposal/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: opportunity-104-revision-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

应用在自己的数据库保存“商机 OPP-104 / revision 1 → Invocation ID”。HTTP 202 表示已接单，随后查询可能仍处于执行中。网络重试沿用同一 key 和请求体；新需求版本使用新 key，保留旧版本结果。

## 4. 恢复页面与处理反馈

```bash
SNAPSHOT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID/snapshot" \
  -H "X-API-Key: $ENDPOINT_KEY")
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID/events/stream" \
  -H "X-API-Key: $ENDPOINT_KEY" --data-urlencode "after=$CURSOR"
```

页面先渲染 snapshot，再应用事件。打开旧商机时恢复原 Invocation，不重复提交。Agent 提出问题时读取 required actions，并由有权限的人答复；补充要求前检查 Invocation capabilities。完整命令见[统一服务 API](/v2/zh/service/service-api)。

方案生成不必为了未知 SSO 等条件一直等待：本例要求把它们写进问题清单。用户要求修订已完成方案时，提交新的 Job，并由 CRM 记录修订关系。

## 5. 检查交付后写回

读取 `invocation.status/result` 与 `GET /invoke/v1/invocations/{id}/artifacts`，使用返回的下载地址获取实际文件。先确认整体终态，再检查：

- 两个文件都能下载，正文包含真实内容，来源标注为 customer-v1、product-v1、delivery-v1。
- 语音和离线 App 没有被描述为现有能力，订单查询集成依赖明确。
- SSO、数据地域、峰值列为待确认；四周 PoC 有前提。
- 方案关联的商机 revision 仍有效；旧结果不会覆盖已更新的需求。

通过后由 CRM 进入“待发送”或“已复核”状态。发送客户邮件是 CRM 的独立业务动作。

## 6. 失败与回归

| 情况 | 应用处理 |
| --- | --- |
| 来源读取失败或没有实际文件 | 展示失败/缺失交付，不展示“方案已就绪” |
| 执行 partial_succeeded | 检查失败步骤及缺少文件，再决定是否接受部分输出 |
| 用户刷新、断网 | 快照与 cursor 恢复；旧 cursor 过期则重新加载快照 |
| 用户要求未支持能力 | 列出限制和待评估事项，不替客户作承诺 |
| 更新 catalog 为 product-v2 | 用新请求记录验证新来源；旧方案仍保留原来源版本 |

生产接入可把内联资料替换为获授权的业务读取工具或 Memory；实时资料要记录实际读取版本。样例和验收表是预期行为，真实模型、文件工具和 CRM 写回需在自己的环境实跑。
