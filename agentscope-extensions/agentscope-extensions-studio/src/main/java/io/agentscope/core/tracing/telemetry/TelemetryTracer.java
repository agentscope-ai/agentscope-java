/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.tracing.telemetry;

import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.AGENTSCOPE_FUNCTION_NAME;
import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.GenAiOperationNameAgentScopeIncubatingValues.FORMAT;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getAgentRequestAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getAgentResponseAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getCommonAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getFormatRequestAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getFormatResponseAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getFunctionName;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getLLMRequestAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getLLMResponseAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getToolRequestAttributes;
import static io.agentscope.core.tracing.telemetry.AttributesExtractors.getToolResponseAttributes;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.CHAT;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.EXECUTE_TOOL;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.INVOKE_AGENT;

import io.agentscope.core.Version;
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
import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.telemetry.AttributesExtractors.FormatterConverter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

public class TelemetryTracer implements Tracer {

    private final io.opentelemetry.api.trace.Tracer tracer;

    public TelemetryTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Msg> callAgent(
            AgentBase instance, List<Msg> inputMessages, Supplier<Mono<Msg>> agentCall) {
        return Mono.deferContextual(
                ctxView -> {
                    Context parentContext =
                            ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                    ctxView, Context.current());
                    SpanBuilder spanBuilder =
                            tracer.spanBuilder(INVOKE_AGENT + " " + instance.getName())
                                    .setParent(parentContext);
                    spanBuilder.setAllAttributes(
                            getAgentRequestAttributes(instance, inputMessages));
                    spanBuilder.setAllAttributes(getCommonAttributes());
                    spanBuilder.setAttribute(
                            AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, "callAgent"));

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                    return otelContext
                            .wrapSupplier(agentCall)
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

    @Override
    public Flux<ChatResponse> callModel(
            ChatModelBase instance,
            List<Msg> inputMessages,
            List<ToolSchema> toolSchemas,
            GenerateOptions options,
            Supplier<Flux<ChatResponse>> modelCall) {
        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext =
                            ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                    ctxView, Context.current());
                    SpanBuilder spanBuilder =
                            tracer.spanBuilder(CHAT + " " + instance.getModelName())
                                    .setParent(parentContext);
                    spanBuilder.setAllAttributes(
                            getLLMRequestAttributes(instance, inputMessages, toolSchemas, options));
                    spanBuilder.setAllAttributes(getCommonAttributes());
                    spanBuilder.setAttribute(
                            AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, "callModel"));

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                    StreamChatResponseAggregator aggregator = StreamChatResponseAggregator.create();

                    return otelContext
                            .wrapSupplier(modelCall)
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

    @Override
    public Mono<ToolResultBlock> callTool(
            Toolkit instance,
            ToolCallParam toolCallParam,
            Supplier<Mono<ToolResultBlock>> toolKitCall) {
        ToolUseBlock toolUseBlock = toolCallParam.getToolUseBlock();

        return Mono.deferContextual(
                ctxView -> {
                    Context parentContext =
                            ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                    ctxView, Context.current());
                    SpanBuilder spanBuilder =
                            tracer.spanBuilder(EXECUTE_TOOL + " " + toolUseBlock.getName())
                                    .setParent(parentContext);

                    spanBuilder.setAllAttributes(getToolRequestAttributes(instance, toolUseBlock));
                    spanBuilder.setAllAttributes(getCommonAttributes());
                    spanBuilder.setAttribute(
                            AGENTSCOPE_FUNCTION_NAME, getFunctionName(instance, "callTool"));

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());

                    return otelContext
                            .wrapSupplier(toolKitCall)
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

    @Override
    public <TReq, TResp, TParams> List<TReq> callFormat(
            AbstractBaseFormatter<TReq, TResp, TParams> formatter,
            List<Msg> msgs,
            Supplier<List<TReq>> formatCall) {
        String formatterTarget =
                FormatterConverter.getFormatterTarget(formatter.getClass().getSimpleName());
        SpanBuilder spanBuilder = tracer.spanBuilder(FORMAT + " " + formatterTarget);
        spanBuilder.setAllAttributes(getFormatRequestAttributes(formatter, msgs));
        spanBuilder.setAllAttributes(getCommonAttributes());
        spanBuilder.setAttribute(AGENTSCOPE_FUNCTION_NAME, getFunctionName(formatter, "format"));
        Span span = spanBuilder.startSpan();

        List<TReq> result = null;
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

    @Override
    public <TResp> TResp runWithContext(ContextView reactorCtx, Supplier<TResp> inner) {
        Context otelContext =
                ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                        reactorCtx, Context.current());
        return otelContext.wrapSupplier(inner).get();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final String INSTRUMENTATION_NAME = "agentscope-java";
        private final io.opentelemetry.api.trace.Tracer NOOP_TRACER =
                TracerProvider.noop().get(INSTRUMENTATION_NAME, Version.VERSION);

        private boolean enabled = true;
        private String endpoint;
        private Map<String, String> headers = new HashMap<>();
        private io.opentelemetry.api.trace.Tracer tracer;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Adds a header to be included in OTLP HTTP requests.
         *
         * @param key   The header name
         * @param value The header value
         * @return This builder
         */
        public Builder addHeader(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        /**
         * Sets all headers to be included in OTLP HTTP requests.
         *
         * @param headers Map of header name to value
         * @return This builder
         */
        public Builder headers(Map<String, String> headers) {
            this.headers = new java.util.HashMap<>(headers);
            return this;
        }

        public Builder tracer(io.opentelemetry.api.trace.Tracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public TelemetryTracer build() {
            if (!enabled) {
                return new TelemetryTracer(NOOP_TRACER);
            }

            if (tracer != null) {
                return new TelemetryTracer(tracer);
            }

            var exporterBuilder = OtlpHttpSpanExporter.builder().setEndpoint(endpoint);

            // Add headers for authentication (e.g., Langfuse)
            for (var entry : headers.entrySet()) {
                exporterBuilder.addHeader(entry.getKey(), entry.getValue());
            }

            TracerProvider tracerProvider =
                    SdkTracerProvider.builder()
                            .addSpanProcessor(
                                    BatchSpanProcessor.builder(exporterBuilder.build()).build())
                            .setSampler(Sampler.alwaysOn())
                            .build();

            return new TelemetryTracer(tracerProvider.get(INSTRUMENTATION_NAME, Version.VERSION));
        }
    }
}
