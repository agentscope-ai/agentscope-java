---
title: "阶段路由：JEV 与文本模型固定输入对照"
---

2026-09-26 使用同一候选目录、route 描述、思考等级和 .8 置信度阈值，对比 OFF、JEV 和 Qwen。JEV 在这组样本中接纳且正确的选择更多，耗时更短；主要差异是弃权数量。样本仅验证路由判断，未执行目标生成模型，不能据此宣称任务效果提升或费用下降。

## 固定输入

[12 个合成样本](/examples/jev/evidence/phase-routing-cases.jsonl.txt)包含问候、短摘要、简单计算、架构设计、安全边界、已定代码变更、精确 JSON 修改、重复失败诊断、中文设计、上下文容量、最低思考等级，以及无法用当前候选实现的图片生成。12 个 route 金标、11 个 effort 金标；无法完成的请求预期为 none，不再标注 effort。

这些标签由本项目编写，参考 [Jevonian 75e980a](https://github.com/xinyao27/jevonian/tree/75e980a8b05cf931b3f7f0de364fea905ab4ca01) 的阶段路由机制，未经独立人工盲标，不是上游官方测试集。固定 SHA-256：

```text
f1fd2a6f042663ff34c8f515c2588f0cbfa57a351878590589c583f567dc39cf
```

两个假想候选 fast / strong 的容量、等级及价格固定在 runner 中，分别对应低成本常规任务与复杂推理。这些是合成目录数据，用于判断输入，价格不是当前市场资费；真正被调用的后端是 JEV 或 Qwen 判断模型。路由金标不发给模型。工具列表为空，不测试真实工具能力或权限；这些执行边界由离线集成测试单列。

自动路由的问题包含 route 和可选 effort：多个等级时同次提问；能力筛选后只剩单一等级时直接应用。12 个样本均只发起一次判断，无自动重试。none 只有明确且达到阈值才按金标评估为正确；低置信度保留弃权，不把最高概率选项强行计入已接管结果。

- JEV 请求 jev-latest，实际返回 jev-1.13.0。
- Qwen 请求 qwen3.8-max，thinking=false、temperature=0、HTTP JSON 模式；概率是文本模型自报估计，与 JEV 置信度不保证同样校准。
- 运行模式 SHADOW，实际下游 ModelCallInput 必须与原输入是同一个对象。下游为不调用模型的空处理器；没有生成模型 token 或真实任务验收分数。
- 时延是单样本路由 hook 的读取、判断、解析及空派发总耗时，包含冷启动；不是整个 Agent、首 token 或模型推理内部耗时。样本串行，P50/P95 为 nearest-rank。两个后端各跑一轮，没有按结果调整问题、候选、阈值或金标。

## 结果

| 指标 | OFF | JEV | Qwen |
| --- | ---: | ---: | ---: |
| Route 正确 / 错误 / 弃权 / 调用错误 | 0 / 0 / 12 / 0 | 11 / 0 / 1 / 0 | 8 / 0 / 4 / 0 |
| 正确数 / 全部 12 个 route 标签 | 无语义判断 | 91.67% | 66.67% |
| Route 错误率 / 弃权率 | 不适用 | 0% / 8.33% | 0% / 33.33% |
| Effort 正确 / 错误 / 弃权 / 调用错误 | 0 / 0 / 11 / 0 | 10 / 0 / 1 / 0 | 7 / 0 / 4 / 0 |
| 正确数 / 全部 11 个 effort 标签 | 无语义判断 | 90.91% | 63.64% |
| Effort 错误率 / 弃权率 | 不适用 | 0% / 9.09% | 0% / 36.36% |
| 路由 P50 / P95 | 无模型，不作性能对比 | 485.214 / 1,342.449 ms | 1,515.032 / 1,843.683 ms |
| 请求数 / 有已知用量响应数 | 0 / 0 | 12 / 12 | 12 / 12 |
| 输入 / 输出 token | 0 / 0 | 11,477 / 802 | 7,859 / 493 |

关闭基线使用原模型，但本 runner 不调用它；表中的“无语义判断”不能解释为原流程任务失败。Effort 是按选中模型能力和最低等级处理后的配置值，也不代表供应商最终执行的思考强度。

JEV 在精确 JSON 修改上 route 置信度不足。Qwen 在问候、简单计算、已定代码变更、精确 JSON 修改上弃权。全部保留在分母；不将弃权当作错误执行，也不以零错误率描述所有请求成功接管。JSON 修改在 execute 与 utility 间有解释空间，应由独立标注进一步区分，而不是为本次分数改标签。

上下文受限和高等级下限两个样本先由代码排除 fast，二者两后端均判断正确。移除这两个样本后，其余 10 个 route 标签正确数为 JEV 9、Qwen 6；不能把约束筛选的收益全归给语义模型。

## 限制与记录

小样本、单轮、合成候选和人工金标不足以校准生产阈值。固定 .8 会因分布校准差异产生不同弃权率；未在独立数据上寻找各后端最优阈值。该批原始报告在弃权时只保存原因及判断用量，概率字段为空；之后实现补充了弃权分布保留，没有为提高分数重跑此批。

生成模型没有执行，所以没有端到端答案质量、工具任务成功率、实际切换收益、缓存命中或账单对照。候选的合成价格不用于计算“已节省成本”；表中用量仅为判断请求，货币成本未验证。真实 Agent 的工具次数、阶段粘性和 SDK 用量记录见 [API 与离线案例](/v2/zh/jev/guides/phase-routing-api)。

## 复现

按[构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)准备 classpath，在项目根目录执行，输出文件必须不存在：

```bash
module=agentscope-examples/jev
routing_cp="$module/target/classes:$(cat /tmp/jev-trace-cp.txt)"
routing_data="$module/src/test/resources/jev/phase-routing-cases.jsonl"
java -cp "$routing_cp" io.agentscope.examples.jev.JevPhaseRoutingBenchmark --baseline "$routing_data" /tmp/phase-routing-baseline.json
```

真实调用显式启用，分别读取 TYPESAFE_API_KEY 和 DASHSCOPE_API_KEY：

```bash
java -cp "$routing_cp" io.agentscope.examples.jev.JevPhaseRoutingBenchmark --live-jev "$routing_data" /tmp/phase-routing-jev.json jev-latest
java -cp "$routing_cp" io.agentscope.examples.jev.JevPhaseRoutingBenchmark --live-qwen "$routing_data" /tmp/phase-routing-qwen.json qwen3.8-max https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

原始文件：[OFF](/examples/jev/evidence/phase-routing-baseline-2026-09-26.json.txt)、[JEV](/examples/jev/evidence/phase-routing-jev-2026-09-26.json.txt)、[Qwen](/examples/jev/evidence/phase-routing-qwen-2026-09-26.json.txt)、[样本](/examples/jev/evidence/phase-routing-cases.jsonl.txt)、[runner 源码](/examples/jev/source/JevPhaseRoutingBenchmark.java.txt)。docs/jev/evidence 与正式站点附件同步保存。
