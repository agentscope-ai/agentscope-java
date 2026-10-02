---
title: "应用组件API与离线运行入口"
---


状态：已实现独立应用API，均在`io.agentscope.extensions.judge.jev.application`包。这些组件由应用显式调用；除Service中的三个中间件外，不自动挂入生产Agent。

## 初始化

```java
var options = new JevExecution.Options(JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2), "app-v1", (ctx, record) -> {});
var selector = new JevCandidateSelector(client, options, 0.2, 0.8);
var judge = new JevJudge(client, Duration.ofSeconds(2));
```

依赖现有JEV扩展。client为JevClient；测试也可传`Function<SystemOneRequest, Mono<SystemOneResult>>`。示例阈值需按场景校准。Options控制selector的运行模式；Judge、DraftPipeline及监督/记忆API由应用显式调用即执行评审，不是后台自动启用的中间件。宿主的用途开关应包围完整应用流程。

`selector.select(ctx,state,question,candidates)`对候选逐个提出Noul问题，分64项批次、总预算共享，最多1024个候选。返回selected/rejected/uncertain与各项概率；uncertain不是明确不适用。selected按概率降序、同分按ID排序。关闭或错误没有有效selection；空候选无需请求。它只返回建议，不执行动作，所以SHADOW可用于离线分析，宿主决定是否采用建议。

## 离线运行

先按[Harness案例](/v2/zh/jev/guides/harness-runtime)的命令安装模块及生成`/tmp/jev-harness-classpath.txt`，再执行：

```bash
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-harness-classpath.txt)" io.agentscope.examples.jev.JevApplicationExample
```

该main依次运行客服、RAG、草稿审核、监督、上下文恢复、记忆、团队与浏览器建议，全部使用fixture，不连接模型或真实业务。真实试验需显式注入配置了密钥的JevClient；应用自身负责访问控制与数据脱敏。

[客服](/v2/zh/jev/guides/support-api) · [RAG](/v2/zh/jev/guides/rag-api) · [输出审核](/v2/zh/jev/guides/draft-pipeline-api) · [监督](/v2/zh/jev/guides/supervision-api) · [上下文](/v2/zh/jev/guides/context-planner-api) · [记忆](/v2/zh/jev/guides/memory-api) · [团队与浏览器](/v2/zh/jev/guides/team-browser-api)

源码：[JevCandidateSelector](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevCandidateSelector.java.txt) · [JevApplicationExample](/examples/jev/source/JevApplicationExample.java.txt)。
