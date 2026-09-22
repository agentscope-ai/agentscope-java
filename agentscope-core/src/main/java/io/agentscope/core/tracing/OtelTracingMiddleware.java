/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tracing;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

/**
 * Middleware that adds OpenTelemetry tracing to the agent lifecycle.
 *
 * <p>Produces spans for:
 * <ul>
 *   <li>{@code invoke_agent <name>} — wraps the entire reply</li>
 *   <li>{@code chat <model>} — wraps each model API call</li>
 *   <li>{@code execute_tool <name>} — wraps each tool execution</li>
 * </ul>
 *
 * <p>Model-call spans report input/output token usage and the available cache, reasoning, and
 * tool-prompt token breakdowns using GenAI semantic-convention attributes. The tool-prompt
 * breakdown uses an AgentScope-specific attribute because it is not yet defined by the GenAI
 * convention.
 *
 * <p>Context propagation across Reactor's asynchronous chain (including thread
 * hops via {@code publishOn} / {@code subscribeOn}) is handled by
 * {@link ContextPropagationOperator}
 * The global lift hook is registered once on class load, so child spans see
 * the correct parent regardless of which thread the signal lands on.
 *
 * <p>When no OTel SDK is configured (only the default no-op provider is
 * active), every hook short-circuits with near-zero overhead.
 *
 * <p>The no-argument constructor reads {@link GlobalOpenTelemetry} lazily when a hook runs.
 * Pass an application-owned SDK to {@link #OtelTracingMiddleware(OpenTelemetry)} when spans must
 * be recorded on that SDK instead. That constructor does not register or replace the global SDK;
 * the caller owns its lifecycle. {@code ReActAgent.builder().middleware(...)} is the production
 * attachment point for either constructor.
 *
 * <p>Usage:
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model(model)
 *     .middleware(new OtelTracingMiddleware())
 *     .build();
 * }</pre>
 */
public class OtelTracingMiddleware implements MiddlewareBase {

    private static final String INSTRUMENTATION_NAME = "io.agentscope";

    private static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    private static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    private static final String GEN_AI_USAGE_CACHE_CREATION_INPUT_TOKENS =
            "gen_ai.usage.cache_creation.input_tokens";
    private static final String GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS =
            "gen_ai.usage.cache_read.input_tokens";
    private static final String GEN_AI_USAGE_REASONING_OUTPUT_TOKENS =
            "gen_ai.usage.reasoning.output_tokens";
    private static final String AGENTSCOPE_USAGE_TOOL_USE_PROMPT_TOKENS =
            "agentscope.usage.tool_use_prompt_tokens";

    private static volatile boolean hookRegistered = false;

    /**
     * Application-owned SDK, or {@code null} when hooks should read {@link GlobalOpenTelemetry}
     * at execution time.
     */
    private final OpenTelemetry openTelemetry;

    /**
     * Creates middleware that reads {@link GlobalOpenTelemetry} lazily when a hook runs.
     *
     * <p>The SDK may be registered after this middleware is constructed. Spans are created from
     * the global {@code io.agentscope} tracer at execution time, so a middleware built before
     * {@code buildAndRegisterGlobal()} still exports to the SDK that is current when the hook
     * runs. When only the default no-op provider is active, the hooks still run the downstream
     * chain.
     */
    public OtelTracingMiddleware() {
        this.openTelemetry = null;
        registerReactorHook();
    }

