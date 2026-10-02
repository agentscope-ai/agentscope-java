---
title: "Agent 集成离线组合案例"
---

# Agent 集成离线组合案例

源码：[JevIntegrationExample](../../../agentscope-examples/jev/src/main/java/io/agentscope/examples/jev/JevIntegrationExample.java)。这是实际 ReActAgent 与 Toolkit 的离线运行，使用内存 Knowledge、假聊天模型、假 JEV 调用函数；不会读取密钥或访问模型服务。

流程：查询退款条件 → search_knowledge 返回授权证据 → 原模型生成遗漏条件的草稿 → 内容检查 → Judge 拒绝草稿 → 无工具修订 → 复审后发布。随后运行两个固定评估样本，展示按场景汇总的报告。

从仓库根目录执行：

```bash
mvn -pl agentscope-examples/jev -am install -DskipTests
mvn -f agentscope-examples/jev/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/jev-integration-cp.txt
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-integration-cp.txt)" io.agentscope.examples.jev.JevIntegrationExample
```

预期最终回答保留“未使用且七天内”的退款条件；模型调用三次，其中一次仅修订文本；两个合成样本的期望标签匹配。这里的准确率只是 fixture 自检，不表示真实 JEV 效果，耗时也不是服务端延迟。

基本 API 分别见[内容护栏](content-guardrail-api.md)、[最终草稿修订](answer-refinement-api.md)、[Knowledge 适配](knowledge-adapter-api.md)、[评估接口](evaluator-api.md)。真实业务启用前需替换数据源和模型，在 SHADOW 下固定金标校准。
