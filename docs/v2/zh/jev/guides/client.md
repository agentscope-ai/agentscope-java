---
title: "创建客户端与发起类型化判断"
---

使用 `JevClient` 调用结构化判断接口。它返回概率、选择或评分；生成用户回答仍由你配置的生成模型完成。

## 添加依赖

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-jev</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

涉及 Harness 中间件、压缩或 Agent 案例时，还需要同版本 `agentscope-harness`；扩展中的 Harness 依赖为 optional。

## 场景：检查“退款已完成”是否有证据

业务记录是“尚未发起退款”，Agent 却生成了“退款已完成”。这里把回答与记录放在同一份 state 中，询问记录是否支持回答。客户端负责发起并校验类型化请求；是否发布、修订或转人工，由上层组件根据结果决定。

## 发起判断并读取概率

```java
import io.agentscope.extensions.judge.jev.*;
import java.time.Duration;
import java.util.Map;

var client = JevClient.builder()
    .apiKey(System.getenv("TYPESAFE_API_KEY"))
    .model("jev-latest")
    .timeout(Duration.ofSeconds(5))
    .retryPolicy(new JevRetryPolicy(2, Duration.ofMillis(500)))
    .build();
var request = SystemOneRequest.builder()
    .state(Map.of("answer", "退款已完成", "record", "尚未发起退款"))
    .question("supported", new NoulQuestion(
        "Does the business record support the answer?", null))
    .build();
var pending = client.systemOne(request); // Mono<SystemOneResult>
var result = pending.block();            // 独立命令行案例可阻塞等待
var answer = (NoulAnswer) result.answers().get("supported");
System.out.println("记录支持回答的概率：" + answer.noul());
```

这里返回的数值不是退款状态，也不会改变业务记录。若需要 PASS/FAIL/INCONCLUSIVE 与阈值处理，直接使用[条件评审](/v2/zh/jev/guides/judge-api)，避免各业务重复实现分支。

在响应式服务中返回或组合 `Mono`，避免为了记录结果重复订阅。共享 state 在请求完成前不要修改。需要多个判断时，在同一个 builder 上按不同 ID 添加 `.question(...)`。

## 配置与错误

默认端点为 `https://api.typesafe.ai`，默认模型为 `jev-latest`，单次请求超时 5 秒。没有显式 key 时，依次读取 `TYPESAFE_API_KEY`、`JEV_API_KEY`。存在密钥不会自动启用中间件。

客户端向 `/v1/systemone` 发请求，对 HTTP 429、529 和 5xx 按策略退避重试；非重试错误、重试耗尽和响应校验失败抛出 `JevException`。它检查题目集合、答案类型、概率分布和 Score 等级。应用不应从缺失答案中补造成功结果。

单次 HTTP 超时与一次逻辑判断的总预算不同。多批次、重试和退避的总上限由 [JevExecution](/v2/zh/jev/guides/harness-runtime)、Judge 或对应组件设置。取消可以停止本地订阅，远端请求是否中断取决于传输实现。

## Spring Boot 配置

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-jev-spring-boot-starter</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

```yaml
agentscope:
  jev:
    api-key: ${TYPESAFE_API_KEY:}
    model: jev-latest
    timeout: 5s
    retry:
      max-retries: 2
      initial-backoff: 500ms
```

可用 `JevClientBuilderCustomizer` bean 定制构建器。Starter 配置客户端；具体 Harness 能力仍需显式注册和设置模式。

## 使用文本模型做同题对照

`JevTextBackend(model, transport)` 接收 `SystemOneRequest`，生成兼容 Chat Completions 的请求。transport 是由应用提供的 `Function<String, Mono<String>>`，负责端点、鉴权、超时和取消。

适配器严格校验 JSON、题目和分布，不补缺项、不自动归一化非法概率；文本模型自报概率需单独校准。可运行的 HTTP 对照入口见[评测指南](/v2/zh/jev/evaluation)。

## 先运行离线案例

[JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt)的 `judge` 场景展示同类退款证据检查的三种状态。按[构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios judge
```

离线案例注入预设概率，不调用 HTTP，不代表模型效果。上面的客户端代码会访问真实接口，需要显式配置密钥；接口失败应走错误处理，不能用离线结果替代真实判断。