    /**
     * Creates middleware that records spans on an application-owned OpenTelemetry SDK.
     *
     * <p>This is an opt-in path. {@code onAgent}, {@code onModelCall}, and {@code onActing} all
     * obtain the {@code io.agentscope} tracer from {@code openTelemetry}. The instance is not
     * registered as {@link GlobalOpenTelemetry}, and this middleware does not shut it down. Build
     * it with {@code OpenTelemetrySdk.builder().setTracerProvider(...).build()} (not {@code
     * buildAndRegisterGlobal()}), point an OTLP HTTP exporter at the application endpoint, and
     * close the tracer provider on shutdown. Attach it with the existing agent builder:
     *
     * <pre>{@code
     * String endpoint =
     *         System.getenv().getOrDefault(
     *                 "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");
     * SdkTracerProvider tracerProvider =
     *         SdkTracerProvider.builder()
     *                 .addSpanProcessor(
     *                         BatchSpanProcessor.builder(
     *                                         OtlpHttpSpanExporter.builder()
     *                                                 .setEndpoint(endpoint)
     *                                                 .build())
     *                                 .build())
     *                 .build();
     * OpenTelemetry appSdk =
     *         OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
     * Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
     *
     * ReActAgent agent =
     *         ReActAgent.builder()
     *                 .name("assistant")
     *                 .model(model)
     *                 .middleware(new OtelTracingMiddleware(appSdk))
     *                 .build();
     * }</pre>
     *
     * <p>{@code StudioManager} still installs deprecated {@code TracerRegistry} tracing on its own
     * during initialization. Vendor instrumentation that rewrites tracer lookup, and a missing
     * Studio call-tree expansion control, need separate verification against the deployment that
     * produces them. Passing {@link OpenTelemetry#noop()} runs the downstream chain and records
     * no spans.
     *
     * @param openTelemetry application-owned OpenTelemetry instance; must not be null
     * @throws NullPointerException if {@code openTelemetry} is null
     */
    public OtelTracingMiddleware(OpenTelemetry openTelemetry) {
        this.openTelemetry =
                Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        registerReactorHook();
    }

    private static void registerReactorHook() {
        if (!hookRegistered) {
            synchronized (OtelTracingMiddleware.class) {
                if (!hookRegistered) {
                    ContextPropagationOperator.builder().build().registerOnEachOperator();
                    hookRegistered = true;
                }
            }
        }
    }

