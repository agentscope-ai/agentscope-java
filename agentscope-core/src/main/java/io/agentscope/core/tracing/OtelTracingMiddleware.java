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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // OpenTelemetry GenAI span semantic convention attributes.
    // Reference:
    // https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME =
            AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Boolean> GEN_AI_REQUEST_STREAM =
            AttributeKey.booleanKey("gen_ai.request.stream");
    private static final AttributeKey<Long> GEN_AI_REQUEST_SEED =
            AttributeKey.longKey("gen_ai.request.seed");
    private static final AttributeKey<Long> GEN_AI_REQUEST_TOP_K =
            AttributeKey.longKey("gen_ai.request.top_k");
    private static final AttributeKey<Long> GEN_AI_REQUEST_MAX_TOKENS =
            AttributeKey.longKey("gen_ai.request.max_tokens");
    private static final AttributeKey<Double> GEN_AI_REQUEST_FREQUENCY_PENALTY =
            AttributeKey.doubleKey("gen_ai.request.frequency_penalty");
    private static final AttributeKey<Double> GEN_AI_REQUEST_PRESENCE_PENALTY =
            AttributeKey.doubleKey("gen_ai.request.presence_penalty");
    private static final AttributeKey<String> GEN_AI_REQUEST_REASONING_LEVEL =
            AttributeKey.stringKey("gen_ai.request.reasoning.level");
    private static final AttributeKey<Double> GEN_AI_REQUEST_TEMPERATURE =
            AttributeKey.doubleKey("gen_ai.request.temperature");
    private static final AttributeKey<Double> GEN_AI_REQUEST_TOP_P =
            AttributeKey.doubleKey("gen_ai.request.top_p");
    private static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<String> GEN_AI_INPUT_MESSAGES =
            AttributeKey.stringKey("gen_ai.input.messages");
    private static final AttributeKey<String> GEN_AI_OUTPUT_MESSAGES =
            AttributeKey.stringKey("gen_ai.output.messages");
    private static final AttributeKey<String> GEN_AI_TOOL_DEFINITIONS =
            AttributeKey.stringKey("gen_ai.tool.definitions");

    private static final String OPERATION_CHAT = "chat";

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
                    SpanBuilder spanBuilder =
                            getTracer()
                                    .spanBuilder("chat " + modelName)
                                    .setParent(parentContext)
                                    .setAttribute(GEN_AI_OPERATION_NAME, OPERATION_CHAT)
                                    .setAttribute(GEN_AI_REQUEST_MODEL, modelName)
                                    .setAttribute(
                                            "gen_ai.request.messages.count",
                                            (long) input.messages().size())
                                    .setAttribute(
                                            "gen_ai.request.tools.count",
                                            input.tools() != null
                                                    ? (long) input.tools().size()
                                                    : 0L);
                    setModelRequestAttributes(spanBuilder, input);
                    Span span = spanBuilder.startSpan();

                    Context otelCtx = span.storeInContext(parentContext);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);
                    StringBuilder completionText = new StringBuilder();
                    StringBuilder completionReasoning = new StringBuilder();
                    Map<String, ToolCallAccumulator> toolCallAccumulators = new LinkedHashMap<>();

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof TextBlockDeltaEvent tde
                                                        && tde.getDelta() != null) {
                                                    completionText.append(tde.getDelta());
                                                } else if (event
                                                                instanceof
                                                                ThinkingBlockDeltaEvent tde
                                                        && tde.getDelta() != null) {
                                                    completionReasoning.append(tde.getDelta());
                                                } else if (event
                                                        instanceof ToolCallDeltaEvent tde) {
                                                    getToolCallAccumulator(
                                                                    toolCallAccumulators,
                                                                    tde.getToolCallId(),
                                                                    tde.getToolCallName())
                                                            .append(tde.getDelta());
                                                } else if (event instanceof ModelCallEndEvent mce) {
                                                    setModelResponseAttributes(
                                                            span,
                                                            mce,
                                                            completionText.toString(),
                                                            completionReasoning.toString(),
                                                            toolCallAccumulators);
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

    private void setModelRequestAttributes(SpanBuilder spanBuilder, ModelCallInput input) {
        setGenerationOptionAttributes(spanBuilder, input.options());

        String inputMessages = serializeInputMessages(input.messages());
        if (inputMessages != null) {
            spanBuilder.setAttribute(GEN_AI_INPUT_MESSAGES, inputMessages);
        }

        String toolDefinitions = serializeToolDefinitions(input.tools());
        if (toolDefinitions != null) {
            spanBuilder.setAttribute(GEN_AI_TOOL_DEFINITIONS, toolDefinitions);
        }
    }

    private void setModelResponseAttributes(
            Span span,
            ModelCallEndEvent event,
            String completionText,
            String completionReasoning,
            Map<String, ToolCallAccumulator> toolCallAccumulators) {
        if (event.getUsage() != null) {
            var usage = event.getUsage();
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, (long) usage.getInputTokens());
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, (long) usage.getOutputTokens());
        }

        String outputMessages =
                serializeOutputMessages(completionText, completionReasoning, toolCallAccumulators);
        if (outputMessages != null) {
            span.setAttribute(GEN_AI_OUTPUT_MESSAGES, outputMessages);
        }
    }

    private void setGenerationOptionAttributes(SpanBuilder spanBuilder, GenerateOptions options) {
        if (options == null) {
            return;
        }
        if (options.getStream() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_STREAM, options.getStream());
        }
        if (options.getTemperature() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_TEMPERATURE, options.getTemperature());
        }
        if (options.getTopP() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_TOP_P, options.getTopP());
        }
        if (options.getTopK() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_TOP_K, (long) options.getTopK());
        }
        if (options.getMaxTokens() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, (long) options.getMaxTokens());
        }
        if (options.getPresencePenalty() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_PRESENCE_PENALTY, options.getPresencePenalty());
        }
        if (options.getFrequencyPenalty() != null) {
            spanBuilder.setAttribute(
                    GEN_AI_REQUEST_FREQUENCY_PENALTY, options.getFrequencyPenalty());
        }
        if (options.getSeed() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_SEED, options.getSeed());
        }
        if (options.getReasoningEffort() != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_REASONING_LEVEL, options.getReasoningEffort());
        }
    }

    private String serializeInputMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        List<InputMessage> serializedMessages = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            if (msg == null) {
                continue;
            }
            serializedMessages.add(
                    new InputMessage(
                            msg.getRole() != null ? msg.getRole().name().toLowerCase() : "unknown",
                            serializeParts(msg.getContent()),
                            msg.getName()));
        }
        return serializedMessages.isEmpty() ? null : serializeToJson(serializedMessages);
    }

    private String serializeOutputMessages(
            String completionText,
            String completionReasoning,
            Map<String, ToolCallAccumulator> toolCallAccumulators) {
        List<MessagePart> parts = new ArrayList<>();
        if (completionReasoning != null && !completionReasoning.isEmpty()) {
            parts.add(new ReasoningPart(completionReasoning));
        }
        if (completionText != null && !completionText.isEmpty()) {
            parts.add(new TextPart(completionText));
        }
        for (ToolCallAccumulator toolCall : toolCallAccumulators.values()) {
            parts.add(
                    new ToolCallRequestPart(
                            toolCall.id, toolCall.name, toolCall.arguments.toString()));
        }
        if (parts.isEmpty()) {
            return null;
        }

        return serializeToJson(List.of(new OutputMessage("assistant", parts)));
    }

    private List<MessagePart> serializeParts(List<ContentBlock> content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<MessagePart> parts = new ArrayList<>(content.size());
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                parts.add(new TextPart(textBlock.getText()));
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                parts.add(new ReasoningPart(thinkingBlock.getThinking()));
            } else if (block instanceof ToolUseBlock toolUseBlock) {
                parts.add(
                        new ToolCallRequestPart(
                                toolUseBlock.getId(),
                                toolUseBlock.getName(),
                                toolUseBlock.getInput()));
            } else if (block instanceof ToolResultBlock toolResultBlock) {
                parts.add(
                        new ToolCallResponsePart(
                                toolResultBlock.getId(), toolResultBlock.getOutput()));
            }
        }
        return parts;
    }

    private String serializeToolDefinitions(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<ToolDefinition> toolDefinitions = new ArrayList<>(tools.size());
        for (ToolSchema tool : tools) {
            if (tool == null) {
                continue;
            }
            toolDefinitions.add(
                    new ToolDefinition(
                            "function",
                            tool.getName(),
                            tool.getDescription(),
                            tool.getParameters()));
        }
        return toolDefinitions.isEmpty() ? null : serializeToJson(toolDefinitions);
    }

    private ToolCallAccumulator getToolCallAccumulator(
            Map<String, ToolCallAccumulator> toolCalls, String id, String name) {
        String key = id != null ? id : name;
        return toolCalls.computeIfAbsent(key, ignored -> new ToolCallAccumulator(id, name));
    }

    private String serializeToJson(Object value) {
        try {
            return JsonUtils.getJsonCodec().toJson(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class ToolCallAccumulator {
        private final String id;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallAccumulator(String id, String name) {
            this.id = id;
            this.name = name;
        }

        private void append(String delta) {
            if (delta != null) {
                arguments.append(delta);
            }
        }
    }

    private record InputMessage(String role, List<MessagePart> parts, String name) {}

    private record OutputMessage(String role, List<MessagePart> parts) {}

    private sealed interface MessagePart
            permits TextPart, ReasoningPart, ToolCallRequestPart, ToolCallResponsePart {

        String type();
    }

    private record TextPart(String type, String content) implements MessagePart {

        private TextPart(String content) {
            this("text", content);
        }
    }

    private record ReasoningPart(String type, String content) implements MessagePart {

        private ReasoningPart(String content) {
            this("reasoning", content);
        }
    }

    private record ToolCallRequestPart(String type, String id, String name, Object arguments)
            implements MessagePart {

        private ToolCallRequestPart(String id, String name, Object arguments) {
            this("tool_call", id, name, arguments);
        }
    }

    private record ToolCallResponsePart(String type, String id, Object response)
            implements MessagePart {

        private ToolCallResponsePart(String id, Object response) {
            this("tool_call_response", id, response);
        }
    }

    private record ToolDefinition(
            String type, String name, String description, Map<String, Object> parameters) {}
}
