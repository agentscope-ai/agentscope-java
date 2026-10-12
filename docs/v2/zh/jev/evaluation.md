---
title: "评测自己的 Harness 与判断后端"
---

用同一组输入比较原流程、JEV 增强流程和文本模型判断，才能知道增加判断后是否改善业务结果。将判断质量、Harness 实际行为和端到端效果分别测量。

## 场景：退款拦截是否值得增加一次判断

“先查订单，未发货再退款”中，一个判断器即使拒绝了全部危险调用，也可能把所有正常退款一起拦住。应同时观察错误放行、正常调用受阻和额外等待时间，才能判断接管是否有价值。

[退款防护对比用例](/examples/jev/tool-guard/README.md.txt)提供 160 条合成数据、数据划分、标注理由、离线行为测试与 JEV/Qwen 实测记录，代码和结果集中在 `agentscope-examples/jev/benchmarks/tool-guard`。它的命令行参数与下表通用基准不同，请按其 README 运行；[功能文档末尾](/v2/zh/jev/guides/tool-guard-api#退款场景对比测试)保留简明结果。

## 选择评估对象

- **判断层**：工具适用性、分类、审核、路由标签、压缩建议是否符合金标。
- **执行层**：拒绝是否真的阻止派发，允许是否恰好执行一次，结果是否与调用配对，取消后是否停止。
- **任务层**：回答正确性、证据召回、关键事实保留、任务成功率与完整耗时。

动作标签正确不能替代浏览器任务完成；路由标签一致不能证明节省成本；压缩建议正确也不能单独证明后续答案正确。

## 固定输入与可运行入口

案例模块提供合成样本，适合学习数据格式和跑通对照管线。用于业务验收时，应补充真实业务分布的独立金标与难例。

| 方向 | 程序入口 | 固定输入 |
|---|---|---|
| 轨迹评估 | [JevTraceBenchmark](/examples/jev/source/JevTraceBenchmark.java.txt) | [trace-cases.jsonl](/examples/jev/data/trace-cases.jsonl.txt) |
| 上下文压缩 | [JevContextBenchmark](/examples/jev/source/JevContextBenchmark.java.txt) | [context-cases.jsonl](/examples/jev/data/context-cases.jsonl.txt) |
| 长任务监督 | [JevSupervisionBenchmark](/examples/jev/source/JevSupervisionBenchmark.java.txt) | [supervision-cases.jsonl](/examples/jev/data/supervision-cases.jsonl.txt) |
| 代码评审 | [JevCodeReviewBenchmark](/examples/jev/source/JevCodeReviewBenchmark.java.txt) | [review-cases.jsonl](/examples/jev/data/review-cases.jsonl.txt) |
| 检索证据 | [JevEvidenceBenchmark](/examples/jev/source/JevEvidenceBenchmark.java.txt) | [evidence-cases.jsonl](/examples/jev/data/evidence-cases.jsonl.txt) |
| 阶段路由 | [JevPhaseRoutingBenchmark](/examples/jev/source/JevPhaseRoutingBenchmark.java.txt) | [phase-routing-cases.jsonl](/examples/jev/data/phase-routing-cases.jsonl.txt) |
| 浏览器可见动作 | [JevBrowserBenchmark](/examples/jev/source/JevBrowserBenchmark.java.txt) | [browser-cases.jsonl](/examples/jev/data/browser-cases.jsonl.txt) |

这些程序回放固定判断输入，不运行完整生产任务。金标只用于判分，不发送给后端。原始数据位于 `agentscope-examples/jev/src/test/resources/jev`。

## 运行基线与同题对照

先按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`。以轨迹评估为例：

```bash
JEV_DATA="agentscope-examples/jev/src/test/resources/jev/trace-cases.jsonl"
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --baseline "$JEV_DATA" /tmp/trace-baseline.json
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --offline "$JEV_DATA" /tmp/trace-offline.json
```

所有表中程序支持 `--baseline`；只有轨迹程序提供这里的 `--offline` 合成判断模式。输出路径必须不存在，避免覆盖上次报告。

真实后端必须显式启用，模型名和端点由你选择：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --live-jev "$JEV_DATA" /tmp/trace-jev.json jev-latest
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --live-qwen "$JEV_DATA" /tmp/trace-qwen.json "$QWEN_MODEL" "$QWEN_ENDPOINT"
```

JEV 使用 `TYPESAFE_API_KEY`，Qwen 使用 `DASHSCOPE_API_KEY`；QWEN_ENDPOINT 应为账号所在区域的兼容 Chat Completions 完整端点。其他方向按表替换程序名和数据路径，参数顺序相同。真实调用会产生接口用量，离线运行不读取密钥。

## 读懂报告并校准阈值

按场景记录正确、错误、弃权、跳过和调用错误。对有金标样本的总体准确率，弃权和错误保留在分母；另报接管覆盖率和接管样本错误率，避免通过丢弃难例提高表面准确率。

区分判断的 P50/P95 与整个 Agent 的端到端 P50/P95。报告中的 token 只覆盖拿到 usage 的响应；价格或用量未知时不推算零成本。概率阈值在独立校准集选择，再在未参与调参的数据上比较；不要用模型彼此一致作为金标。

保留定义版本、实际模型版本、输入哈希、运行区域和模式。对少量样本的结论附上样本量，业务评估可增加配对置信区间、选项顺序/命名置换与并发取消检查。上线前先在 SHADOW 记录建议，再按具体用途选择是否接管。
