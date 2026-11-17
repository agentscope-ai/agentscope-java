---
title: Middleware
description: 在 agent 生命周期的关键位置拦截并扩展行为
en_link: /dev/en/docs/building-blocks/middleware
---

## 概述

Agent middleware 是在不修改 agent 或 model 代码的前提下，向 agent 执行流程中的关键位置注入自定义逻辑（日志、追踪、输入改写、访问控制等）的机制。

AgentScope Java 中，可以在 8 个位置上设置 hook，覆盖完整调用生命周期：流水线前的状态绑定、从外层 reply 流程一路下沉到底层模型 API 调用、最终答案边界、以及结果产生后的完成通知：

| 位置 | 类型 | 说明 |
|------|------|------|
| `onAgent` | Onion | 包裹一次完整的 reply 流程，覆盖其中所有 ReAct 轮次、工具执行与最终输出 |
| `onReasoning` | Onion | 包裹一轮 ReAct 中的推理步骤（输入组装 → 模型调用 → 流式解码） |
| `onActing` | Onion | 包裹一次工具调用的执行 |
| `onModelCall` | Onion | 包裹一次底层 `ChatModel` API 调用，最贴近模型 |
| `onSystemPrompt` | Transformer | 在每次组装 system prompt 时触发；多个 middleware 串行接力，每一个把上一个的输出再做一次变换 |
| `onAgentStateReady` | Notification | 每次调用触发一次：本次调用的 `AgentState` 就绪时——早于输入进入流水线（pre-call 钩子、记忆、推理） |
| `onFinalAnswer` | Gate | 任何内容交付前的最终答案终审；只要有 middleware 参与该点，agent 即切换到认证交付模式（见[最终答案门禁](#最终答案门禁onfinalanswer)一节） |
| `onAgentFinish` | 等待完成的通知 | 在调用结果产生后、原生 checkpoint 与 writer 释放前返回 `Mono<Void>`；保留当前调用上下文。 |

四种类型的差别：

- **Onion**（洋葱式）—— middleware 包裹下一层 handler，可以在 `next.apply(input)` 前后插入逻辑、观察中间事件流。
- **Transformer**（变换式）—— middleware 之间串成流水线，前一个的输出作为后一个的输入，不存在「内层」概念。
- **Notification**（通知式）—— 单向同步通知，没有 `next` 委托；多个 middleware 在固定的生命周期点位按序执行。
- **Gate**（门禁式）—— 按链序依次问询、对交付持有终审权。与其他类型不同，它是模式切换型扩展点：只要有 middleware 参与，实时答案内容就会被截留，直到链给出裁决。

下图展示这些 hook 在 agent 生命周期中的嵌套关系。`onSystemPrompt` 嵌入在 `onReasoning` 内部，因为它在 reasoning 步骤组装 system prompt 时被触发；`onAgentStateReady` 在本次调用的 `AgentState` 绑定到 `RuntimeContext` 之后、输入进入流水线之前触发；`onAgentFinish` 只在成功结果之后、journal 收尾边界执行：

```text
onAgent/
├── onAgentStateReady（call 级 AgentState 就绪，输入尚未进入流水线）
├── ReAct loop（每一轮）/
│   ├── onReasoning/
│   │   ├── onSystemPrompt（组装 system prompt）
│   │   └── onModelCall（模型 API 调用）
│   └── onActing（每次工具调用）
├── onFinalAnswer（最终答案出口：正常结束 / maxIters 摘要 / returnDirect 直出）
└── onAgentFinish（成功调用收尾，早于 journal checkpoint / writer 释放）
```

`onFinalAnswer` 是这些 hook 中唯一的模式切换型扩展点——其激活语义与它开启的认证交付模式见下文[最终答案门禁](#最终答案门禁onfinalanswer)一节。


<Note>

当前 `onActing` 只包裹 agent 运行时内部的工具执行；通过 external execution 在 agent 外部执行的工具不会被 `onActing` 追踪到。

</Note>


## 装备 Middleware

AgentScope 把一组 hook 装在一个 `MiddlewareBase` 实现里 —— 同一个 middleware 类可以同时实现 8 个位置中任意子集的 hook（未实现的 Onion / Transformer 位置默认 `next.apply(input)`；通知式位置默认空操作 / 立即完成；门禁式位置默认接受候选）。把实例传给 builder 的 `middlewares(...)` 即可装备：

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

与[最终答案门禁](#最终答案门禁onfinalanswer)的分工：该 middleware 服务于「无门禁、只要干净流」的 agent。注册任意门禁后它即冗余但无害——门禁守卫本就截留每一轮的实时文本、只发布认证内容，逐轮缓冲因此不再起作用。

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
| `onFinalAnswer` | `FinalAnswerInput` | `candidate: Msg`, `retryCount: int`, `cumulativeUsage: ChatUsage`, `finishReason: FinishReason` |
| `onAgentFinish` | —（直接参数） | `result: Msg`（返回 `Mono<Void>` 完成信号） |

需要替换流入下一层的字段时，构造一个新的 input record 后再调用 `next.apply(...)`。

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

### 最终答案门禁（`onFinalAnswer`）

合规审查、PII 脱敏、格式校验、兜底改写——都想在最终答案**交付之前**过目。`onFinalAnswer` 就是这道关卡：每当一个输出即将成为本次调用的最终答案，门禁 middleware 会被先行问询，并对「交付什么」持有终审权。

三个出口都会过门禁——正常结束、maxIters 之后的强制摘要、以及 returnDirect 直出——通过 `input.finishReason()` 区分（`FINAL_ANSWER` / `MAX_ITERATIONS` / `RETURN_DIRECT`）。中断类出口（HITL 挂起、acting 停止）从不过门禁。returnDirect 候选既可由入参的 finish reason 识别，也可由候选消息自身的 `TOOL_RETURN_DIRECT` 生成原因识别——信任自己工具代码的门可以按出口直接放行。

决定权完全在你：

- `FinalAnswerDecision.accept()` —— 候选成立，原样交付；
- `FinalAnswerDecision.accept(replacement)` —— 交付给定的消息：replacement 会前喂给后续门禁，若本轮被接受，则作为链产出写入记忆并交付；
- `FinalAnswerDecision.retry(msgs)` —— 候选不成立：模型的原稿加上你的反馈进入上下文，推理重跑。门禁空完成按纯 accept 计。

```java
public class ComplianceGate implements MiddlewareBase {

    @Override
    public Mono<FinalAnswerDecision> onFinalAnswer(RuntimeContext ctx, FinalAnswerInput input) {
        // 已耗尽的摘要再试很少收敛——换成兜底内容。
        if (input.finishReason() == FinishReason.MAX_ITERATIONS) {
            return Mono.just(FinalAnswerDecision.accept(fallbackMessage()));
        }
        // 工具直出通常来自你自己的工具代码——按出口跳过。
        if (input.finishReason() == FinishReason.RETURN_DIRECT) {
            return Mono.just(FinalAnswerDecision.accept());
        }
        return Mono.fromCallable(() -> checker.check(textOf(input.candidate())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> result.passed()
                        ? FinalAnswerDecision.accept()
                        : input.retryCount() >= 3
                                ? FinalAnswerDecision.accept(fallbackMessage())  // 自管预算
                                : FinalAnswerDecision.retry(reminder(result.issues())));
    }
}
```

#### 激活与认证交付

`ON_FINAL_ANSWER` 是模式切换型扩展点：只要有任意 middleware 参与该点，agent 即切换为**认证交付**。参与从不空洞——自动检测只点亮事实上实现了 `onFinalAnswer` 的类，唯一另一条路是显式声明；门就是覆写该钩子的普通 `MiddlewareBase`，参与遵循与其他扩展点相同的 `activePoints()` 契约。该钩子不接收 agent，无状态门因此是纯函数，其实例可安全服务任意多个 agent 与并发调用；跨轮状态应放在 `RuntimeContext` attributes，绝不在门禁字段里。

认证交付下，公共事件流的行为如下：

- 每一轮的实时 `TextBlock*` / `ThinkingBlock*` 事件被截留——丢弃而非缓冲，取消/出错时无物可泄漏。实时流式是有意牺牲的；用无内容的 `final_answer.reviewing` 自定义事件驱动「审查中…」占位 UI。
- returnDirect 绑定批次的**工具结果内容**同样被截留——这些输出就是最终答案候选。工具生命周期事件（`ToolResultStart` / `ToolResultEnd`）照常流出，不能短路的批次内容也照常流出。外部 returnDirect 恢复的 `ExternalExecutionResultEvent` 同样只在裁决后发布。
- 裁决后，认证消息（链产出——思考在前、文本在后）一次性投影；被否决的候选绝不进入公共流——只进入记忆供自我纠正、以及调用日志审计。
- `ModelCallEndEvent` 仍实时发出（仅用量），可能早于认证内容——文本拼装请依赖 `TextBlockEnd` / `AgentResultEvent`，不要依赖 `ModelCallEnd`。
- 无门禁的 agent 逐字节不变：实时 token 流保留，不出现门禁事件。

`final_answer.retry` 遥测事件只携带 `retryCount` 与 `finishReason`，绝不携带候选文本。`CUSTOM` 通道始终绕过守卫，因此按约定只承载叙事性进度——绝不承载答案内容。

#### 与结构化输出的交互

`call(msgs, MyClass.class)` 的出口同样过门禁，且出口的 JSON 契约始终在裁决后成立：门禁错误使整个结构化调用失败（绝不静默 fallback 重跑模型）；被接受的内容——模型自己的 payload 或门的 replacement——都会被重新按 schema 校验（破坏契约的 replacement 使调用失败）；重试轮必须重新产出结构化答案，普通文本轮绝不可能作为类型化结果收场。

#### 链式语义

多个门禁叠加时，按 `order()` 顺序（大者在前；同序按注册顺序）依次问询：

- **工作稿前喂。** 每个门收到当前工作稿：链首见模型原稿，后续门见其前驱的 replacement。
- **否决从不短路。** 每个门都会被咨询——包括触发保险丝的那一轮——本轮全部反馈按链序收集；一轮多个否决只计一次重试。重试写入的是模型**原稿**加合并反馈，并忽略 `maxIters` 重入推理。
- **死轮整体作废。** 被否决轮上后位门接受的 replacement 只前喂评审、轮末丢弃。
- **两种合法排序——不可兼得。** 头部变换 + 尾部否决：否决门认证的正是要交付的内容（最适合合规签发）。头部否决 + 尾部变换：否决门评审的是模型原话（纠错回路最干净；此时变换必须是确定性拼装，而非语义改写）。两者并存需要无收敛保证的复审循环；框架不提供。
- **失败闭合。** 门禁错误使整个调用失败——绝不静默放行。框架保险丝每次调用最多兑现 10 次重试（不可配置）：第 11 次否决以错误终止调用、零交付。优雅耗尽兜底来自门禁通过 `input.retryCount()` 自管预算。

纯观察用途（日志、指标、审计）不应实现该钩子——事件流与调用日志已经服务它们，当门用的代价是白白牺牲实时流式。若只需要压制中间轮文本而不需要裁决，请在无门 agent 上注册 [`FinalAnswerFilterMiddleware`](#finalanswerfiltermiddleware)。`input.retryCount()` 报告本次调用已兑现的重试次数（跨调用重置；同一 agent 上的并行调用各自独立计数），`input.cumulativeUsage()` 汇总每次尝试的 token 用量。

### 读取 RuntimeContext

`MiddlewareBase` 的所有 hook 都将本次 `call` / `stream` 绑定的 [`RuntimeContext`](/dev/zh/docs/building-blocks/agent#runtimecontext-per-call-上下文) 作为第二个参数直接传入——既能读会话字段，也能按类型 / 按 key 取属性，还能反向写入来给下游 hook 和 tool 传值。

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 把 user / request id 打到日志，并把 trace id 写回 context 供 tool 读取。 */
public class RequestContextMiddleware implements MiddlewareBase {

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

通知式 hook（`onAgentStateReady`、`onAgentFinish`）每次调用按 `order()` 顺序执行一遍——数值大者先执行，与洋葱链的进入方向一致：

```
middlewares = [mw1(order=2), mw2(order=1)]
// onAgentStateReady / onAgentFinish：mw1 → mw2
```

门禁 middleware（参与 `ON_FINAL_ANSWER` 者）在每个最终答案出口按同样的 `order()` 顺序依次问询；链式规则（工作稿前喂、否决不短路、反馈合并）见[门禁一节](#最终答案门禁onfinalanswer)：

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
  ├── onFinalAnswer（每个最终答案出口：正常结束 / maxIters 摘要 / returnDirect）
  └── onAgentFinish（成功调用收尾，早于 journal checkpoint / writer 释放）
```

### 扩展点参与声明（`activePoints()`）

`MiddlewareBase.activePoints()` 声明 middleware 在哪些扩展点处于激活状态。它是**参与开关**，不是对"覆写了哪些方法"的复述：

| 声明       | 方法实现 | 行为                                     |
| ---------- | -------- | ---------------------------------------- |
| 包含该点   | 已覆写   | 正常参与                                 |
| 包含该点   | 未覆写   | 调用默认实现（直通）——合法冗余           |
| 不包含该点 | 已覆写   | 方法不会被调用——有意关闭，非错误         |
| 不包含该点 | 未覆写   | 该 middleware 在此点不存在，链中不占位   |

关键语义：

- **默认按钩子事实推导。** 未覆写 `activePoints()`（返回 `null`）的 middleware，其参与集由框架按类推导、每类只算一次——依据是该类**实际实现**了哪些钩子方法：只覆写 `onReasoning` 的 middleware 就只在 `ON_REASONING` 参与，未来版本新增的扩展点同理不会卷入。未声明的 middleware 由此保持兼容，又不会被静默卷入会改变全局行为的新扩展点。
- **覆写即接管。** 一旦返回具体集合，激活集合就是返回的集合本身；忘记声明的扩展点即使覆写了方法也不会生效。
- **空集合合法。** `EnumSet.noneOf(ExtensionPoint.class)` 让 middleware 在所有扩展点失活，同时保留注册——可用作运行开关。
- **构造期固化。** agent 构建时读取一次声明；之后修改返回的集合对已构建的 agent 无效。
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

**推荐实践：** 为你实现的每个 middleware 准确定义 `activePoints()`。精确声明让框架能整体跳过未参与者——某扩展点无任何参与者时不建任何包装层（零包装、不组装 pipeline），链更短，构建期的有效执行计划也更清晰。不声明则回落到结构性自动检测（恰好是该类实现的那些钩子），因此任何时候补充声明都只会把参与范围收窄到你真正意图的集合。

继承内置 middleware 时同样适用：内置类声明的正是其挂接的扩展点，子类若覆写了新的 hook（例如给 `TaskReminderMiddleware` 增加 `onModelCall`），必须相应扩展继承到的 `activePoints()`——否则新增的 hook 会被静默跳过。

## 实用示例

### 计时 middleware

下面的 middleware 记录每次模型调用的耗时：

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;

public class TimingMiddleware implements MiddlewareBase {
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
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public class DynamicContextMiddleware implements MiddlewareBase {

    private final Supplier<String> contextFn;

    public DynamicContextMiddleware(Supplier<String> contextFn) {
        this.contextFn = contextFn;
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
import java.util.function.Function;
import reactor.core.publisher.Flux;

public class ModelFallbackMiddleware implements MiddlewareBase {

    private final Model fallback;

    public ModelFallbackMiddleware(Model fallback) {
        this.fallback = fallback;
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
import java.util.function.Function;
import reactor.core.publisher.Flux;

public class StopOnAllDeniedMiddleware implements MiddlewareBase {

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

`onAgentFinish` 按 middleware 顺序串行执行，通过 `ON_AGENT_FINISH` 参与。Harness 调用保持默认的后台记忆行为。
