---
title: "代码评审：JEV 与文本模型固定输入对照"
---

2026-09-26，在同一分阶段评审实现上对照关闭基线、JEV 和 Qwen。JEV 筛选响应较快，Qwen 在这组标注筛选题上更准确；但后续定位、分类和严重度判断会继续弃权或出错。两者都不能据此作为自动合并门禁。

## 固定输入与测量范围

[12 个合成样本](/examples/jev/evidence/review-cases.jsonl.txt)由本项目编写，覆盖权限检查删除与保留、SQL 拼接与参数绑定、循环边界、空列表崩溃、资源释放、公开 JSON 字段变化、重试测试缺口、注释修改、完整源码区域定位及纯删除 OLD 行号。11 个 CHANGES 和 1 个 CODEBASE 场景，共 16 个有标注风险项、8 个预期问题位置及机制。

参考 [jev-review 31f8960](https://github.com/devagrawal09/jev-review/tree/31f89602797fb7bea007f8a480bf368bf564954e) 的流程与问题维度；这些样本不是上游官方数据集、真实仓库回放或独立人工标注集。部分测试证据是描述性文本，没有执行其中代码。goldScreen / expectedFindings 不传入模型，runner 只将 input 转换为 JevReviewInput。固定文件 SHA-256：

```text
092842ee6cf45007c5d78c85a7612ee9dda32408cab282912c142274b1d8a144
```

所有语义设置固定：lowRiskThreshold=.2、screenThreshold=.7、minConfidence=.55、routeSeverity=1.5、blockingSeverity=2；最多 5 个画像、8 项追查、64 次请求，单样本总预算 120 秒。该预算用于完整实验，不是 Service 默认值；Service 默认 10 秒，上限 30 秒。

- JEV 请求 jev-latest，实际返回 jev-1.13.0。
- Qwen 请求 qwen3.8-max，关闭 thinking、temperature=0，HTTP JSON 模式，通过共同的类型化问题适配器返回结果。
- 两后端使用同一 state/questions 定义、阈值及分阶段逻辑，执行路径由各自判断决定，因此请求数不同。文本模型分布是自报估计，confidence 由适配器计算，与 JEV confidence 不保证同样校准。
- 各后端运行一轮，无自动重试，没有用结果调参后重新测量。Qwen 严重度频繁弃权，也保留原结果。
- 时延从单样本调用开始到整个评审流程返回，包含串行追查、解析、错误和冷启动；不是首 token、单次请求或整个 Agent 的耗时。各样本串行，P50/P95 采用 nearest-rank。不同后端实验独立运行，不能把跨后端墙钟并发当作同等负载保证。

## 测量结果

| 指标 | OFF 基线 | JEV | Qwen |
| --- | ---: | ---: | ---: |
| 有标注筛选项 | 16 | 16 | 16 |
| 正确 / 错误 / 弃权 / 筛选调用错误 | 0 / 0 / 16 / 0 | 11 / 0 / 5 / 0 | 16 / 0 / 0 / 0 |
| 正确数 / 全部标注项 | 0% | 68.75% | 100% |
| 预期问题形成 Finding 且位置匹配 | 0 / 8 | 3 / 8 | 2 / 8 |
| 位置与单一金标机制都匹配 | 0 / 8 | 3 / 8 | 1 / 8 |
| 总 Finding 数（含未标注维度） | 0 | 5 | 6 |
| 完整报告数 | 0 / 12 | 1 / 12 | 1 / 12 |
| 完整评审 P50 | 无模型，不作性能比较 | 617.154 ms | 11,651.959 ms |
| 完整评审 P95 | 无模型，不作性能比较 | 3,408.667 ms | 21,692.515 ms |
| 实际请求数 | 0 | 48 | 88 |
| 带可知用量的请求 | 0 | 47 / 48 | 88 / 88 |
| 已知输入 / 输出 token | 0 / 0 | 31,216 / 3,144 | 39,006 / 3,471 |
| 后续阶段请求错误 | 0 | 1 | 1 |

位置匹配统计的是已经形成 Finding 的输出，不统计只通过定位但随后严重度弃权的中间结果；因此不能把 3/8 或 2/8 单独解释成定位模型准确率。原始报告保留 followUps 及每阶段请求状态。

筛选准确率分母保留弃权与错误，未筛选或没有判断不会算正确。这里两者标注筛选项的错误率均为 0，JEV 弃权率 31.25%，Qwen 为 0；这不表示完整评审没有错误。JEV 一次文件画像请求失败，Qwen 一次兼容性机制响应不合契约，均记录在调用错误及部分报告中。JEV 失败请求用量未知，表中仅为已知合计，不代表完整账单；未接入实际计费账单和定价版本，货币成本未验证。

## 样本观察与局限

JEV 对权限弱化、SQL 注入和跨源码区域的注入形成了带证据的安全 Finding。循环末项遗漏、空列表问题、公开字段兼容性及纯删除权限检查落在筛选弃权区间。重试的测试缺口虽然筛选为高风险，后续定位返回 noMatch，未生成发现。

Qwen 标注筛选项全部判断正确，但多项后续严重度置信度不足，按统一策略停止形成 Finding；循环末项遗漏定位正确，机制选择为 state，单一金标为 condition，所以机制匹配不计正确。这类机制有重叠，应在独立人工复核后采用允许集合或多评审者标注，不能为提升当前分数修改本轮金标。安全问题的低严重度置信度也不等于风险不存在。

两者只有注释修改样本获得 complete=true。其他报告的不完整原因包括未标注维度的不确定、画像置信度、后续弃权和调用错误。未标注的 Finding 没有足够金标判定真伪，因此没有报告完整缺陷精确率或把它们一概算误报。

样本很小、以局部直接证据为主，没有跨文件依赖分析、大仓库/长文件效果、重复运行分布或生产 SLA。JEV 48 次、Qwen 88 次请求的差异同时影响完整链路耗时；表格不能推出单请求固定加速倍数。阈值保持未校准影子模式；后续真实仓库评审、独立金标、完整缺陷召回与误报率以及成本校准仍待验证。

## 运行与原始记录

先按[离线案例构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)安装模块并生成 `/tmp/jev-trace-cp.txt`。以下命令在项目根目录执行，输出文件必须不存在：

```bash
module=agentscope-examples/jev
review_cp="$module/target/classes:$(cat /tmp/jev-trace-cp.txt)"
review_data="$module/src/test/resources/jev/review-cases.jsonl"
java -cp "$review_cp" io.agentscope.examples.jev.JevCodeReviewBenchmark --baseline "$review_data" /tmp/review-baseline.json
```

显式启用真实接口后分别读取已有 TYPESAFE_API_KEY 与 DASHSCOPE_API_KEY，不在参数中传密钥：

```bash
java -cp "$review_cp" io.agentscope.examples.jev.JevCodeReviewBenchmark --live-jev "$review_data" /tmp/review-jev.json jev-latest
java -cp "$review_cp" io.agentscope.examples.jev.JevCodeReviewBenchmark --live-qwen "$review_data" /tmp/review-qwen.json qwen3.8-max https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

- [OFF 基线](/examples/jev/evidence/review-baseline-2026-09-26.json.txt)
- [JEV 完整记录](/examples/jev/evidence/review-jev-2026-09-26.json.txt)
- [Qwen 完整记录](/examples/jev/evidence/review-qwen-2026-09-26.json.txt)
- [固定样本](/examples/jev/evidence/review-cases.jsonl.txt)
- [可复现 runner 源码](/examples/jev/source/JevCodeReviewBenchmark.java.txt)

原始 JSON 同时保留于 docs/jev/evidence，正式站点使用 docs/examples/jev/evidence。结果是本次固定实现的观测，不是参考项目性能数字。
