---
title: "检索与固定草稿：JEV / Qwen 对照"
---

2026-09-26，在同一四项段落分类、独立重排和答案 Judge 定义上对比 OFF、JEV 与 Qwen。JEV 本批完成速度较快；Qwen 在这些人工编写的合成标签上正确数更多。首批 JEV 全部调用错误，排障后原样重跑；两份报告都保留，不能用成功复跑掩盖可用性故障。

## 数据与边界

[12 个场景](/examples/jev/evidence/evidence-cases.jsonl.txt)包含 21 个 ACL 后可见段落分类标签、11 个相关证据和 24 个答案质量标签。覆盖退款条件、提示注入、退货期限、免费方案前提、令牌轮换、无依据承诺、错误主题、租户隔离、中文政策和多诉求遗漏。数据为本项目编写，借鉴 [spring-ai-typesafe 5c469ce](https://github.com/spring-ai-community/spring-ai-typesafe/tree/5c469ce10ee45175f56a8f5882cc93d29f72f591) 的机制，不是上游官方 benchmark 或生产回放。SHA-256：

```text
0101c273eb32117dbc2dbdc3603c440d5d428252ed8a18f4c6340e5758513e55
```

- 检索：两后端得到相同候选，先 ACL，再分类/排序；评估 `suggested`，运行模式是 SHADOW，`delivered` 仍为原始授权 topK。OFF 衡量未经语义筛选的原始授权 topK。
- 答案：两后端评审相同的固定草稿、相同固定可信证据，由 supportingIds 选择；答案证据不依赖各后端的检索建议。因此可分离审核差异，但不是“后端检索→真实模型生成→审核”的端到端质量。
- 分类金标和 goldAnswer 不发给模型。supportingIds 用于组装对照证据，不作为答案标签发出。分类字段为 INCLUDED / CONFLICTING / EXCLUDED；INCONCLUSIVE、ERROR 独立统计。
- 策略固定：四项否定阈值 .2；注入/冲突接纳 .7、相关性 .45、答案证据 .55；重排最低分 0；答案 Judge 通过 .8、拒绝 .2。均未经独立校准，未根据本轮结果调参。
- JEV 请求 jev-latest，成功批次返回 jev-1.13.0。Qwen 为 qwen3.8-max，HTTP JSON 模式、temperature=0、thinking=false。文本模型自报概率与 JEV 输出并非同样校准。
- 每样本检索预算 60 秒，答案审核另有 60 秒；这是实验配置，Service 检索默认 5 秒、上限 30 秒。串行请求，无自动重试。各后端独立运行；时延包含解析与冷启动，P50/P95 用 nearest-rank，不是首 token 时间或整个 Agent 耗时。

## 有效响应批次结果

| 指标 | OFF | JEV 复跑 | Qwen |
| --- | ---: | ---: | ---: |
| 段落正确 / 错误 / 弃权 / 调用错误 | 0 / 0 / 21 / 0 | 16 / 1 / 4 / 0 | 18 / 0 / 3 / 0 |
| 正确数 / 全部 21 个标签 | 无语义判断 | 76.19% | 85.71% |
| 段落错误率 / 弃权率 | 不适用 | 4.76% / 19.05% | 0% / 14.29% |
| 相关证据命中 / 全部相关证据 | 11 / 11 | 10 / 11 | 11 / 11 |
| 建议或基线精确率 / 召回率 | 55% / 100% | 90.91% / 90.91% | 100% / 100% |
| 草稿标签正确 / 错误 / 弃权 / 调用错误 | 0 / 0 / 24 / 0 | 17 / 2 / 5 / 0 | 21 / 1 / 2 / 0 |
| 正确数 / 全部 24 个草稿标签 | 无语义判断 | 70.83% | 87.50% |
| 草稿错误率 / 弃权率 | 不适用 | 8.33% / 20.83% | 4.17% / 8.33% |
| 检索及审核组合 P50 / P95 | 无模型，不作性能对比 | 1,733.093 / 2,568.408 ms | 4,586.990 / 5,989.289 ms |
| 检索阶段 P50 / P95 | — | 1,303.145 / 2,127.346 ms | 3,713.748 / 4,899.089 ms |
| 固定草稿审核 P50 / P95 | — | 440.346 / 481.359 ms | 980.188 / 1,089.975 ms |
| 请求数 / 有已知用量响应数 | 0 / 0 | 44 / 44 | 44 / 44 |
| 输入 / 输出 token | 0 / 0 | 16,861 / 2,496 | 13,221 / 1,139 |

精确率和召回率为所有样本合并后的 micro 统计；相关集合只有 11 条，不能外推真实知识库召回。这里报告以全部标签为分母，保留弃权和错误。真实 Agent 组合的离线执行验证另见 [API 案例](/v2/zh/jev/guides/evidence-pipeline-api)，不能把两个层面的结果合成生产准确率。

## 故障批次与观察

首批 JEV 发出 33 次请求，全部返回调用错误，21 个分类标签、24 个草稿标签均为 ERROR；没有得到模型版本或用量。最小 HTTP、同一 Java 客户端和相同四题请求随后均成功；原故障未记录 HTTP 分类，原因没有确证。随后仅在 runner 增加异常类/HTTP 状态分类，不记录响应正文或凭据；样本、提示、阈值不变，原样复跑。复跑没有请求错误。首批和复跑不可视为同一轮，也不合并成一个准确率；失败批次的快速返回不算性能收益。

JEV 将只说明 basic plan 支持在线查看的段落判成 CONFLICTING，实际没有说明 offline export 能力；并对包含取消费用与期限的有效证据弃权，导致一次召回损失。Qwen 的三次分类弃权都发生在应排除段落，所以没有损失这组相关证据的召回。

答案标签存在概念边界：当前金标将“回答了期限但数字错误”和“回答发货时间又增加无依据承诺”视为 answers_question=true、grounded=false；题面 fully answer 可能让模型把正确性也纳入 answers_question。两后端都在错误期限上给出 FAIL，按既定金标计错；没有为提高分数修改标签。这些差异需要独立人工复核和更清楚的维度定义，不能直接归结为模型能力不足。

单轮、小样本、人工合成、固定草稿、未校准分布、没有生产生成器和人工盲标，均限制结论。未接入计费账单和定价版本，货币成本未知；首批失败的用量未知，表中成功批次 token 不代表包含故障与排障探针的完整账单。

## 复现

按[构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)生成 classpath，在项目根目录执行，输出文件必须不存在：

```bash
module=agentscope-examples/jev
evidence_cp="$module/target/classes:$(cat /tmp/jev-trace-cp.txt)"
evidence_data="$module/src/test/resources/jev/evidence-cases.jsonl"
java -cp "$evidence_cp" io.agentscope.examples.jev.JevEvidenceBenchmark --baseline "$evidence_data" /tmp/evidence-baseline.json
```

真实请求显式启用并读取已有 TYPESAFE_API_KEY / DASHSCOPE_API_KEY：

```bash
java -cp "$evidence_cp" io.agentscope.examples.jev.JevEvidenceBenchmark --live-jev "$evidence_data" /tmp/evidence-jev.json jev-latest
java -cp "$evidence_cp" io.agentscope.examples.jev.JevEvidenceBenchmark --live-qwen "$evidence_data" /tmp/evidence-qwen.json qwen3.8-max https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

原始记录：[基线](/examples/jev/evidence/evidence-baseline-2026-09-26.json.txt)、[JEV 首批故障](/examples/jev/evidence/evidence-jev-initial-failure-2026-09-26.json.txt)、[JEV 复跑](/examples/jev/evidence/evidence-jev-2026-09-26.json.txt)、[Qwen](/examples/jev/evidence/evidence-qwen-2026-09-26.json.txt)、[样本](/examples/jev/evidence/evidence-cases.jsonl.txt)、[runner 源码](/examples/jev/source/JevEvidenceBenchmark.java.txt)。正式站点附件与 docs/jev/evidence 保持一致。
