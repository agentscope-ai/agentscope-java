---
title: "Middleware"
description: "在 agent 生命周期的关键位置拦截并扩展行为"
---

## 概述

Agent middleware 是在不修改 agent 或 model 代码的前提下，向 agent 执行流程中的关键位置注入自定义逻辑（日志、追踪、输入改写、访问控制等）的机制。

AgentScope Java 中，可以在 5 个位置上设置 hook，覆盖了从外层 reply 流程一路下沉到底层模型 API 调用的全链路：

| 位置 | 类型 | 说明 |
|------|------|------|
| `onAgent` | Onion | 包裹一次完整的 reply 流程，覆盖其中所有 ReAct 轮次、工具执行与最终输出 |
| `onReasoning` | Onion | 包裹一轮 ReAct 中的推理步骤（输入组装 → 模型调用 → 流式解码） |
| `onActing` | Onion | 包裹一次工具调用的执行 |
| `onModelCall` | Onion | 包裹一次底层 `ChatModel` API 调用，最贴近模型 |
| `onSystemPrompt` | Transformer | 在每次组装 system prompt 时触发；多个 middleware 串行接力，每一个把上一个的输出再做一次变换 |

两种类型的差别：

- **Onion**（洋葱式）—— middleware 包裹下一层 handler，可以在 `next.apply(input)` 前后插入逻辑、观察中间事件流。
- **Transformer**（变换式）—— middleware 之间串成流水线，前一个的输出作为后一个的输入，不存在「内层」概念。

下图展示这些 hook 在 agent 生命周期中的嵌套关系。`onSystemPrompt` 嵌入在 `onReasoning` 内部，因为它在 reasoning 步骤组装 system prompt 时被触发：

```text
onAgent/
└── ReAct loop（每一轮）/
    ├── onReasoning/
    │   ├── onSystemPrompt（组装 system prompt）
    │   └── onModelCall（模型 API 调用）
    └── onActing（每次工具调用）
```

:::{note}
当前 `onActing` 只包裹 agent 运行时内部的工具执行；通过 external execution 在 agent 外部执行的工具不会被 `onActing` 追踪到。
:::

## 装备 Middleware

AgentScope 把一组 hook 装在一个 `MiddlewareBase` 实现里 —— 同一个 middleware 类可以同时实现 5 个位置中任意子集的 hook（其余位置默认 `next.apply(input)`）。把实例传给 builder 的 `middlewares(...)` 即可装备：

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

`middleware(...)`（单数）也可单独追加一个；`middlewares(...)` 接受 `List<? extends MiddlewareBase>`，未实现的位置自动跳过，不产生任何调用开销。

## 内置 Middleware

### OtelTracingMiddleware

`OtelTracingMiddleware`（位于 `io.agentscope.core.tracing`）为 agent 全生命周期接入 [OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/gen-ai/) 追踪。它在 `onAgent`、`onModelCall`、`onActing` 三个位置打点，按层级生成 span：

- `invoke_agent <name>` —— 包裹整次 reply
- `chat <model>` —— 包裹每次模型 API 调用
- `execute_tool <name>` —— 包裹每次工具执行

未配置 OpenTelemetry SDK（只剩默认的 no-op provider）时，所有 hook 会直接短路到 `next.apply(input)`，几乎零开销。

使用前先在进程中初始化 OpenTelemetry SDK（OTLP exporter、`SdkTracerProvider`、`OpenTelemetrySdk.builder().setTracerProvider(...).buildAndRegisterGlobal()`），随后把 `OtelTracingMiddleware` 装到 agent 上即可：

```java
import io.agentscope.core.ReActAgent;
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
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 同时观察 agent / reasoning / model_call / system_prompt 四个位置。 */
public class FullObservabilityMiddleware implements MiddlewareBase {

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

需要替换流入下一层的字段时，构造一个新的 input record 后再调用 `next.apply(...)`。

完整可运行示例：`agentscope-examples/documentation/.../middleware/CustomizedMiddlewareExample.java`、`middleware/ModelCallMiddlewareExample.java`、`middleware/SystemPromptMiddlewareExample.java`。

### 读取 RuntimeContext

`MiddlewareBase` 的所有 hook 都将本次 `call` / `stream` 绑定的 [`RuntimeContext`](./agent.md#runtimecontext-per-call-上下文) 作为第二个参数直接传入——既能读会话字段，也能按类型 / 按 key 取属性，还能反向写入来给下游 hook 和 tool 传值。

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

Onion 类 hook（`onAgent`、`onReasoning`、`onActing`、`onModelCall`）—— **列表中第一个 middleware 处于最外层**：

```
middlewares = [mw1, mw2]
// 调用顺序：
// mw1 前 → mw2 前 → 内部逻辑 → mw2 后 → mw1 后
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

一次 reply 中各 hook 的整体执行顺序遵循 agent 生命周期：

```
onAgent
  └── 每一轮 ReAct：
        ├── onReasoning
        │     ├── prepare model input → onSystemPrompt
        │     └── onModelCall
        └── onActing（本轮每个工具调用一次）
```

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

:::{tip}
若只是简单的「主→备」回退，`ReActAgent.Builder` 直接暴露了 `fallbackModel(...)` 与 `maxRetries(...)`，无需自己写 middleware。
:::

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
