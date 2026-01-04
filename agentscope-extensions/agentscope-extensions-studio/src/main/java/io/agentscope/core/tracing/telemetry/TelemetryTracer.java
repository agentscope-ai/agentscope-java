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
import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.ARMS_GEN_APP_KEY;
import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.ARMS_HOST_IP_KEY;
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
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_SPAN_KIND;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.CHAT;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.EXECUTE_TOOL;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.INVOKE_AGENT;
import static io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME;
import static io.opentelemetry.semconv.incubating.HostIncubatingAttributes.HOST_NAME;

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
import io.agentscope.core.util.HostUtils;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.resources.Resource;
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

    public static final String GENAI_APP = "genai_app";
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

                    // for Arms
                    spanBuilder.setAttribute(GEN_AI_SPAN_KIND, "AGENT");

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

                    // for Arms
                    spanBuilder.setAttribute(GEN_AI_SPAN_KIND, "LLM");

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

                    // for Arms
                    spanBuilder.setAttribute(GEN_AI_SPAN_KIND, "TOOL");

                    Span span = spanBuilder.startSpan();
                    Context otelContext = span.storeInContext(Context.current());
                    span.setAllAttributes(getToolRequestAttributes(instance, toolUseBlock));

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
        private io.opentelemetry.api.trace.Tracer tracer;
        private Map<String, String> headers;

        private String serviceName;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder header(String key, String value) {
            if (headers == null) {
                headers = new HashMap<>();
            }

            headers.put(key, value);
            return this;
        }

        public Builder authentication(String value) {
            return header("Authentication", value);
        }

        public Builder headers(Map<String, String> headers) {
            if (this.headers == null) {
                this.headers = new HashMap<>();
            }

            this.headers.putAll(headers);
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

            OtlpHttpSpanExporterBuilder oseBuilder =
                    OtlpHttpSpanExporter.builder().setEndpoint(endpoint);

            Attributes attributes =
                    Attributes.of(
                            SERVICE_NAME,
                            serviceName,
                            ARMS_GEN_APP_KEY,
                            GENAI_APP,
                            HOST_NAME,
                            HostUtils.getHostname(),
                            ARMS_HOST_IP_KEY,
                            HostUtils.getLocalIpAddressOrEmpty());

            Resource resource = Resource.getDefault().merge(Resource.create(attributes));

            if (headers != null) {
                headers.forEach(oseBuilder::addHeader);
            }

            OtlpHttpSpanExporter exporter = oseBuilder.build();

            TracerProvider tracerProvider =
                    SdkTracerProvider.builder()
                            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                            .setSampler(Sampler.alwaysOn())
                            .setResource(resource)
                            .build();

            return new TelemetryTracer(tracerProvider.get(INSTRUMENTATION_NAME, Version.VERSION));
        }
    }
}
