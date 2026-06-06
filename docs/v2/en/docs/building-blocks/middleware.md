---
title: "Middleware"
description: "Intercept and extend agent behavior at key lifecycle points"
---

## Overview

Agent middleware lets you inject custom logic (logging, tracing, input rewriting, access control, …) at key points in an agent's execution flow without modifying the agent or model code.

In AgentScope Java, you can hook into 5 places — covering everything from the outer reply flow down to the raw model API call:

| Position | Type | Description |
|----------|------|-------------|
| `onAgent` | Onion | Wraps a full reply flow, covering all ReAct rounds, tool execution, and the final output |
| `onReasoning` | Onion | Wraps one reasoning step in the ReAct loop (input assembly → model call → streaming decode) |
| `onActing` | Onion | Wraps the execution of a single tool call |
| `onModelCall` | Onion | Wraps a raw `ChatModel` API call — closest to the model |
| `onSystemPrompt` | Transformer | Triggers when the system prompt is assembled; multiple middlewares run in sequence, each transforming the previous output |

The two types differ:

- **Onion** — middleware wraps the next handler; you can insert logic before/after `next.apply(input)` and observe the intermediate event stream.
- **Transformer** — middlewares form a pipeline; the previous output is the next input. There's no "inner layer" concept.

The diagram below shows how the hooks nest in the agent lifecycle. `onSystemPrompt` is nested inside `onReasoning` because it fires when the reasoning step assembles the system prompt:

```text
onAgent/
└── ReAct loop (per round)/
    ├── onReasoning/
    │   ├── onSystemPrompt (assemble system prompt)
    │   └── onModelCall (model API call)
    └── onActing (per tool call)
```

:::{note}
`onActing` only wraps tool executions inside the agent runtime. Tools executed outside the agent via external execution are not tracked by `onActing`.
:::

## Equipping middleware

AgentScope packs a set of hooks into a single `MiddlewareBase` implementation — one middleware class can implement any subset of the 5 hooks (the rest default to `next.apply(input)`). Pass the instances to the builder's `middlewares(...)`:

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

`middleware(...)` (singular) appends one; `middlewares(...)` accepts `List<? extends MiddlewareBase>`. Hooks not implemented by a middleware are skipped at zero cost.

## Built-in middlewares

### OtelTracingMiddleware

`OtelTracingMiddleware` (`io.agentscope.core.tracing`) wires up [OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/gen-ai/) tracing for the agent lifecycle. It instruments `onAgent`, `onModelCall`, `onActing`, producing nested spans:

- `invoke_agent <name>` — wraps a full reply
- `chat <model>` — wraps each model API call
- `execute_tool <name>` — wraps each tool execution

When no OpenTelemetry SDK is configured (only the default no-op provider), every hook short-circuits to `next.apply(input)` — near-zero overhead.

Initialise the OpenTelemetry SDK in your process (OTLP exporter, `SdkTracerProvider`, `OpenTelemetrySdk.builder().setTracerProvider(...).buildAndRegisterGlobal()`) and then equip the middleware:

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

Each reply produces a nested span tree with attributes such as agent name, session ID, model name, token counts, tool name, and inputs.

### TaskReminderMiddleware

`TaskReminderMiddleware` (`io.agentscope.core.middleware`) pairs with the built-in `TodoTools`: before every reasoning step it renders the current `AgentState.tasksContext` as a `<system-reminder>` and injects it into the context, keeping long-running tasks aligned with the plan.

Enable it together with `TodoTools` via `enableTaskList(true)`:

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

## Custom middleware

Implement `MiddlewareBase` (`io.agentscope.core.middleware`) and override only the hooks you need.

