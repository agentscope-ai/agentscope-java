---
title: Middleware
description: Intercept and extend agent behavior at key lifecycle points
zh_link: /v2/zh/docs/building-blocks/middleware
---

## Overview

Agent middleware lets you inject custom logic (logging, tracing, input rewriting, access control, …) at key points in an agent's execution flow without modifying the agent or model code.

In AgentScope Java, you can hook into 8 places — covering the full call lifecycle: state binding before the pipeline, the reply flow down to the raw model API call, the final-answer boundary, and post-result completion:

| Position | Type | Description |
|----------|------|-------------|
| `onAgent` | Onion | Wraps a full reply flow, covering all ReAct rounds, tool execution, and the final output |
| `onReasoning` | Onion | Wraps one reasoning step in the ReAct loop (input assembly → model call → streaming decode) |
| `onActing` | Onion | Wraps the execution of a single tool call |
| `onModelCall` | Onion | Wraps a raw `ChatModel` API call — closest to the model |
| `onSystemPrompt` | Transformer | Triggers when the system prompt is assembled; multiple middlewares run in sequence, each transforming the previous output |
| `onAgentStateReady` | Notification | Fires once per call as soon as this call's `AgentState` is ready — before the input enters the pipeline (pre-call hooks, memory, reasoning) |
| `onFinalAnswer` | Gate | Last word on the final answer before anything ships; the moment any middleware participates, the agent switches to certified delivery (see the [final-answer gate](#final-answer-gate-onfinalanswer) section) |
| `onAgentFinish` | Notification | Runs after a successful call result, before the invocation-journal checkpoint commit and writer release — the last point that still carries the active call's `RuntimeContext` |

The four types differ:

- **Onion** — middleware wraps the next handler; you can insert logic before/after `next.apply(input)` and observe the intermediate event stream.
- **Transformer** — middlewares form a pipeline; the previous output is the next input. There's no "inner layer" concept.
- **Notification** — one-way notification without `next` delegation; middlewares run in sequence at a fixed lifecycle point. `onAgentStateReady` is a synchronous no-arg-effect call; `onAgentFinish` returns a completion signal (`Mono<Void>`).
- **Gate** — sequential consultation with the last word on delivery. Unlike the others, it is a side-effect point: the moment any middleware participates, live answer content is withheld until the chain certifies it.

The diagram below shows how the hooks nest in the agent lifecycle. `onSystemPrompt` is nested inside `onReasoning` because it fires when the reasoning step assembles the system prompt; `onAgentFinish` runs only after a successful result, at the journal's finalization boundary:

```text
onAgent/
├── onAgentStateReady (call-scoped AgentState ready, input not yet in pipeline)
├── ReAct loop (per round)/
│   ├── onReasoning/
│   │   ├── onSystemPrompt (assemble system prompt)
│   │   └── onModelCall (model API call)
│   └── onActing (per tool call)
├── onFinalAnswer (final-answer exits: normal finish / maxIters summary / returnDirect)
└── onAgentFinish (successful call complete, before journal checkpoint/writer release)
```

`onFinalAnswer` is the only side-effect point among these hooks — its activation semantics and the certified-delivery mode it switches on are covered in the [final-answer gate](#final-answer-gate-onfinalanswer) section below.

<Note>

`onActing` only wraps tool executions inside the agent runtime. Tools executed outside the agent via external execution are not tracked by `onActing`.

</Note>


## Equipping middleware

AgentScope packs a set of hooks into a single `MiddlewareBase` implementation — one middleware class can implement any subset of the 8 hooks (onion/transformer hooks not implemented default to `next.apply(input)`; notification hooks default to a no-op / immediate completion; gate hooks default to accepting the candidate). Pass the instances to the builder's `middlewares(...)`:

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

`middleware(...)` (singular) adds one; `middlewares(...)` accepts `List<? extends MiddlewareBase>`. Hooks not implemented by a middleware are skipped at zero cost.

## Built-in middlewares

### OtelTracingMiddleware

`OtelTracingMiddleware` (`io.agentscope.core.tracing`) wires up [OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/gen-ai/) tracing for the agent lifecycle. It instruments `onAgent`, `onModelCall`, `onActing`, producing nested spans:

- `invoke_agent <name>` — wraps a full reply
- `chat <model>` — wraps each model API call
- `execute_tool <name>` — wraps each tool execution

When no OpenTelemetry SDK is configured (only the default no-op provider), every hook short-circuits to `next.apply(input)` — near-zero overhead.

`OtelTracingMiddleware` reads the process-wide `GlobalOpenTelemetry` instance. Applications that export spans themselves need the OpenTelemetry SDK and OTLP exporter in addition to AgentScope. Keep their versions aligned through the OpenTelemetry BOM (the version below matches the one currently used by AgentScope):

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

Build and register the SDK once per process before constructing the agent. The optional environment variable in this example can contain a value such as `Basic <base64-credentials>` for a backend that requires an `Authorization` header, including Langfuse:

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

The SDK must be registered before the middleware is used. If your runtime (for example, Spring Boot OpenTelemetry auto-configuration) already registers `GlobalOpenTelemetry`, reuse it and only add the middleware. Do not call the deprecated `TracerRegistry.register(...)` in the new setup. Close the `SdkTracerProvider` during application shutdown so its batch processor can flush pending spans.

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

### FinalAnswerFilterMiddleware

`FinalAnswerFilterMiddleware` exposes only the text from the final ReAct reasoning round. Text from rounds that produce tool calls is suppressed, while tool and other non-text events continue to stream normally.

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

The middleware buffers each round's text until the model call ends, because it cannot know whether the round is final until no tool call is observed.

Division of labor with the [final-answer gate](#final-answer-gate-onfinalanswer): this middleware serves gate-free agents that only want a clean stream. Registering any gate makes it redundant but harmless — the gate's guard already withholds every round's live text and publishes only the certified content, so the per-round buffering simply never matters.

## Custom middleware

Implement `MiddlewareBase` (`io.agentscope.core.middleware`) and override only the hooks you need.

Each onion hook receives a `next` function — calling `next.apply(input)` enters the next layer. You can insert logic before or after, or use Reactor operators (`doOnNext` / `flatMap` / `map`, …) to observe and rewrite the event stream.

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

/** Observes agent / reasoning / model_call / system_prompt at the same time. */
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

Input record types per hook (under `io.agentscope.core.middleware`):

| Hook | Input record | Fields |
|------|--------------|--------|
| `onAgent` | `AgentInput` | `msgs: List<Msg>` |
| `onReasoning` | `ReasoningInput` | `messages: List<Msg>`, `tools: List<ToolSchema>`, `options: GenerateOptions` |
| `onActing` | `ActingInput` | `toolCalls: List<ToolUseBlock>` |
| `onModelCall` | `ModelCallInput` | `messages`, `tools`, `options`, `model: Model` |
| `onSystemPrompt` | `String` | The current prompt |
| `onAgentStateReady` | — (direct parameters) | `state: AgentState`, `inputMessages: List<Msg>` |
| `onFinalAnswer` | `FinalAnswerInput` | `candidate: Msg`, `retryCount: int`, `cumulativeUsage: ChatUsage`, `finishReason: FinishReason` |
| `onAgentFinish` | — (direct parameters) | `result: Msg` (returns a `Mono<Void>` completion signal) |

To replace fields flowing into the next layer, construct a new input record, then call `next.apply(...)`.

Runnable examples: `agentscope-examples/documentation/.../middleware/CustomizedMiddlewareExample.java`, `middleware/ModelCallMiddlewareExample.java`, `middleware/SystemPromptMiddlewareExample.java`.

### Final-answer gate (`onFinalAnswer`)

The final answer is the last thing a call produces — compliance review, PII redaction, format validation, fallback rewriting all want a look at it *before* it ships. The `onFinalAnswer` hook is that checkpoint: whenever an output is about to become this call's final answer, the gate middleware is consulted first, and it has the last word on what gets delivered.

All three exits pass the gate — a normal finish (no pending tool calls this round), the forced summary after maxIters, and a returnDirect short-circuit (every tool call of the round is returnDirect and succeeds) — distinguished by `input.finishReason()` (`FINAL_ANSWER` / `MAX_ITERATIONS` / `RETURN_DIRECT`). Interrupt-like exits (HITL suspension, acting stops) are not final answers and never pass it.

The decision is entirely yours:

- `FinalAnswerDecision.accept()` — the candidate counts; deliver it unchanged;
- `FinalAnswerDecision.accept(replacement)` — deliver the given message instead: the replacement is fed forward to later gates and, if the round is accepted, memorized and delivered as the chain's output (the session keeps the sanitized version, not the original);
- `FinalAnswerDecision.retry(msgs)` — the candidate does not count: the model's original draft plus your feedback enters the context and reasoning re-runs. A gate completing empty counts as a plain accept — not answering means the candidate counts.

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.FinalAnswerDecision;
import io.agentscope.core.middleware.FinalAnswerInput;
import io.agentscope.core.middleware.FinishReason;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class ComplianceGate implements MiddlewareBase {

    private static final int MAX_RETRIES = 3;  // your own policy: well below the framework fuse

    @Override
    public Mono<FinalAnswerDecision> onFinalAnswer(RuntimeContext ctx, FinalAnswerInput input) {
        // Retrying an exhausted summary rarely converges — swap in a fallback instead.
        if (input.finishReason() == FinishReason.MAX_ITERATIONS) {
            return Mono.just(FinalAnswerDecision.accept(fallbackMessage()));
        }
        // Tool-direct output usually comes from your own tool code — skip by finishReason.
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
                        // Graceful exhaustion fallback is the gate's own job — the
                        // framework's fuse fails closed, it never degrades to deliver.
                        return FinalAnswerDecision.accept(fallbackMessage());
                    }
                    return FinalAnswerDecision.retry(reminder(result.issues()));
                });
    }
}
```

#### Activation and certified delivery

`ON_FINAL_ANSWER` is a side-effect extension point: the moment any middleware participates there, the agent switches to **certified delivery**. Participation is never vacuous: auto-detection lights up exactly the classes that factually implement `onFinalAnswer` (writing the hook is writing gate logic), and the only other path is an explicit declaration. A gate is simply a `MiddlewareBase` that overrides `onFinalAnswer` — no dedicated interface exists; participation follows the same `activePoints()` contract as every other extension point, with an explicit `EnumSet.of(ExtensionPoint.ON_FINAL_ANSWER)` declaration recommended as everywhere else.

The hook never receives the agent. Binding runs one way — the agent binds its middlewares at construction; a middleware never holds or branches on the agent it serves. A verdict function needs only the per-call context and the gate input, and keeping the agent out of the signature makes a stateless gate a pure function whose single instance safely serves any number of agents and concurrent calls — singleton reuse is safe by construction, not by discipline. Cross-round state belongs in `RuntimeContext` attributes, never in gate fields.

Under certified delivery, the public event stream behaves as follows:

- Live `TextBlock*` / `ThinkingBlock*` events of every round are withheld from the public stream — dropped, not buffered, so nothing can leak on cancel or error. Real-time streaming is consciously sacrificed; drive a "reviewing…" placeholder UI from the content-free `final_answer.reviewing` custom event, and animate the certified text locally once it arrives.
- After the verdict, the certified message (the chain's output — thinking first, then text) is projected in one burst. The projected content, `call()`'s return value, `AgentResultEvent`, and memory are the same message by construction; rejected candidates never reach the public stream — they go to memory for self-correction and to the invocation-journal audit only.
- `ModelCallEndEvent` still fires live (usage only) and may precede the certified content — assemble text on `TextBlockEnd` / `AgentResultEvent`, never on `ModelCallEnd`.
- Agents without a gate are byte-for-byte unchanged: live token streaming stays, no gate events appear.

The invocation-journal audit event `final_answer.rejected` keeps the rejected draft; the `final_answer.retry` telemetry event carries only `retryCount` and `finishReason`, never candidate text. The `CUSTOM` channel always bypasses the guard, so by convention it carries narrative progress only — never answer content.

#### Chain semantics

Multiple gates stack, consulted sequentially in `order()` sequence (higher first; registration order within ties):

- **Working draft feeds forward.** Each gate receives the current working draft: the chain head sees the model's original, later gates see their predecessors' replacements. `input.candidate()` is what that gate judges.
- **Vetoes never short-circuit.** Every gate is consulted; all feedback of a round is collected in chain order; one round with multiple vetoes counts as a single retry. The retry writes the model's **original** draft (never a gate's rewrite) plus the merged feedback to memory, and re-enters reasoning ignoring `maxIters`.
- **Dead rounds are discarded whole.** A replacement accepted by a later gate on a vetoed round is fed forward for judging but thrown away at round end — the next round rebuilds from the new candidate.
- **Two valid orderings — not both at once.** Transforms at the head + veto gates at the tail: the veto certifies exactly what ships (best for compliance sign-off; feedback may reference transformed text). Veto gates at the head + transforms at the tail: the veto judges the model's own words (cleanest correction loop; transforms must then be trusted deterministic assembly — redaction, stitching, disclaimers — not semantic rewrites). Having both would require re-review loops with no convergence guarantee; the framework does not offer them.
- **Fail closed.** A gate error fails the whole call — never a silent pass. The framework fuse honors at most 10 retries per call (not configurable): the 11th veto terminates the call with an error and zero delivery. Graceful exhaustion fallback comes from a gate managing its own budget via `input.retryCount()`, not from the framework.

Purely observational uses (logging, metrics, audit) should not implement the hook — the event stream and invocation journal already serve them, and becoming a gate sacrifices real-time streaming for nothing. Likewise, if you only want intermediate-round text suppressed without a verdict, register [`FinalAnswerFilterMiddleware`](#finalanswerfiltermiddleware) on a gate-free agent; with a gate registered it is redundant but harmless.

`input.retryCount()` tells you how many retries have already been honored in this call (0 for the first candidate, reset across calls — parallel calls on one agent keep separate budgets), and `input.cumulativeUsage()` sums the token usage of every attempt, so nothing is lost across retries.

### Reading RuntimeContext

Every `MiddlewareBase` hook receives the [`RuntimeContext`](/v2/en/docs/building-blocks/agent#runtimecontext-per-call-context) bound for this `call` / `stream` as the second argument — you can read session fields and typed/string attributes, and you can write back to it to forward values to downstream hooks and tools.

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
            Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        System.out.printf(
                "[req] user=%s session=%s reqId=%s%n",
                ctx.getUserId(),
                ctx.getSessionId(),
                ctx.get("request_id"));
        ctx.put("trace_id", java.util.UUID.randomUUID().toString());  // visible to later hooks / tools
        return next.apply(input);
    }
}
```

Things to keep in mind:

- The same `RuntimeContext` instance is shared by every hook and tool in the reply; its maps are thread-safe, so `put` from any hook is safe.
- Don't cache per-request state on middleware instance fields — a middleware instance is typically reused across agents / calls. Use `RuntimeContext` or Reactor's `contextWrite` instead.
- If the builder also has a global `toolExecutionContext`, the framework merges it after the per-call context when dispatching to tools (per-call wins on key collisions).

### Execution order

Onion hooks (`onAgent`, `onReasoning`, `onActing`, `onModelCall`) are ordered by `MiddlewareBase.order()` — **higher values are outermost**. The default order is `1`; middlewares with the same order retain their builder registration order:

```
middlewares = [mw1(order=2), mw2(order=1)]
// Order:
// mw1 pre → mw2 pre → inner → mw2 post → mw1 post
```

Override `order()` to move a custom middleware relative to the default order. For example, an order of `0` runs inside middleware that keeps the default order of `1`:

```java
MiddlewareBase lowerPriority = new MiddlewareBase() {
    @Override
    public int order() {
        return 0;
    }
};
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

Notification hooks (`onAgentStateReady`, `onAgentFinish`) run once per call in `order()` sequence — higher values first, matching the onion entry order:

```
middlewares = [mw1(order=2), mw2(order=1)]
// onAgentStateReady / onAgentFinish: mw1 → mw2
```

Gate middlewares (participating at `ON_FINAL_ANSWER`) are consulted in the same `order()` sequence at each final-answer exit; see [the gate section](#final-answer-gate-onfinalanswer) for the chain rules (feed-forward working draft, no veto short-circuit, merged feedback):

```
middlewares = [mw1(order=2), mw2(order=1)]
// onFinalAnswer: mw1 → mw2 → round verdict
```

Overall hook execution order across one reply:

```
onAgent
  ├── onAgentStateReady (state bound to RuntimeContext, before input enters pipeline)
  ├── per ReAct round:
  │     ├── onReasoning
  │     │     ├── prepare model input → onSystemPrompt
  │     │     └── onModelCall
  │     └── onActing (per tool call)
  ├── onFinalAnswer (final-answer exits: normal finish / maxIters summary / returnDirect)
  └── onAgentFinish (successful result fixed, before journal checkpoint/writer release)
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


<Tip>

For a simple primary→backup fallback, `ReActAgent.Builder` already exposes `fallbackModel(...)` and `maxRetries(...)` directly — no middleware needed. Observing the switch is the same story: it happens below the `onModelCall` seam, so use `ReActAgent.Builder.failoverListener(...)` rather than a middleware.

</Tip>


### Stop agent when all tools are denied

When a user denies all tool calls from a reasoning step via HITL, the agent continues to the next reasoning iteration by default (backward compatible). To stop the agent in this scenario, write an `onActing` middleware that observes `AllToolsDeniedEvent` and emits a `RequestStopEvent`:

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

Once wired up, the agent stops immediately when all tools are denied, returning `GenerateReason.ALL_TOOLS_DENIED`:

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
