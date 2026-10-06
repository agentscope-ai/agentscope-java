---
title: "文档核验：给处理流水线增加质量检查"
description: "对照原文、抽取结果和规则提交 Job，输出有证据的问题清单。"
en_link: /v2/en/service/cases/document-verification
---

文档系统已有 OCR 和字段抽取，Agent 服务承担后续核验：检查原文、字段和规则是否一致，将问题交给复核人员。上游继续执行确定性处理，不需要把整个流水线改成 Agent。

## 1. 固定一份有问题的输入

下载[请求样例](/examples/service/document-verification/input.json.txt)为 `input.json`。虚构发票 DOC-101 / doc-v1 只有一页文本，直接内联到请求中：

| 对象 | 值 |
| --- | --- |
| 原文第 1 页 | 数量 4，单价 32.00 USD，总额 128.00 USD |
| 抽取结果 | 数量 4，单价 32，总额 **182** |
| 规则 sop-v1 | 总额等于数量乘单价；字段符合原文；不可读页面不算已核验 |

这里期望发现 total 字段错误，应为 128，并指向第 1 页。样例不需要 PDF 上传；生产中读取 PDF、大文件和页码定位，需配置受控文件工具或相应原生文件能力，公共 Job 没有因为 input 中存在 URL 就自动下载文件。

## 2. 发布核验能力

从一个 Agent 开始，指令要求覆盖全部输入页面，区分事实矛盾与无法验证，保留原始值及证据。金额运算交给确定性工具，Agent 负责结合资料解释问题。

按[发布指南](/v2/zh/service/endpoints)创建 job Endpoint `document-check`，输入 schema 接受 request、document_id、source_version、pages、extracted、rules。要求结构化结果时，执行适配器必须返回可映射的业务对象；仅在回复中输出 JSON 文本并不保证公共 result 是同一个对象。验证真实原始结果后配置 resultMapping / outputSchema。

本例的业务结果约定如下，这是**预期结果形状**，不是运行记录或平台内置字段：

```json
{
  "document_id": "DOC-101",
  "verified_pages": [1],
  "findings": [
    {"field": "total", "observed": 182, "expected": 128,
     "source_page": 1, "rule_version": "sop-v1"}
  ],
  "review_required": true
}
```

## 3. 在流水线中调用

准备 Bash、curl、jq，以及 BASE_URL、具备 invoke/read 的后端 ENDPOINT_KEY；先发布上述 Endpoint。

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/document-check/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: doc-101-doc-v1-sop-v1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

将文档版本、抽取批次、规则版本和 Invocation ID 保存在上游检查记录中。等待结果时，原文处理流程可以继续处理其他文档；需要核验通过才能流转的当前文档保持待复核。

## 4. 根据证据决定流转

查询直到 Invocation 进入终态，取 `invocation.result`，必要时下载核验报告 Artifact。应用先检查输出结构，再将 findings 与原文页定位一起展示。核验成功但 findings 非空是一次正常的“发现问题”，不应把它记成基础设施故障。

复核人员确认修改为 128 后，由业务系统生成新的抽取版本，重新提交 Job。原结果和新结果都保留；Agent 不直接覆盖原始发票。

## 5. 用反例验收

| 输入变化 | 预期行为 |
| --- | --- |
| 原始样例 | 指出 total=182 与原文、计算值 128 矛盾，定位第 1 页 |
| 将抽取 total 改为 128 | 不再产生该矛盾；不能机械复制上一次 findings |
| 删除页面正文或使文件不可读 | 明确未核验范围，不能返回“全部通过” |
| 改变规则版本 | 新调用记录新规则；同幂等键不同正文应被拒绝 |
| output schema 不符 | 公开调用失败，流水线不能按结构化成功结果消费 |
| 人工有争议 | 保留证据和复核状态，不伪造最终业务批准 |

固定样例只验证一种矛盾。生产验收还需有标注样本集，分别统计漏检、误报、无法验证比例及专家复核时间，并验证真实文件授权和大文档读取。
