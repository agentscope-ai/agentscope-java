---
title: "上下文压缩：固定样本与真实判断对照"
---

2026-09-26，本项用同一组 6 条合成会话，比较不压缩基线、JEV 与 Qwen 的成对保留建议。用例和金标由本项目编写，参考 [fast-jev-compaction 的双问题与三种动作](https://github.com/tamaratran/fast-jev-compaction/blob/e3f262a7f4d42bd8dd32ced30d26176f7cb545b0/src/compact.ts)；不是原项目发布的官方数据集，也未经过独立业务标注复核。

## 数据与设置

[固定输入](/examples/jev/evidence/context-cases.jsonl.txt) 的 SHA-256 为 `7bdda75c6d77c10b2805fd62cc2b04b8552226209c8c75d8ae2903a1f30f6af1`。

六个场景覆盖：已失效文件、被新版本替代的读取、只需保留读取路径的审计、需要精确证据、写入回执保护、尚未解决的任务。共 9 对语义候选，另有 2 对由确定性规则固定的调用。

模型共享相同 state/questions、阈值 0.2/0.8、近期保护 1 条、最大状态/请求估算 25k/30k、每批 128 题、结果前缀 100 字符、最少字符缩减 1%。每条请求预算 60 秒、串行、无重试。测试只调用 SHADOW 规划，不更新 AgentState，不写归档，不执行工具。

JEV 请求 `jev-latest`，实际返回 `jev-1.13.0`；Qwen 为 `qwen3.8-max`，使用兼容 HTTP API、JSON 输出、`temperature=0` 和 `enable_thinking=false`。文本后端使用[轨迹评估相同的严格适配器](/v2/zh/jev/guides/trace-evaluation-api#文本模型后端与固定样本)。金标不进入模型输入。

## 实测

| 项目 | JEV | Qwen |
| --- | ---: | ---: |
| 完成请求 / 有用量响应 | 6 / 6 | 6 / 6 |
| 明确动作匹配金标 / 全部语义候选 | 1 / 9（11.1%） | 7 / 9（77.8%） |
| 明确动作错误 | 0 | 0 |
| 弃权、保留整对 | 8 / 9 | 2 / 9 |
| 后端/响应错误 | 0 | 0 |
| 确定性固定项保留 | 2 / 2 | 2 / 2 |
| 有足够缩减、产生可采用建议的会话 | 0 / 6 | 2 / 6 |
| 完整规划 P50 | 256.6 ms | 1086.9 ms |
| 完整规划 P95 | 1341.5 ms | 1716.4 ms |
| 输入 token | 4006 | 3004 |
| 输出 token | 348 | 155 |

这里的“动作匹配率”将弃权计入分母：保守保留避免丢失证据，但没有形成明确的压缩决策，不能算正确判断。确定性固定项另算，不用于抬高模型准确率。

JEV 在 8 个语义候选上落入弃权区间，六条会话都没有达到采用条件；Qwen 对“仅保留读取路径”和“过期搜索结果”形成了可采用建议，另两项不确定时也保留原文。本轮结果不支持把当前 JEV 配置直接用于线上 ENFORCE，更不能因其延迟较低就认为压缩质量已经足够。

不压缩基线始终 KEEP，不调用模型。它提供零语义判断成本的保留策略，不体现压缩能力；原始结果中按金标计算的动作匹配率不能用来评价 Agent 任务完成质量。

这些数据只衡量固定轨迹上的建议与耗时。没有运行真实长任务来验证压缩率与任务成功率，也没有用独立数据校准阈值。6 条样本的 nearest-rank P95 等于最大值；两模型各跑一次，不能作为稳定延迟或统计显著性结论。未核对价格与账单，费用未计算。

## 原始证据

- [不压缩基线](/examples/jev/evidence/context-baseline-2026-09-26.json.txt)
- [JEV 建议、每对概率与用量](/examples/jev/evidence/context-jev-2026-09-26.json.txt)
- [Qwen 建议、每对自报概率与用量](/examples/jev/evidence/context-qwen-2026-09-26.json.txt)

报告保留逐场景、逐工具对的期望、动作、固定原因及概率。固定项的 1/1 是确定性占位值，不是模型响应。`INSUFFICIENT_REDUCTION` 表示本次不采用压缩；其中仍可包含明确 KEEP 或弃权 KEEP，必须结合每对 reason 解读。

## 复现

先执行[构建与 classpath 步骤](/v2/zh/jev/guides/trace-benchmark#复现命令)，再在项目根目录运行：

```bash
JEV_CP="agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)"
JEV_CONTEXT_DATA="agentscope-examples/jev/src/test/resources/jev/context-cases.jsonl"
java -cp "$JEV_CP" io.agentscope.examples.jev.JevContextBenchmark --baseline "$JEV_CONTEXT_DATA" /tmp/context-baseline.json
java -cp "$JEV_CP" io.agentscope.examples.jev.JevContextBenchmark --live-jev "$JEV_CONTEXT_DATA" /tmp/context-jev.json jev-latest
java -cp "$JEV_CP" io.agentscope.examples.jev.JevContextBenchmark --live-qwen "$JEV_CONTEXT_DATA" /tmp/context-qwen.json qwen3.8-max https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

真实模式分别需要当前 shell 的 `TYPESAFE_API_KEY`、`DASHSCOPE_API_KEY`，必须显式执行；已有输出文件不会覆盖。离线完整 Harness/归档示例见[API 与案例](/v2/zh/jev/guides/context-compaction-api#可运行离线案例)。

## 验证边界

实现验证覆盖成对操作、错误回退、存储隔离、取消、预算拒绝与并发状态保护。语义效果的下一步是采集真实长任务轨迹，独立标注哪些调用/结果仍必要，衡量关键证据保留、上下文缩减和任务成功率；先建立独立校准集，再决定是否开启接管，不用这六条样本反复调阈值后报告效果。
