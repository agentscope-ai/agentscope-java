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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

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
 * <p>Context propagation across Reactor's asynchronous chain (including thread
 * hops via {@code publishOn} / {@code subscribeOn}) is handled by
 * {@link ContextPropagationOperator}
 * The global lift hook is registered once on class load, so child spans see
 * the correct parent regardless of which thread the signal lands on.
 *
 * <p>When no OTel SDK is configured (only the default no-op provider is
 * active), every hook short-circuits with near-zero overhead.
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

    private static volatile boolean hookRegistered = false;

    public OtelTracingMiddleware() {
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
        return Flux.defer(
                () -> {
                    Span span =
                            getTracer()
                                    .spanBuilder("invoke_agent " + agent.getName())
                                    .setAttribute("gen_ai.operation.name", "invoke_agent")
                                    .setAttribute("gen_ai.agent.name", agent.getName())
                                    .setAttribute(
                                            "gen_ai.agent.id",
                                            agent.getAgentId() != null ? agent.getAgentId() : "")
                                    .setAttribute(
                                            "gen_ai.request.messages.count",
                                            (long) input.msgs().size())
                                    .startSpan();

                    Context otelCtx = Context.current().with(span);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof AgentStartEvent rse
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
        return Flux.defer(
                () -> {
                    Model model = input.model();
                    String modelName = model != null ? model.getModelName() : "unknown";
                    Span span =
                            getTracer()
                                    .spanBuilder("chat " + modelName)
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

                    Context otelCtx = Context.current().with(span);
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
        return Flux.defer(
                () -> {
                    String toolNames =
                            input.toolCalls() != null
                                    ? input.toolCalls().stream()
                                            .map(ToolUseBlock::getName)
                                            .collect(Collectors.joining(", "))
                                    : "unknown";

                    Span span =
                            getTracer()
                                    .spanBuilder("execute_tool " + toolNames)
                                    .setAttribute("gen_ai.operation.name", "execute_tool")
                                    .setAttribute("gen_ai.tool.name", toolNames)
                                    .setAttribute(
                                            "gen_ai.tool.call.count",
                                            input.toolCalls() != null
                                                    ? (long) input.toolCalls().size()
                                                    : 0L)
                                    .startSpan();

                    Context otelCtx = Context.current().with(span);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);
                    Set<String> callIds = new LinkedHashSet<>();

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

    private void setToolCallIds(Span span, Set<String> callIds) {
        if (!callIds.isEmpty()) {
            span.setAttribute("gen_ai.tool.call.id", String.join(",", callIds));
        }
    }

    private void setModelResponseAttributes(Span span, ModelCallEndEvent event) {
        if (event.getUsage() != null) {
            var usage = event.getUsage();
            span.setAttribute("gen_ai.usage.input_tokens", (long) usage.getInputTokens());
            span.setAttribute("gen_ai.usage.output_tokens", (long) usage.getOutputTokens());
        }
    }
}