    private Tracer getTracer() {
        if (openTelemetry != null) {
            return openTelemetry.getTracer(INSTRUMENTATION_NAME);
        }
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    // ------------------------------------------------------------------
    // onAgent — invoke_agent span
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext = resolveOtelContext(ctxView);
                    Span span =
                            getTracer()
                                    .spanBuilder("invoke_agent " + agent.getName())
                                    .setParent(parentContext)
                                    .setAttribute("gen_ai.operation.name", "invoke_agent")
                                    .setAttribute("gen_ai.agent.name", agent.getName())
                                    .setAttribute(
                                            "gen_ai.agent.id",
                                            agent.getAgentId() != null ? agent.getAgentId() : "")
                                    .setAttribute(
                                            "gen_ai.request.messages.count",
                                            (long) input.msgs().size())
                                    .startSpan();

                    Context otelCtx = span.storeInContext(parentContext);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof AgentStartEvent rse
                                                        && rse.getSource() == null
                                                        && rse.getReplyId() != null) {
                                                    span.setAttribute(
                                                            "agentscope.agent.reply_id",
                                                            rse.getReplyId());
                                                }
                                            })
                                    .doOnComplete(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.OK);
                                                    span.end();
                                                }
                                            })
                                    .doOnError(
                                            e -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(
                                                            StatusCode.ERROR, e.getMessage());
                                                    span.recordException(e);
                                                    span.end();
                                                }
                                            })
                                    .doOnCancel(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                                    span.end();
                                                }
                                            }),
                            otelCtx);
                });
    }

    // ------------------------------------------------------------------
    // onModelCall — chat span
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext = resolveOtelContext(ctxView);
                    Model model = input.model();
                    String modelName = model != null ? model.getModelName() : "unknown";
                    Span span =
                            getTracer()
                                    .spanBuilder("chat " + modelName)
                                    .setParent(parentContext)
                                    .setAttribute("gen_ai.operation.name", "chat")
                                    .setAttribute("gen_ai.request.model", modelName)
                                    .setAttribute(
                                            "gen_ai.request.messages.count",
                                            (long) input.messages().size())
                                    .setAttribute(
                                            "gen_ai.request.tools.count",
                                            input.tools() != null
                                                    ? (long) input.tools().size()
                                                    : 0L)
                                    .startSpan();

                    Context otelCtx = span.storeInContext(parentContext);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof ModelCallEndEvent mce) {
                                                    setModelResponseAttributes(span, mce);
                                                }
                                            })
                                    .doOnComplete(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.OK);
                                                    span.end();
                                                }
                                            })
                                    .doOnError(
                                            e -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(
                                                            StatusCode.ERROR, e.getMessage());
                                                    span.recordException(e);
                                                    span.end();
                                                }
                                            })
                                    .doOnCancel(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                                    span.end();
                                                }
                                            }),
                            otelCtx);
                });
    }

    // ------------------------------------------------------------------
    // onActing — execute_tool span
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext = resolveOtelContext(ctxView);
                    String toolNames =
                            input.toolCalls() != null
                                    ? input.toolCalls().stream()
                                            .map(ToolUseBlock::getName)
                                            .collect(Collectors.joining(", "))
                                    : "unknown";
                    String spanName = buildToolSpanName(input);

                    Span span =
                            getTracer()
                                    .spanBuilder("execute_tool " + spanName)
                                    .setParent(parentContext)
                                    .setAttribute("gen_ai.operation.name", "execute_tool")
                                    .setAttribute("gen_ai.tool.name", toolNames)
                                    .setAttribute(
                                            "gen_ai.tool.call.count",
                                            input.toolCalls() != null
                                                    ? (long) input.toolCalls().size()
                                                    : 0L)
                                    .startSpan();

                    Context otelCtx = span.storeInContext(parentContext);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);
                    Set<String> callIds = ConcurrentHashMap.newKeySet();

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof ToolResultEndEvent tre
                                                        && tre.getToolCallId() != null) {
                                                    callIds.add(tre.getToolCallId());
                                                }
                                            })
                                    .doOnComplete(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    setToolCallIds(span, callIds);
                                                    span.setStatus(StatusCode.OK);
                                                    span.end();
                                                }
                                            })
                                    .doOnError(
                                            e -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    setToolCallIds(span, callIds);
                                                    span.setStatus(
                                                            StatusCode.ERROR, e.getMessage());
                                                    span.recordException(e);
                                                    span.end();
                                                }
                                            })
                                    .doOnCancel(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    setToolCallIds(span, callIds);
                                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                                    span.end();
                                                }
                                            }),
                            otelCtx);
                });
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    // Reads OTel Context from Reactor ContextView first; falls back to ThreadLocal
    // Context.current()
    // so spans created inside a reactive pipeline can find their parent even after a thread hop.
    private Context resolveOtelContext(ContextView ctxView) {
        return ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                ctxView, Context.current());
    }

    // Uses the first tool name as the span name; appends "(+N more)" for batches to cap
    // cardinality.
    // Full tool name list is still available in the gen_ai.tool.name attribute.
    private static String buildToolSpanName(ActingInput input) {
        if (input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return "unknown";
        }
        String first = input.toolCalls().get(0).getName();
        int rest = input.toolCalls().size() - 1;
        return rest > 0 ? first + " (+" + rest + " more)" : first;
    }

    private void setToolCallIds(Span span, Set<String> callIds) {
        if (!callIds.isEmpty()) {
            span.setAttribute("gen_ai.tool.call.id", String.join(",", callIds));
        }
    }

    private void setModelResponseAttributes(Span span, ModelCallEndEvent event) {
        if (event.getUsage() != null) {
            var usage = event.getUsage();
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, (long) usage.getInputTokens());
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, (long) usage.getOutputTokens());
            span.setAttribute(
                    GEN_AI_USAGE_CACHE_CREATION_INPUT_TOKENS,
                    (long) usage.getCacheCreationTokens());
            span.setAttribute(GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS, (long) usage.getCachedTokens());
            span.setAttribute(
                    GEN_AI_USAGE_REASONING_OUTPUT_TOKENS, (long) usage.getReasoningTokens());
            span.setAttribute(
                    AGENTSCOPE_USAGE_TOOL_USE_PROMPT_TOKENS, (long) usage.getToolUsePromptTokens());
        }
    }
}