Each onion hook receives a `next` function — calling `next.apply(input)` enters the next layer. You can insert logic before or after, or use Reactor operators (`doOnNext` / `flatMap` / `map`, …) to observe and rewrite the event stream.

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Observes agent / reasoning / model_call / system_prompt at the same time. */
public class FullObservabilityMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        System.out.println("[agent] start for " + agent.getName());
        return next.apply(input)
                .doOnComplete(() -> System.out.println("[agent] end for " + agent.getName()));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        System.out.println("[reasoning] start");
        return next.apply(input).doOnComplete(() -> System.out.println("[reasoning] end"));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        System.out.println("[model_call] " + input.model().getClass().getSimpleName());
        return next.apply(input).doOnComplete(() -> System.out.println("[model_call] done"));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        System.out.println("[system_prompt] length=" + currentPrompt.length());
        return Mono.just(currentPrompt);
    }
}
```

Input record types per hook (under `io.agentscope.core.middleware`):

| Hook | Input record | Fields |
|------|--------------|--------|
| `onAgent` | `AgentInput` | `msgs: List<Msg>` |
| `onReasoning` | `ReasoningInput` | `messages: List<Msg>`, `tools: List<ToolSchema>`, `options: GenerateOptions` |
| `onActing` | `ActingInput` | `toolCalls: List<ToolUseBlock>` |
| `onModelCall` | `ModelCallInput` | `messages`, `tools`, `options`, `model: Model` |
| `onSystemPrompt` | `String` | The current prompt |

To replace fields flowing into the next layer, construct a new input record, then call `next.apply(...)`.

Runnable examples: `agentscope-examples/documentation/.../middleware/CustomizedMiddlewareExample.java`, `middleware/ModelCallMiddlewareExample.java`, `middleware/SystemPromptMiddlewareExample.java`.

### Reading RuntimeContext

Every `MiddlewareBase` hook receives the `Agent` as the first argument. Calling `agent.getRuntimeContext()` returns the [`RuntimeContext`](./agent.md#runtimecontext-per-call-context) bound for this `call` / `stream` — you can read session fields and typed/string attributes, and you can write back to it to forward values to downstream hooks and tools.

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Log user / request id and propagate a trace id for downstream tools. */
public class RequestContextMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        RuntimeContext rc = agent.getRuntimeContext();
        if (rc != null) {
            System.out.printf(
                    "[req] user=%s session=%s reqId=%s%n",
                    rc.getUserId(),
                    rc.getSessionId(),
                    rc.get("request_id"));
            rc.put("trace_id", java.util.UUID.randomUUID().toString());  // visible to later hooks / tools
        }
        return next.apply(input);
    }
}
```

Things to keep in mind:

- `agent.getRuntimeContext()` is only non-null during a `call`; outside a call it returns `null`.
- The same `RuntimeContext` instance is shared by every hook and tool in the reply; its maps are thread-safe, so `put` from any hook is safe.
- Don't cache per-request state on middleware instance fields — a middleware instance is typically reused across agents / calls. Use `RuntimeContext` or Reactor's `contextWrite` instead.
- If the builder also has a global `toolExecutionContext`, the framework merges it after the per-call context when dispatching to tools (per-call wins on key collisions).

### Execution order

Onion hooks (`onAgent`, `onReasoning`, `onActing`, `onModelCall`) — **the first middleware in the list is outermost**:

```
middlewares = [mw1, mw2]
// Order:
// mw1 pre → mw2 pre → inner → mw2 post → mw1 post
```

For streaming / event-emitting hooks, the inner middleware sees each emitted event first:

```
mw1_pre → mw2_pre → mw2_event → mw1_event → ... → mw2_post → mw1_post
```

Transformer hooks (`onSystemPrompt`) — **left to right pipeline**:

```
middlewares = [mw1, mw2]
// originalPrompt → mw1.onSystemPrompt() → mw2.onSystemPrompt() → final
```

Overall hook execution order across one reply:

```
onAgent
  └── per ReAct round:
        ├── onReasoning
        │     ├── prepare model input → onSystemPrompt
        │     └── onModelCall
        └── onActing (per tool call)
```

## Practical examples

### Timing middleware

The middleware below records the wall-clock time of each model call:

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

### Rate-limit middleware

Enforce a minimum interval between two model calls:

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

### Dynamic system-prompt middleware

Inject runtime context into the system prompt. Or reuse the example `middleware/SystemPromptMiddlewareExample.java`:

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

// Wire-up:
// .middlewares(List.of(new DynamicContextMiddleware(() -> "Time: " + Instant.now())))
```

### Model-fallback middleware

Swap to a backup model if the primary fails:

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
For a simple primary→backup fallback, `ReActAgent.Builder` already exposes `fallbackModel(...)` and `maxRetries(...)` directly — no middleware needed.
:::
