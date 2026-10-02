---
title: "轨迹评估：JEV 与 Qwen 固定输入对照"
---

本页记录 R1 在 2026-09-25 的实测。12 条人工编写的合成轨迹包含订单查询、错误/多余/遗漏工具、重复执行、无依据承诺、工具返回中的注入与答案质量。它是工程回归集，不是外部公开基准或生产抽样；参考的是 [jevals 的指标和 runner 实现](https://github.com/openlayer-ai/jevals/tree/e9fb26aff4a4410580776fb35deec85b4ad9c308)，不能把这些样本称为原项目官方测试集。

## 输入与协议

- [固定 JSONL 输入](/examples/jev/evidence/trace-cases.jsonl.txt)，SHA-256：`620db2ccafad52415ebf75461ddf737cc505b33011eeb7a7906b2190bb06ad57`。
- 12 条轨迹共 29 个有金标的语义指标项；没有标注的指标只保留结果，不参与准确率。另有 12 个确定性轨迹匹配项，单独统计。
- 两个后端共享 state、题目、归约器和阈值（fail 0.2 / pass 0.8）。金标只进入评分器，不传给模型；确定性轨迹期望也不进入语义问题的 state。
- 单条预算 60 秒，串行运行，无重试；延迟覆盖完整评估请求、解析与归约。它不包含生成 Agent 原始轨迹的耗时，不能称为整个业务 Agent 的端到端耗时。
- JEV 请求 `jev-latest`，返回版本 `jev-1.13.0`；Qwen 请求并返回 `qwen3.8-max`，兼容 HTTP API，`temperature=0`、`enable_thinking=false`，输出上限为 `min(16384, 256+64×题目数)`，JSON 模式。
- Qwen 概率为模型自报值；未校准，不具备与 JEV 概率等同的统计含义。格式错误保留为失败，不自动修复或重试。参数依据见[兼容 API](https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope)与[思考模式说明](https://www.alibabacloud.com/help/en/model-studio/deep-thinking)。

## 实测结果

| 项目 | JEV | Qwen 文本判定 |
| --- | ---: | ---: |
| 完成请求 / 有用量响应 | 12 / 12 | 12 / 12 |
| 响应契约校验失败 | 0 | 1 |
| 有金标语义项正确 / 全部 | 27 / 29（93.1%） | 25 / 29（86.2%） |
| 明确判错的金标项 | 0 | 0 |
| 弃权的金标项 | 2 | 1 |
| 因响应错误无法判分的金标项 | 0 | 3 |
| 完整评估 P50 | 272.4 ms | 2808.0 ms |
| 完整评估 P95 | 1177.2 ms | 3043.5 ms |
| 输入 token | 12473 | 9073 |
| 输出 token | 2208 | 1656 |

这次 Qwen 的 P50 约为 JEV 的 10.3 倍，P95 约 2.6 倍。样本仅 12 条，nearest-rank P95 在这里就是最大值；模型服务与网络在不同时间运行，未经重复采样。不得据此推断稳定生产差距或显著性。

Qwen 的 `wrong_tool` 样本出现 `INVALID_RESPONSE`，导致该请求的七项语义指标均无有效结果，其中三项有金标。当前报告没有保存模型原文，不能进一步断言具体格式原因。调用有已知 token 用量，已计入上表。它是一次请求失败，不是七次接口失败。

| 指标 | 金标项数 | JEV 正确 / 全部 | Qwen 正确 / 全部 |
| --- | ---: | ---: | ---: |
| tool_choice | 7 | 6 / 7 | 5 / 7 |
| used_tool_result | 5 | 5 / 5 | 4 / 5 |
| grounded | 6 | 6 / 6 | 5 / 6 |
| stayed_in_scope | 5 | 4 / 5 | 5 / 5 |
| answer_relevancy | 1 | 1 / 1 | 1 / 1 |
| completeness | 3 | 3 / 3 | 3 / 3 |
| indirect_injection | 2 | 2 / 2 | 2 / 2 |

报告按场景保留明细。JSON 汇总中的 `inconclusive/skipped/errors` 统计该指标的全部 12 条轨迹；`correct/incorrect/accuracy` 仅针对有金标项。因此不能把这些计数直接相加，解释为相同分母。上表重新筛选到有金标项。

确定性基线 `trajectory_match` 为 12 / 12，JEV/Qwen 运行中的同一确定性项也为 12 / 12。它验证执行轨迹与期望是否匹配，不具备语义判定功能，不能将其 100% 与上述语义准确率比较。

本轮没有核对账号实际计费与折扣，不推算货币成本。已中止的 Qwen 首轮（未显式指定思考模式）和一次最小连接诊断不属于这份固定运行结果，可能仍产生费用；本报告 token 不是本次账号总账单。

## 原始报告

- [确定性基线](/examples/jev/evidence/trace-baseline-2026-09-25.json.txt)
- [JEV 逐项结果及用量](/examples/jev/evidence/trace-jev-2026-09-25.json.txt)
- [Qwen 逐项结果及用量](/examples/jev/evidence/trace-qwen-2026-09-25.json.txt)

报告保留执行时间、数据哈希、实际模型版本、每条结果、用量和耗时，不保存密钥或完整原始模型响应。合成样本输入单独公开，便于复算。

## 复现命令

在项目根目录执行。先安装两个 BOM 与 JEV 模块，避免单模块构建无法解析当前版本的父级依赖管理。

```bash
mvn -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/jev -am install -DskipTests
mvn -f agentscope-examples/jev/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/jev-trace-cp.txt
JEV_CP="agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)"
JEV_DATA="agentscope-examples/jev/src/test/resources/jev/trace-cases.jsonl"
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark --baseline "$JEV_DATA" /tmp/trace-baseline.json
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark --offline "$JEV_DATA" /tmp/trace-offline.json
```

`--offline` 的判定是合成响应，只验证批量流程，不衡量模型质量。输出文件已存在时命令报错，避免覆盖之前的证据。

真实调用必须显式执行下面的模式，并在当前 shell 提供 `TYPESAFE_API_KEY` 或 `DASHSCOPE_API_KEY`；程序不打印密钥。

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark --live-jev "$JEV_DATA" /tmp/trace-jev.json jev-latest
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark --live-qwen "$JEV_DATA" /tmp/trace-qwen.json qwen3.8-max https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

Qwen 端点由调用者显式传入，应与账号区域匹配。模型别名未来可能变化，以报告中的实际版本为准。取消进程会丢失当前尚未写出的批量报告，已完成的远端请求不因此撤销。

## 后续验证

加入独立人工复核的业务金标、增加难例和多轮轨迹，使用独立数据校准各用途阈值；多次重复运行并记录部署区域和模型版本；将 Agent 生成耗时与评估附加耗时分开，再测完整业务链路。Service 的网关、事件持久化、多实例和生产数据仍需部署验收。
