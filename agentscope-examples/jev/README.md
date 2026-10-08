# JEV 示例

所有可运行案例与基准程序位于 `agentscope-examples/jev`，包名为 `io.agentscope.examples.jev`。JEV 客户端、业务组件以及三个 Harness 中间件仍由 `agentscope-extensions-jev` 提供。

## 构建和运行

在项目根目录运行：

```bash
mvn -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/jev -am install -DskipTests
mvn -pl agentscope-examples/jev dependency:build-classpath -Dmdep.outputFile=/tmp/jev-examples-cp.txt
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-examples-cp.txt)" io.agentscope.examples.jev.JevHarnessExample
```

默认案例使用合成响应，不需要密钥。真实浏览器案例需要显式指定浏览器；基准程序的真实模型调用必须显式使用 `--live-jev`、`--live-qwen` 或 `--compare`。

## 案例目录

| 方向 | 入口 |
|---|---|
| 客服评审 | JevCustomerSupportExample |
| 场景化工具选择与路由 | `JevHarnessScenarios selection` / `routing` |
| 逐页应用与评审场景 | `JevApplicationScenarios`，可指定单个场景参数 |
| 工具选择、执行防护、模型路由 | JevHarnessExample |
| 条件性退款防护对比 | [JevToolGuardBenchmark：数据、方法与实测](benchmarks/tool-guard/README.md) |
| 应用层决策 | JevApplicationExample |
| Agent 检索与答案修订 | JevIntegrationExample |
| 轨迹评估 | JevTraceEvaluationExample / JevTraceBenchmark |
| 上下文压缩 | JevContextCompactionExample / JevContextBenchmark |
| 任务监督 | JevSupervisionExample / JevSupervisionBenchmark |
| 代码评审 | JevCodeReviewExample / JevCodeReviewBenchmark |
| 检索证据处理 | JevEvidenceExample / JevEvidenceBenchmark |
| 阶段路由 | JevPhaseRoutingExample / JevPhaseRoutingBenchmark |
| 浏览器 | JevBrowserExample / JevBrowserLocalExample / JevBrowserBenchmark |

退款防护的数据、方法和结果集中在 `benchmarks/tool-guard`；其他基准固定输入位于 `src/test/resources/jev`。各程序的参数和真实调用方式见 [JEV 文档](../../docs/v2/zh/jev/index.md)。

## 验证

```bash
mvn -pl agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev,agentscope-examples/jev test
```

依赖示例的集成测试及共享测试辅助类随示例迁入本模块，保持原有断言。其余扩展单元测试保留原位置。Service 仅在 test scope 引用本模块中的离线夹具；生产代码不依赖示例。
