---
title: Middleware
description: 在 agent 生命周期的关键位置拦截并扩展行为
en_link: /v2/en/docs/building-blocks/middleware
---

## 概述

Agent middleware 是在不修改 agent 或 model 代码的前提下，向 agent 执行流程中的关键位置注入自定义逻辑（日志、追踪、输入改写、访问控制等）的机制。

AgentScope Java 中，可以在 7 个位置上设置 hook，覆盖了从外层 reply 流程一路下沉到底层模型 API 调用的全链路：

| 位置 | 类型 | 说明 |
|------|------|------|
| `onAgent` | Onion | 包裹一次完整的 reply 流程，覆盖其中所有 ReAct 轮次、工具执行与最终输出 |
| `onReasoning` | Onion | 包裹一轮 ReAct 中的推理步骤（输入组装 → 模型调用 → 流式解码） |
| `onActing` | Onion | 包裹一次工具调用的执行 |
| `onModelCall` | Onion | 包裹一次底层 `ChatModel` API 调用，最贴近模型 |
| `onSystemPrompt` | Transformer | 在每次组装 system prompt 时触发；多个 middleware 串行接力，每一个把上一个的输出再做一次变换 |
| `onAgentStateReady` | Notification | 每次调用触发一次：本次调用的 `AgentState` 就绪时——早于输入进入流水线（pre-call 钩子、记忆、推理） |
| `onFinalAnswer` | Gate | 在最终回答交付前做最后裁决；只要有 middleware 参与，agent 即切入认证交付模式（见[最终输出门禁](#最终输出门禁onfinalanswer)章节） |

四种类型的差别：

- **Onion**（洋葱式）—— middleware 包裹下一层 handler，可以在 `next.apply(input)` 前后插入逻辑、观察中间事件流。
- **Transformer**（变换式）—— middleware 之间串成流水线，前一个的输出作为后一个的输入，不存在「内层」概念。
- **Notification**（通知式）—— 单向同步通知，没有 `next` 委托；多个 middleware 在固定的生命周期点位按序执行。
- **Gate**（门禁式）—— 按序问询，对交付什么拥有最后决定权。它与前三者不同，是副作用型扩展点：一旦有 middleware 参与，实时回答内容即被截留，直到链式裁决通过后才交付。

下图展示这些 hook 在 agent 生命周期中的嵌套关系。`onSystemPrompt` 嵌入在 `onReasoning` 内部，因为它在 reasoning 步骤组装 system prompt 时被触发；`onAgentStateReady` 在本次调用的 `AgentState` 绑定到 `RuntimeContext` 之后、输入进入流水线之前触发：

```text
onAgent/
├── onAgentStateReady（call 级 AgentState 就绪，输入尚未进入流水线）
├── ReAct loop（每一轮）/
│   ├── onReasoning/
│   │   ├── onSystemPrompt（组装 system prompt）
│   │   └── onModelCall（模型 API 调用）
│   └── onActing（每次工具调用）
└── onFinalAnswer（终局出口：正常收尾 / maxIters 摘要 / returnDirect 直出）
```

`onFinalAnswer` 是第七个扩展点，也是唯一的副作用型扩展点——其激活语义与随之开启的认证交付模式，见下文[最终输出门禁](#最终输出门禁onfinalanswer)章节。

<Note>

当前 `onActing` 只包裹 agent 运行时内部的工具执行；通过 external execution 在 agent 外部执行的工具不会被 `onActing` 追踪到。

</Note>


## 装备 Middleware

AgentScope 把一组 hook 装在一个 `MiddlewareBase` 实现里 —— 同一个 middleware 类可以同时实现 7 个位置中任意子集的 hook（未实现的 Onion / Transformer 位置默认 `next.apply(input)`；通知式位置默认不做任何事；门禁位置默认接受候选）。把实例传给 builder 的 `middlewares(...)` 即可装备：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import java.util.List;

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .sysPrompt("You are a helpful assistant.")
                .model(model)
                .toolkit(toolkit)
                .middlewares(List.of(new OtelTracingMiddleware()))
                .build();
```

`middleware(...)`（单数）也可单独添加一个；`middlewares(...)` 接受 `List<? extends MiddlewareBase>`，未实现的位置自动跳过，不产生任何调用开销。

## 内置 Middleware

### OtelTracingMiddleware

`OtelTracingMiddleware`（位于 `io.agentscope.core.tracing`）为 agent 全生命周期接入 [OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/gen-ai/) 追踪。它在 `onAgent`、`onModelCall`、`onActing` 三个位置打点，按层级生成 span：

- `invoke_agent <name>` —— 包裹整次 reply
- `chat <model>` —— 包裹每次模型 API 调用
- `execute_tool <name>` —— 包裹每次工具执行

未配置 OpenTelemetry SDK（只剩默认的 no-op provider）时，所有 hook 会直接短路到 `next.apply(input)`，几乎零开销。

默认情况下，`OtelTracingMiddleware` 从进程级 `GlobalOpenTelemetry` 实例读取配置。无参构造函数在 hook 执行时才惰性查找该实例，因此可以在注册全局 SDK 之前构造 middleware。应用如果自行导出 span，除了 AgentScope 之外还需要引入 OpenTelemetry SDK 和 OTLP exporter。使用 OpenTelemetry BOM 保持二者版本一致（下列版本与 AgentScope 当前使用的版本一致）：

```xml
<properties>
    <opentelemetry.version>1.61.0</opentelemetry.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-bom</artifactId>
            <version>${opentelemetry.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-sdk</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
</dependencies>
```

构建 agent 之前，在每个进程中只构建并注册一次 SDK。下例中的可选环境变量可保存 `Basic <base64-credentials>` 形式的值，供 Langfuse 等要求 `Authorization` header 的后端使用：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

String endpoint =
        System.getenv().getOrDefault(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");
String authorization = System.getenv("OTEL_EXPORTER_OTLP_AUTHORIZATION");

var exporterBuilder = OtlpHttpSpanExporter.builder().setEndpoint(endpoint);
if (authorization != null && !authorization.isBlank()) {
    exporterBuilder.addHeader("Authorization", authorization);
}

SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
                .addSpanProcessor(
                        BatchSpanProcessor.builder(exporterBuilder.build()).build())
                .build();

OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .buildAndRegisterGlobal();
Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .sysPrompt("You are a helpful assistant.")
                .model(model)
                .toolkit(toolkit)
                .middleware(new OtelTracingMiddleware())
                .build();
```

必须在 middleware 开始工作前注册 SDK。如果运行环境（例如 Spring Boot 的 OpenTelemetry 自动配置）已经注册了 `GlobalOpenTelemetry`，直接复用并只添加 middleware 即可。新配置不再调用已弃用的 `TracerRegistry.register(...)`。应用关闭时应关闭 `SdkTracerProvider`，让 batch processor 刷新尚未导出的 span。

如果要改用应用自己持有的 SDK，而不是进程级实例，请用 `build()`（不要用 `buildAndRegisterGlobal()`）构建 SDK，把 OTLP HTTP exporter 指向应用的 endpoint，并显式挂到 agent 上。这是一条可选的 middleware 路径：调用方拥有 SDK 的生命周期，并负责关闭 provider。生产环境的接入方式仍然是现有的 `ReActAgent.builder().middleware(...)`，不需要改 builder。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

String endpoint =
        System.getenv().getOrDefault(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");

SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
                .addSpanProcessor(
                        BatchSpanProcessor.builder(
                                        OtlpHttpSpanExporter.builder()
                                                .setEndpoint(endpoint)
                                                .build())
                                .build())
                .build();

OpenTelemetry appSdk =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .sysPrompt("You are a helpful assistant.")
                .model(model)
                .toolkit(toolkit)
                .middleware(new OtelTracingMiddleware(appSdk))
                .build();
```

传入 `appSdk` 不会替换 `GlobalOpenTelemetry`，也不会改变 `StudioManager`。`StudioManager` 在 `initialize()` 时仍会独立安装已弃用的 `TracerRegistry` 追踪。供应商插桩如果改写了 tracer 查找结果，以及 Studio 调用树缺少展开控件，都需要对照实际部署另行验证。此 middleware 决定 `onAgent`、`onModelCall`、`onActing` 的 span 记到哪一个 OpenTelemetry SDK。传入 `OpenTelemetry.noop()` 同样合法：下游链路仍会执行，且不会记录 span。

构造 `OtelTracingMiddleware`（无论是 `new OtelTracingMiddleware()` 还是 `new OtelTracingMiddleware(appSdk)`）时，第一次创建实例还会注册一个 JVM 范围的 Reactor hook：`ContextPropagationOperator.registerOnEachOperator()`。从注册那一刻起，该 hook 会在进程中任何代码组装 `Flux` 和 `Mono` operator 时对其进行包装，使父 span 在 `publishOn` / `subscribeOn` 跨线程之后仍然成立。它无法作用于注册之前已经组装好的链：提前构建、之后复用的 publisher 不会获得上下文传播，因此请在组装需要传播的管道之前先构造该 middleware。它与 span 记到哪一个 SDK 无关：应用自持的 SDK 只把 tracer 查找从 `GlobalOpenTelemetry` 隔离开，并不能避免这个全局插桩副作用。该 hook 在每个 JVM 中最多安装一次，关闭 middleware 或 SDK 也不会移除它。

每次 reply 会产出一棵嵌套 span 树，关键属性包括 agent 名称、session ID、模型名、token 数、工具名与入参等。

### TaskReminderMiddleware

`TaskReminderMiddleware`（位于 `io.agentscope.core.middleware`）与内置 `TodoTools` 配合使用，在每个 reasoning step 之前把当前 `AgentState.tasksContext` 渲染成 `<system-reminder>` 注入上下文，避免长任务期间 agent 偏离计划。

通过 builder 上的 `enableTaskList(true)` 开关与 `TodoTools` 一同启用：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new TodoTools());

ReActAgent agent =
        ReActAgent.builder()
                .name("planner")
                .sysPrompt("You plan tasks step by step.")
                .model(model)
                .toolkit(toolkit)
                .enableTaskList(true)
                .build();
```

### FinalAnswerFilterMiddleware

`FinalAnswerFilterMiddleware` 仅输出 ReAct 最终推理轮次的文本。产生工具调用的中间轮次文本会被过滤，工具事件及其他非文本事件仍会正常流式输出。

```java
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .middleware(new FinalAnswerFilterMiddleware())
                .build();
```

由于只有在未观察到工具调用时才能确定当前轮次是最终轮次，该 middleware 会将每轮文本缓冲到模型调用结束。

与[最终输出门禁](#最终输出门禁onfinalanswer)的分工：它面向未注册门禁、只想要干净输出流的 agent。注册了任何门禁后它就冗余但无害——门禁的守卫本来就不放行任何轮次的实时文本、只发布裁决后的内容，它的逐轮缓冲不再起作用。

## 自定义 Middleware

实现 `MiddlewareBase` 接口（位于 `io.agentscope.core.middleware`），只重写需要的 hook 即可，其它的不用管。

每个洋葱 hook 收到一个 `next` 函数，调用 `next.apply(input)` 进入内层逻辑；可以在调用前后插入自己的处理，或者通过 `Flux<AgentEvent>` 算子（`doOnNext` / `flatMap` / `map` 等）观察、改写中间事件流。

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 同时观察 agent / reasoning / model_call / system_prompt 四个位置。 */
public class FullObservabilityMiddleware implements MiddlewareBase {

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(
                ExtensionPoint.ON_AGENT,
                ExtensionPoint.ON_REASONING,
                ExtensionPoint.ON_MODEL_CALL,
                ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        System.out.println("[agent] start for " + agent.getName());
        return next.apply(input)
                .doOnComplete(() -> System.out.println("[agent] end for " + agent.getName()));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        System.out.println("[reasoning] start");
        return next.apply(input).doOnComplete(() -> System.out.println("[reasoning] end"));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        System.out.println("[model_call] " + input.model().getClass().getSimpleName());
        return next.apply(input).doOnComplete(() -> System.out.println("[model_call] done"));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        System.out.println("[system_prompt] length=" + currentPrompt.length());
        return Mono.just(currentPrompt);
    }
}
```

每个 hook 的 input 类型（均位于 `io.agentscope.core.middleware`）：

| Hook | Input record | 字段 |
|------|--------------|------|
| `onAgent` | `AgentInput` | `msgs: List<Msg>` |
| `onReasoning` | `ReasoningInput` | `messages: List<Msg>`, `tools: List<ToolSchema>`, `options: GenerateOptions` |
| `onActing` | `ActingInput` | `toolCalls: List<ToolUseBlock>` |
| `onModelCall` | `ModelCallInput` | `messages`, `tools`, `options`, `model: Model` |
| `onSystemPrompt` | `String` | 当前 prompt |
| `onAgentStateReady` | —（直接参数） | `state: AgentState`, `inputMessages: List<Msg>` |
| `onFinalAnswer` | `FinalAnswerInput` | `candidate: Msg`、`retryCount: int`、`cumulativeUsage: ChatUsage`、`finishReason: FinishReason` |

需要替换流入下一层的字段时，构造一个新的 input record 后再调用 `next.apply(...)`。

`onModelCall` 中的修改只影响该次模型请求，不会自动改写已保存的对话历史。对于旧版 `PostActingEvent`，`setToolResult(...)` 会更新下一轮推理读取的工具消息；显式替换的 `Msg` 优先，原始工具调用标识保持一致。`returnDirect` 仍按原有方式直接返回；替换结果不会改写已经发出的流式 delta。

完整可运行示例：`agentscope-examples/documentation/.../middleware/CustomizedMiddlewareExample.java`、`middleware/ModelCallMiddlewareExample.java`、`middleware/SystemPromptMiddlewareExample.java`。

### 状态就绪通知

`onAgentStateReady` 是通知式 hook（没有 `next`）：每次调用同步触发一次，时点在 call 级 `AgentState` 绑定到 `RuntimeContext` 之后、输入进入流水线之前。`state` 与 `ctx.getAgentState()` 是同一实例且永不为 `null`——全新会话拿到的是刚创建、上下文为空的状态。`inputMessages` 是本次调用输入的私有可变副本：就地修改对本次调用全程生效，调用方原始列表不受影响。

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 全新会话首次调用时在输入头部注入用户画像提示。 */
public class SessionBootstrapMiddleware implements MiddlewareBase {

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT_STATE_READY);
    }

    @Override
    public void onAgentStateReady(
            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> inputMessages) {
        if (state.getContext().isEmpty()) {  // 冷启动：全新会话
            inputMessages.add(0, loadUserProfilePrompt(ctx.getUserId()));
        }
    }
}
```

实现必须非阻塞：通知在本次调用的订阅线程上同步执行（流式场景可能是 Reactor 事件循环线程）。触发以每次生命周期执行（订阅）为单位——冷流重订阅会再次触发。抛出的异常原样使本次调用失败，其后的 middleware 不再执行。未覆写该 hook 的 middleware 只是执行默认空调用；固定的开销仅为每次调用一次输入列表浅拷贝，它同时使调用方列表隔离无条件成立。与其他扩展点一样，参与与否由 `activePoints()` 声明：未声明 `ExtensionPoint.ON_AGENT_STATE_READY` 的 middleware 即使覆写了该 hook 也不会被通知。生命周期窗口：通知在本次调用通过准入并完成中断注册之后、追踪包裹与错误事件链就位之前执行——通知期间发出的中断会被兑现，而抛出的异常直接到达调用方，不经过 ErrorEvent 钩子或追踪 span。

### 最终输出门禁（`onFinalAnswer`）

最终回答是一次调用产出的最后一件事——合规审核、PII 脱敏、格式校验、兜底改写都想在它**交付之前**看一眼。`onFinalAnswer` 钩子就是这个关卡：每当一个输出即将成为本次调用的最终回答，都会先询问门中间件；门对交付什么拥有最后决定权。

三个出口都会过这道门——正常终局（本轮没有待执行的工具调用）、maxIters 耗尽后的强制摘要、returnDirect 直出（整轮工具全部 returnDirect 且成功），用 `input.finishReason()` 区分（`FINAL_ANSWER` / `MAX_ITERATIONS` / `RETURN_DIRECT`）。中断类出口（HITL 挂起、acting 停止）不是最终回答，不过门。

决定权完全在开发者：

- `FinalAnswerDecision.accept()`：候选生效，按原样交付；
- `FinalAnswerDecision.accept(replacement)`：换成给定消息交付。替换稿会继续传给后位门裁决；若本轮最终通过，写入记忆并交付的都是链上最终版本——会话里保存的是替换后的内容，不是模型原稿；
- `FinalAnswerDecision.retry(msgs)`：候选作废——模型的**原稿**连同注入的反馈一起进入上下文，重新推理。问询返回空 Mono 等同于 accept——不作答视为接受。

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.FinalAnswerDecision;
import io.agentscope.core.middleware.FinalAnswerInput;
import io.agentscope.core.middleware.FinishReason;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class ComplianceGate implements MiddlewareBase {

    private static final int MAX_RETRIES = 3;  // 自己的策略：明显小于框架保险丝

    @Override
    public Mono<FinalAnswerDecision> onFinalAnswer(RuntimeContext ctx, FinalAnswerInput input) {
        // 迭代耗尽的输出不值得再重试，直接换兜底话术
        if (input.finishReason() == FinishReason.MAX_ITERATIONS) {
            return Mono.just(FinalAnswerDecision.accept(fallbackMessage()));
        }
        // 工具直出一般出自自己的工具代码，无需合规校验——按 finishReason 跳过
        if (input.finishReason() == FinishReason.RETURN_DIRECT) {
            return Mono.just(FinalAnswerDecision.accept());
        }
        return Mono.fromCallable(() -> checker.check(textOf(input.candidate())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    if (result.passed()) {
                        return FinalAnswerDecision.accept();
                    }
                    if (input.retryCount() >= MAX_RETRIES) {
                        // 体面收尾是门自己管预算的事——框架保险丝是熔断（错误终止），
                        // 绝不降级放行
                        return FinalAnswerDecision.accept(fallbackMessage());
                    }
                    return FinalAnswerDecision.retry(reminder(result.issues()));
                });
    }
}
```

#### 激活与认证交付

`ON_FINAL_ANSWER` 是第七个扩展点，且是副作用型：只要有 middleware 参与该点，agent 即切入**认证交付**模式。参与永远不是空转：自动检测只对真正实现了 `onFinalAnswer` 的类生效——写下这个钩子就是在写门逻辑；另一条激活路径是显式声明。门就是覆写了 `onFinalAnswer` 的 `MiddlewareBase`——不存在专属接口，参与方式与其他扩展点一样走 `activePoints()` 契约，并同其他各点一样推荐显式声明 `EnumSet.of(ExtensionPoint.ON_FINAL_ANSWER)`。

这个钩子不接收 agent。绑定是单向的：agent 在构造期绑定自己的 middleware，middleware 从不持有所服务的 agent。裁决函数只需要每次调用的上下文与门输入——agent 不进签名，无状态的门就是纯函数，同一个实例可安全服务任意多的 agent 与并发调用，单例复用的安全性由构造保证，而非靠纪律。跨轮状态应挂 `RuntimeContext` 属性，不放进门的字段。

认证交付模式下，公共事件流的行为如下：

- 每一轮的实时 `TextBlock*` / `ThinkingBlock*` 事件不进入公共流——直接丢弃而非缓冲，取消或出错时无内容可泄漏。这是有意放弃实时流式：截留期间用无内容的 `final_answer.reviewing` 自定义事件驱动「审查中…」占位 UI，认证内容到达后再由客户端做本地动画呈现。
- 裁决通过后，认证消息（链上最终产出——思考块在前、文本块在后）一次性投影到公共流。投影的内容、`call()` 返回值、`AgentResultEvent` 与写入记忆的是同一条消息；被否决的候选绝不进公共流——只写进记忆供模型自我纠正，并留痕于会话录制审计。
- `ModelCallEndEvent` 照常实时发出（只含 usage），可能早于认证内容到达——拼装文本以 `TextBlockEnd` / `AgentResultEvent` 为准，不要以 `ModelCallEnd` 终结一段文本。
- 未注册门禁的 agent 逐字节不变：逐 token 流照常，门禁事件不出现。

会话录制中的审计事件 `final_answer.rejected` 留存被否决草稿；`final_answer.retry` 遥测事件只带 `retryCount` 和 `finishReason`，不携带候选文本。`CUSTOM` 通道始终绕过守卫，因此约定它只承载叙事性进度，不承载回答内容。

#### 链式语义

多个门可堆叠，按 `order()` 顺序依次问询（数值大者先；同序按注册序）：

- **工作稿逐门传递**：每个门收到当前工作稿——链首门看到模型原稿，后位门看到前一位的替换产出，`input.candidate()` 就是本门裁决的对象。
- **否决不短路**：全链问完再定论，反馈按链序同轮收齐；一轮内多个否决只计 1 次重试。重试写回上下文的是模型**原稿**（绝不是门的改写）加合并反馈，并绕过 `maxIters` 重入推理。
- **被否决的轮次整体作废**：后位门在该轮接受的替换稿仍照常传递供裁决，但轮末全部丢弃——下一轮从模型的新候选重新开始。
- **两种排序纪律，不可兼得**：变换式在链首、否决式在链尾——否决门认证的正是最终交付稿（合规签收最准，但反馈可能引用变换后的文本）；否决式在链首、变换式在链尾——否决门审判的是模型原话（纠错闭环最短，但变换环节必须是开发者可信的确定性组装——脱敏、拼装、加声明——不能做语义改写）。既要审原稿、又要改终稿，需要循环重审，无收敛保证，框架不提供。
- **fail-closed**：门抛错即整个调用以错误终止，绝不静默放行。框架保险丝按调用计最多兑现 10 次重试（不可配置）：第 11 次否决将整个调用错误终止、交付物为零。体面收尾来自门自管 `input.retryCount()` 预算，不来自框架。

纯观测诉求（日志 / 指标 / 审计）不要实现这个钩子——事件流与会话录制已经覆盖它们，当门用只会白白牺牲实时流式。同理，只想压制中间轮文本、不需要裁决的，给未注册门禁的 agent 配 [`FinalAnswerFilterMiddleware`](#finalanswerfiltermiddleware)；门禁已激活时它冗余但无害。

`input.retryCount()` 是本次调用内已兑现的重试次数（首个候选为 0，跨调用清零——同一 agent 的并行调用预算互相隔离）；`input.cumulativeUsage()` 累计本次调用内所有尝试的 usage，跨重试不丢失。

### 读取 RuntimeContext

`MiddlewareBase` 的所有 hook 都将本次 `call` / `stream` 绑定的 [`RuntimeContext`](/v2/zh/docs/building-blocks/agent#runtimecontext-per-call-上下文) 作为第二个参数直接传入——既能读会话字段，也能按类型 / 按 key 取属性，还能反向写入来给下游 hook 和 tool 传值。

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 把 user / request id 打到日志，并把 trace id 写回 context 供 tool 读取。 */
public class RequestContextMiddleware implements MiddlewareBase {

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        System.out.printf(
                "[req] user=%s session=%s reqId=%s%n",
                ctx.getUserId(),
                ctx.getSessionId(),
                ctx.get("request_id"));
        ctx.put("trace_id", java.util.UUID.randomUUID().toString());  // 后续 hook / tool 可读
        return next.apply(input);
    }
}
```

注意点：

- 同一份 `RuntimeContext` 在整个 reply 内被各层 hook / tool 共享，使用线程安全的内部 map，可以安全地 `put` 写入。
- 不要把请求级状态缓存到 middleware 实例字段——一个 middleware 实例通常被多个 agent / call 复用；要么放进 `RuntimeContext`，要么用 Reactor `contextWrite`。
- 若 builder 上同时配置了全局 `toolExecutionContext`，框架在分发给 tool 时会把它合并到 per-call context 之后（per-call 优先级更高）。

### 执行顺序

Onion 类 hook（`onAgent`、`onReasoning`、`onActing`、`onModelCall`）按 `MiddlewareBase.order()` 排序——**数值越大越处于最外层**。默认值是 `1`；相同 order 的 middleware 保持其 Builder 注册顺序：

```
middlewares = [mw1(order=2), mw2(order=1)]
// 调用顺序：
// mw1 前 → mw2 前 → 内部逻辑 → mw2 后 → mw1 后
```

自定义 middleware 可覆写 `order()`，改变其相对默认优先级的位置。例如 order 为 `0` 时，会进入所有仍保持默认 order `1` 的 middleware 内层：

```java
MiddlewareBase lowerPriority = new MiddlewareBase() {
    @Override
    public int order() {
        return 0;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.noneOf(ExtensionPoint.class);  // no hooks, zero participation
    }
};
```

对于流式 / 产出事件的 hook，内层 middleware 先看到每一个 emit 出的事件：

```
mw1_pre → mw2_pre → mw2_event → mw1_event → ... → mw2_post → mw1_post
```

Transformer 类 hook（`onSystemPrompt`）—— middleware **从左到右串行接力**：

```
middlewares = [mw1, mw2]
// originalPrompt → mw1.onSystemPrompt() → mw2.onSystemPrompt() → final
```

通知式 hook（`onAgentStateReady`）每次调用按 `order()` 顺序执行一遍——数值大者先执行，与洋葱链的进入方向一致：

```
middlewares = [mw1(order=2), mw2(order=1)]
// onAgentStateReady：mw1 → mw2
```

门中间件（参与 `ON_FINAL_ANSWER` 的 middleware）在每个最终回答出口按同样的 `order()` 顺序被问询；链式规则（工作稿逐门传递、否决不短路、反馈合并）见[门禁章节](#最终输出门禁onfinalanswer)：

```
middlewares = [mw1(order=2), mw2(order=1)]
// onFinalAnswer：mw1 → mw2 → 本轮裁决
```

一次 reply 中各 hook 的整体执行顺序遵循 agent 生命周期：

```
onAgent
  ├── onAgentStateReady（状态绑定到 RuntimeContext，早于输入进入流水线）
  ├── 每一轮 ReAct：
  │     ├── onReasoning
  │     │     ├── prepare model input → onSystemPrompt
  │     │     └── onModelCall
  │     └── onActing（本轮每个工具调用一次）
  └── onFinalAnswer（终局出口：正常收尾 / maxIters 摘要 / returnDirect 直出）
```

### 何时用 `onActing`,何时用工具调用拦截器?

需要操纵事件流、门控整批工具调用、处理回合级输出 → `onActing`。需要对单次调用的最终参数做校验/改写/拒绝,且要求在不经过 agent 循环的路径(`Toolkit#callTool` 直连、子代理 Toolkit)同样生效 → [工具调用拦截器](/v2/zh/docs/building-blocks/tool#工具调用拦截器)。两者可同时使用。

### 扩展点参与声明（`activePoints()`）

**你写的每个 middleware 都应当实现 `activePoints()`，明确返回自己挂接的点集合。** 无论是业务代码、第三方库还是 AI 工具生成的 middleware，都应遵循这一实践；下面的 `null` 默认值仅为 API 兼容而保留。`MiddlewareBase.activePoints()` 是**参与开关**，不是对"覆写了哪些方法"的复述：

- 声明包含某点即参与该点；未覆写对应方法时走默认直通实现，属合法冗余。
- 声明不包含某点即在此点不存在——链中不占位，即使覆写了方法也不会被调用（有意关闭，非错误）。

关键语义：

- **声明且仅声明你挂接的点。** 每个 middleware 都应覆写 `activePoints()`，精确返回自己挂接的点集合（`EnumSet.allOf(ExtensionPoint.class)` 显式表达全点参与）。声明是权威的：agent 构建时读取一次，优先于下面的自动探测。
- **`null` 默认值仅为 API 兼容而保留——不要依赖它。** 默认实现返回 `null`，让这份契约出现之前写就的 middleware 无需修改即可继续编译和运行。当前 `null` 由自动探测解析：middleware 恰好参与其类实际实现了钩子方法的扩展点（父类继承的实现同样计入），按类计算一次并缓存。自动探测是遗留兜底，不能替代声明意图——它表达不了有意退出，也会因直通基类覆写的钩子点亮无关的点。探测行为在未来版本可能调整，新代码一律实现 `activePoints()`。
- **空集合合法。** `EnumSet.noneOf(ExtensionPoint.class)` 让 middleware 在所有扩展点失活，同时保留注册——可用作运行开关。
- **构造期固化。** agent 构建时解析一次参与集；之后修改返回的集合对已构建的 agent 无效。
- **与 `order()` 正交。** 声明只回答"是否参与"，不回答"什么顺序"——各点参与者保持既有 `order()` 语义。

```java
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.EnumSet;
import java.util.Set;

MiddlewareBase timingOnly =
        new MiddlewareBase() {
            @Override
            public Set<ExtensionPoint> activePoints() {
                // 只在 onModelCall 激活，其余扩展点全部跳过。
                return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
            }
        };
```

精确声明让链保持精简：某扩展点无任何参与者时不建任何包装层（零包装、不组装 pipeline），构建期的有效执行计划清晰可见。`middlewaresAt(point)` 只包含声明（或探测）到的参与者——绝不含在该点上没有任何逻辑的 middleware。不要因为自动探测今天能用就依赖它：探测行为在未来版本可能调整，而显式声明是稳定的契约。

继承内置 middleware 时同样适用：内置类声明的正是其挂接的扩展点，子类若覆写了新的 hook（例如给 `TaskReminderMiddleware` 增加 `onModelCall`），必须相应扩展继承到的 `activePoints()`——否则新增的 hook 永远不会被调用。

新增扩展点遵循同一条评审规则：凡是激活会在参与 middleware 之外产生副作用的点（例如改变全局事件路由），激活必须有明确的意图来源——要么事实上实现了钩子，要么显式声明；不能来自默认行为，也不能来自无关推断。`ON_FINAL_ANSWER` 是现成的范例：激活它即切入认证交付，但不会被误触——自动检测只点亮真正实现了 `onFinalAnswer` 的类（写下这个钩子就是在写门逻辑），默认的空 accept 只有显式声明才能到达。两条路径都是明确的意图表达。

## 实用示例

### 计时 middleware

下面的 middleware 记录每次模型调用的耗时：

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

public class TimingMiddleware implements MiddlewareBase {

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        long start = System.nanoTime();
        return next.apply(input)
                .doFinally(sig -> {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    System.out.println(
                            "[timing] " + agent.getName() + ": " + ms + "ms");
                });
    }
}
```

### 限速 middleware

下面的 middleware 在两次模型调用之间强制留出最小间隔：

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RateLimitMiddleware implements MiddlewareBase {

    private final long minIntervalMs;
    private final AtomicLong lastCall = new AtomicLong(0);

    public RateLimitMiddleware(Duration minInterval) {
        this.minIntervalMs = minInterval.toMillis();
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        long now = System.currentTimeMillis();
        long wait = minIntervalMs - (now - lastCall.get());
        Mono<Void> delay = wait > 0 ? Mono.delay(Duration.ofMillis(wait)).then() : Mono.empty();
        return delay.thenMany(next.apply(input))
                .doOnSubscribe(s -> lastCall.set(System.currentTimeMillis()));
    }
}
```

### 动态 system prompt middleware

下面的 middleware 在 system prompt 中注入实时上下文。也可以直接复用示例 `middleware/SystemPromptMiddlewareExample.java`：

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.MiddlewareBase;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public class DynamicContextMiddleware implements MiddlewareBase {

    private final Supplier<String> contextFn;

    public DynamicContextMiddleware(Supplier<String> contextFn) {
        this.contextFn = contextFn;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        return Mono.just(currentPrompt + "\n\n## Current Context\n" + contextFn.get());
    }
}

// 装配：
// .middlewares(List.of(new DynamicContextMiddleware(() -> "Time: " + Instant.now())))
```

### 模型回退 middleware

下面的 middleware 在主模型失败时切换到备用模型：

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

public class ModelFallbackMiddleware implements MiddlewareBase {

    private final Model fallback;

    public ModelFallbackMiddleware(Model fallback) {
        this.fallback = fallback;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .onErrorResume(err -> {
                    System.err.println("Primary model failed: " + err.getMessage()
                            + ", switching to fallback");
                    return next.apply(
                            new ModelCallInput(
                                    input.messages(),
                                    input.tools(),
                                    input.options(),
                                    fallback));
                });
    }
}
```


<Tip>

若只是简单的「主→备」回退，`ReActAgent.Builder` 直接暴露了 `fallbackModel(...)` 与 `maxRetries(...)`，无需自己写 middleware。观察切换同理：切换发生在 `onModelCall` 接缝之下，用 `ReActAgent.Builder.failoverListener(...)`，而不是写 middleware。

</Tip>


### 全部工具被拒绝时停止 agent

当用户通过 HITL 拒绝了一轮推理产出的全部工具调用时，agent 默认会继续下一轮推理（向后兼容）。如果希望在这种场景下停止 agent，可以编写一个 `onActing` middleware 观察 `AllToolsDeniedEvent` 并发出 `RequestStopEvent`：

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

public class StopOnAllDeniedMiddleware implements MiddlewareBase {

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_ACTING);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .flatMap(event -> {
                    if (event instanceof AllToolsDeniedEvent) {
                        return Flux.just(
                                event,
                                new RequestStopEvent(
                                        "All tools denied by user",
                                        GenerateReason.ALL_TOOLS_DENIED));
                    }
                    return Flux.just(event);
                });
    }
}
```

装配后，agent 在所有工具被拒绝时会立即停止，返回 `GenerateReason.ALL_TOOLS_DENIED`：

```java
ReActAgent agent =
        ReActAgent.builder()
                .name("guarded")
                .sysPrompt("...")
                .model(model)
                .toolkit(toolkit)
                .middlewares(List.of(new StopOnAllDeniedMiddleware()))
                .build();
```
