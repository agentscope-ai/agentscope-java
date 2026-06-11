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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class OtelTracingMiddlewareTest {

    private InMemorySpanExporter spanExporter;
    private OtelTracingMiddleware middleware;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                        .build();
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
        middleware = new OtelTracingMiddleware();
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void onAgent_createsInvokeAgentSpan() {
        Agent agent = stubAgent("test-agent", "agent-001");
        AgentInput input = new AgentInput(List.of());

        AgentStartEvent rse = new AgentStartEvent("sess-1", "reply-42", "test-agent");
        Flux<AgentEvent> result = middleware.onAgent(agent, null, input, in -> Flux.just(rse));
        result.collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        SpanData span = spans.get(0);
        assertEquals("invoke_agent test-agent", span.getName());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(
                "invoke_agent",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.operation.name")));
        assertEquals(
                "test-agent",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.agent.name")));
        assertEquals(
                "agent-001",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.agent.id")));
        assertEquals(
                "reply-42",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "agentscope.agent.reply_id")));
    }

    @Test
    void onAgent_recordsErrorOnFailure() {
        Agent agent = stubAgent("err-agent", "agent-002");
        AgentInput input = new AgentInput(List.of());

        Flux<AgentEvent> result =
                middleware.onAgent(
                        agent, null, input, in -> Flux.error(new RuntimeException("boom")));
        try {
            result.collectList().block();
        } catch (Exception ignored) {
        }

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertTrue(spans.get(0).getStatus().getDescription().contains("boom"));
    }

    @Test
    void onModelCall_createsChatSpanWithUsage() {
        Agent agent = stubAgent("model-agent", "agent-003");
        ChatUsage usage = new ChatUsage(100, 50, 1.5);
        ModelCallEndEvent mce = new ModelCallEndEvent("reply-1", usage);

        ModelCallInput input = new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o"));
        Flux<AgentEvent> result = middleware.onModelCall(agent, null, input, in -> Flux.just(mce));
        result.collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        SpanData span = spans.get(0);
        assertEquals("chat gpt-4o", span.getName());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(
                "chat",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.operation.name")));
        assertEquals(
                "gpt-4o",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.request.model")));
        assertEquals(
                100L,
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.input_tokens")));
        assertEquals(
                50L,
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.output_tokens")));
    }

    @Test
    void onActing_createsExecuteToolSpan() {
        Agent agent = stubAgent("tool-agent", "agent-004");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("search")
                        .input(Map.of("q", "hello"))
                        .build();

        ToolResultEndEvent tre =
                new ToolResultEndEvent("reply-1", "call-1", "testTool", ToolResultState.SUCCESS);
        ActingInput input = new ActingInput(List.of(toolCall));
        Flux<AgentEvent> result = middleware.onActing(agent, null, input, in -> Flux.just(tre));
        result.collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        SpanData span = spans.get(0);
        assertEquals("execute_tool search", span.getName());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(
                "execute_tool",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.operation.name")));
        assertEquals(
                "search",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.tool.name")));
        assertEquals(
                "call-1",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.tool.call.id")));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Agent stubAgent(String name, String agentId) {
        return new StubAgent(name, agentId);
    }

    private static class StubAgent implements Agent {
        private final String name;
        private final String agentId;

        StubAgent(String name, String agentId) {
            this.name = name;
            this.agentId = agentId;
        }

        @Override
        public String getAgentId() {
            return agentId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void interrupt() {}

        @Override
        public void interrupt(Msg msg) {}

        @Override
        public Mono<Msg> call(List<Msg> msgs) {
            return Mono.empty();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
            return Mono.empty();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> observe(Msg msg) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> observe(List<Msg> msgs) {
            return Mono.empty();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            return Flux.empty();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            return Flux.empty();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
            return Flux.empty();
        }
    }

    private static final class StubModel implements io.agentscope.core.model.Model {
        private final String name;

        StubModel(String name) {
            this.name = name;
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public Flux<io.agentscope.core.model.ChatResponse> stream(
                List<Msg> messages,
                List<io.agentscope.core.model.ToolSchema> tools,
                io.agentscope.core.model.GenerateOptions options) {
            return Flux.empty();
        }
    }
}
