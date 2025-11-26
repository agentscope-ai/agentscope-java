package io.agentscope.core.tracing;

import static io.agentscope.core.tracing.AgentScopeIncubatingAttributes.AGENTSCOPE_FUNCTION_NAME;
import static io.agentscope.core.tracing.AgentScopeIncubatingAttributes.GenAiOperationNameAgentScopeIncubatingValues.FORMAT;
import static io.agentscope.core.tracing.AttributesExtractors.getAgentRequestAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getAgentResponseAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getCommonAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getFormatRequestAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getFormatResponseAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getFunctionName;
import static io.agentscope.core.tracing.AttributesExtractors.getLLMRequestAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getLLMResponseAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getToolRequestAttributes;
import static io.agentscope.core.tracing.AttributesExtractors.getToolResponseAttributes;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.CHAT;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.EXECUTE_TOOL;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.INVOKE_AGENT;
import static io.agentscope.core.tracing.Telemetry.checkTracingEnabled;
import static io.agentscope.core.tracing.Telemetry.getTracer;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.AttributesExtractors.FormatterConverter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.List;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class TelemetryWrappers {

    public static Mono<Msg> traceAgent(
            AgentBase instance,
            String methodName,
            List<Msg> inputMessages,
            Supplier<Mono<Msg>> agentCall) {
        if (!checkTracingEnabled()) {
            return agentCall.get();
        }

        return Mono.deferContextual(
                ctxView -> {
                    Context parentContext =
                            ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                    ctxView, Context.current());
                    SpanBuilder spanBuilder =
                            getTracer()
                                    .spanBuilder(INVOKE_AGENT + " " + instance.getName())
                                    .setParent(parentContext);
                    spanBuilder.setAllAttributes(
                            getAgentRequestAttributes(instance, inputMessages));
                    spanBuilder.setAllAttributes(getCommonAttributes());
                    spanBuilder.setAttribute(
                            AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, methodName));

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                    return agentCall
                            .get()
                            .doOnSuccess(
                                    msg -> span.setAllAttributes(getAgentResponseAttributes(msg)))
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
            String methodName,
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
                                    .spanBuilder(CHAT + " " + instance.getModelName())
                                    .setParent(parentContext);
                    spanBuilder.setAllAttributes(
                            getLLMRequestAttributes(instance, inputMessages, toolSchemas, options));
                    spanBuilder.setAllAttributes(getCommonAttributes());
                    spanBuilder.setAttribute(
                            AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, methodName));

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                    StreamChatResponseAggregator aggregator = StreamChatResponseAggregator.create();

                    return modelCall
                            .get()
                            .doOnNext(aggregator::append)
                            .doOnError(span::recordException)
                            .doFinally(
                                    unuse -> {
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

    public static Mono<ToolResultBlock> traceToolKit(
            Toolkit instance,
            String methodName,
            ToolCallParam toolCallParam,
            Supplier<Mono<ToolResultBlock>> toolKitCall) {
        if (!checkTracingEnabled()) {
            return toolKitCall.get();
        }

        ToolUseBlock toolUseBlock = toolCallParam.getToolUseBlock();

        return Mono.deferContextual(
                ctxView -> {
                    Context parentContext =
                            ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                    ctxView, Context.current());
                    SpanBuilder spanBuilder =
                            getTracer()
                                    .spanBuilder(EXECUTE_TOOL + " " + toolUseBlock.getName())
                                    .setParent(parentContext);

                    spanBuilder.setAllAttributes(getToolRequestAttributes(instance, toolUseBlock));
                    spanBuilder.setAllAttributes(getCommonAttributes());
                    spanBuilder.setAttribute(
                            AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, methodName));

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                    return toolKitCall
                            .get()
                            .doOnSuccess(
                                    result ->
                                            span.setAllAttributes(
                                                    getToolResponseAttributes(result)))
                            .doOnError(span::recordException)
                            .doFinally(
                                    unuse -> {
                                        span.end();
                                    })
                            .contextWrite(
                                    ctx ->
                                            ContextPropagationOperator.storeOpenTelemetryContext(
                                                    ctx, otelContext));
                });
    }

    @SuppressWarnings("rawtypes")
    public static <REQUEST> List<REQUEST> traceFormat(
            AbstractBaseFormatter instance,
            String methodName,
            List<Msg> msgs,
            Supplier<List<REQUEST>> formatCall) {
        if (!checkTracingEnabled()) {
            return formatCall.get();
        }

        String formatterTarget =
                FormatterConverter.getFormatterTarget(instance.getClass().getSimpleName());
        SpanBuilder spanBuilder = getTracer().spanBuilder(FORMAT + " " + formatterTarget);
        spanBuilder.setAllAttributes(getFormatRequestAttributes(instance, msgs));
        spanBuilder.setAllAttributes(getCommonAttributes());
        spanBuilder.setAttribute(AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, methodName));
        Span span = spanBuilder.startSpan();

        List<REQUEST> result = null;
        try (Scope scope = span.makeCurrent()) {
            result = formatCall.get();
            span.setAllAttributes(getFormatResponseAttributes(result));
        } catch (Exception e) {
            span.recordException(e);
        } finally {
            span.end();
        }
        return result;
    }

    // TODO: trace embedding & trace normal functions

    private TelemetryWrappers() {}
}
