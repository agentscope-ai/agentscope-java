package io.agentscope.core.tracing;

import static io.agentscope.core.tracing.AgentScopeIncubatingAttributes.AGENTSCOPE_FUNCTION_NAME;
import static io.agentscope.core.tracing.AttributesExtractors.getFunctionName;
import static io.agentscope.core.tracing.AttributesExtractors.getLLMRequestAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getLLMResponseAttributes;
import static io.agentscope.core.tracing.Telemetry.checkTracingEnabled;
import static io.agentscope.core.tracing.Telemetry.getTracer;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.List;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class TelemetryWrappers {

    public static Mono<Msg> traceAgent(
            AgentBase instance, List<Msg> inputMessages, Supplier<Mono<Msg>> agentCall) {
        if (!checkTracingEnabled()) {
            return agentCall.get();
        }

        return Mono.deferContextual(
                ctxView -> {
                    Context parentContext =
                            ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                    ctxView, Context.current());
                    // TODO
                    Span span =
                            getTracer()
                                    .spanBuilder("invoke_agent " + instance.getName())
                                    .setParent(parentContext)
                                    .startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                    return agentCall
                            .get()
                            .doOnError(span::recordException)
                            .doFinally(unuse -> span.end())
                            .contextWrite(
                                    ctx ->
                                            ContextPropagationOperator.storeOpenTelemetryContext(
                                                    ctx, otelContext));
                });
    }

    public static Flux<ChatResponse> traceLLM(
            ChatModelBase instance,
            List<Msg> inputMessages,
            List<ToolSchema> toolSchemas,
            GenerateOptions options,
            Supplier<Flux<ChatResponse>> modelCall) {
        if (!checkTracingEnabled()) {
            return modelCall.get();
        }

        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext =
                            ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                    ctxView, Context.current());
                    SpanBuilder spanBuilder =
                            getTracer()
                                    .spanBuilder("chat " + instance.getModelName())
                                    .setParent(parentContext);
                    spanBuilder.setAllAttributes(
                            getLLMRequestAttributes(instance, inputMessages, toolSchemas, options));
                    spanBuilder.setAttribute(AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, "call"));

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                  StreamChatResponseAggregator aggregator = StreamChatResponseAggregator.create();

                  return modelCall
                            .get()
                            .doOnNext(response -> aggregator.append(response))
                            .doOnError(span::recordException)
                            .doFinally(unuse -> {
                              ChatResponse response = aggregator.getResponse();
                              span.setAllAttributes(getLLMResponseAttributes(response));
                              span.end();
                            })
                            .contextWrite(
                                    ctx ->
                                            ContextPropagationOperator.storeOpenTelemetryContext(
                                                    ctx, otelContext));
                });
    }

    // TODO
    public static void traceToolKit() {}

    public static void traceFormat() {}

    public static void traceEmbedding() {}

    public static void trace() {}

    private TelemetryWrappers() {}
}
