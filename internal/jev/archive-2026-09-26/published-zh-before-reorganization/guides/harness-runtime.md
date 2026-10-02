---
title: "Harness公共执行API与运行模式"
---


状态：已实现`JevExecution`，三个JEV中间件共用。默认OFF；不更改独立JevJudge的PASS/FAIL契约。

```java
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "policy-v1",
    (ctx, record) -> System.out.println(record));
var middleware = JevToolSelectionMiddleware.builder(client).execution(options).build();
```

导入`io.agentscope.extensions.judge.jev.JevExecution`、`java.time.Duration`和`io.agentscope.extensions.judge.jev.example`中的中间件类；依赖仍为`agentscope-extensions-jev`。使用现有Harness builder的`middleware(middleware)`注册。

OFF不调用后端，中间件可直接跳过且不产生观察记录；SHADOW产生建议但把原输入交给next；ENFORCE根据每个用途执行选择、拒绝或路由。无候选等不需要决策的情况直接跳过。模式只能显式设置，存在环境key不会自动启用。

总预算覆盖一个逻辑决策的全部批次、客户端重试和退避；不覆盖后续Agent执行，也不保证取消阻塞HTTP。调用器必须及时返回Mono，状态与配置在订阅期间不得外部修改。

观察Record包含purpose、version、mode、status、reason、elapsed和recommendation。只记录工具名、拒绝调用ID或模型名，不包含消息、工具参数、凭据或原始错误。观察器收到当前RuntimeContext，可关联调用身份；应快速返回，数据库写入需自行排队。观察器RuntimeException被隔离，但同步阻塞观察器不享有后台调度保证。

状态为DECIDED、INCONCLUSIVE、ERROR、CANCELLED、SKIPPED；TIMEOUT和CALL_OR_RESPONSE_ERROR是故障原因码。CANCELLED只通知观察器，不生成回退结果。建议与实际执行须分别理解：SHADOW建议拒绝不代表工具已被阻断。

## 离线案例

`JevHarnessExample`同时展示OFF、SHADOW、ENFORCE下的工具选择、退款模拟检查和模型路由；不执行真实工具，不连接模型。

```bash
mvn -pl agentscope-examples/jev -am install -DskipTests
mvn -pl agentscope-examples/jev dependency:build-classpath -Dmdep.outputFile=/tmp/jev-harness-classpath.txt
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-harness-classpath.txt)" io.agentscope.examples.jev.JevHarnessExample
```

案例没有live开关；需要真实试验时显式把fake替换成`JevClient`，并独立配置密钥与预算。示例概率不代表准确率。

## API迁移

三个builder继续保留，新增`execution(options)`。旧`failOpen`配置已移除，用途策略固定为：工具选择/模型路由故障回退，执行防护接管后故障拒绝受保护调用。旧代码必须显式设置模式才能继续调用JEV。文档工程中的三个示例已改为显式ENFORCE。生产首次配置应使用SHADOW；阈值均需业务校准。

[工具选择](/v2/zh/jev/guides/tool-selection-api) · [执行前防护](/v2/zh/jev/guides/tool-guard-api) · [模型路由](/v2/zh/jev/guides/model-routing-api)

源码：[JevExecution](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/JevExecution.java.txt) · [JevHarnessExample](/examples/jev/source/JevHarnessExample.java.txt)。
