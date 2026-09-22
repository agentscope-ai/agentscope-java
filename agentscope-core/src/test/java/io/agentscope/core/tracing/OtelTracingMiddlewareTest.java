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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class OtelTracingMiddlewareTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetrySdk;
    private OtelTracingMiddleware middleware;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        spanExporter = InMemorySpanExporter.create();
        openTelemetrySdk = registerGlobalSdk(spanExporter);
        middleware = new OtelTracingMiddleware();
    }

    @AfterEach
    void tearDown() {
        if (openTelemetrySdk != null) {
            openTelemetrySdk.close();
            openTelemetrySdk = null;
        }
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
    void onAgent_keepsTopLevelReplyIdWhenSubagentStarts() {
        Agent agent = stubAgent("parent", "agent-parent");
        AgentStartEvent parentStart = new AgentStartEvent("sess-1", "parent-reply", "parent");
        AgentStartEvent childStart = new AgentStartEvent("sess-1", "child-reply", "child");
        childStart.withSource("parent/child");

        middleware
                .onAgent(
                        agent,
                        null,
                        new AgentInput(List.of()),
                        in -> Flux.just(parentStart, childStart))
                .collectList()
                .block();

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals(
                "parent-reply",
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "agentscope.agent.reply_id")));
    }

    @Test
    void onModelCall_createsChatSpanWithUsage() {
        Agent agent = stubAgent("model-agent", "agent-003");
        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens(100)
                        .outputTokens(50)
                        .cachedTokens(30)
                        .cacheCreationTokens(10)
                        .reasoningTokens(20)
                        .toolUsePromptTokens(15)
                        .time(1.5)
                        .build();
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
        assertEquals(
                30L,
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.cache_read.input_tokens")));
        assertEquals(
                10L,
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.cache_creation.input_tokens")));
        assertEquals(
                20L,
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.reasoning.output_tokens")));
        assertEquals(
                15L,
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "agentscope.usage.tool_use_prompt_tokens")));
    }

    @Test
    void onModelCall_ignoresNullUsage() {
        Agent agent = stubAgent("model-agent-no-usage", "agent-no-usage");
        ModelCallEndEvent mce = new ModelCallEndEvent("reply-no-usage", null);

        ModelCallInput input = new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o"));
        middleware.onModelCall(agent, null, input, in -> Flux.just(mce)).collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(
                null,
                spans.get(0)
                        .getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.input_tokens")));
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

    @Test
    void nestedSpans_linkedAsParentAndChild() {
        Agent agent = stubAgent("nest-agent", "agent-100");
        AgentInput agentIn = new AgentInput(List.of());
        ModelCallInput modelIn = new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o"));
        ToolUseBlock toolCall =
                ToolUseBlock.builder().id("call-x").name("search").input(Map.of()).build();
        ActingInput actingIn = new ActingInput(List.of(toolCall));

        Flux<AgentEvent> result =
                middleware.onAgent(
                        agent,
                        null,
                        agentIn,
                        in ->
                                middleware
                                        .onModelCall(
                                                agent,
                                                null,
                                                modelIn,
                                                m ->
                                                        Flux.just(
                                                                new ModelCallEndEvent(
                                                                        "reply-1",
                                                                        new ChatUsage(1, 1, 0.1))))
                                        .thenMany(
                                                middleware.onActing(
                                                        agent,
                                                        null,
                                                        actingIn,
                                                        a ->
                                                                Flux.just(
                                                                        new ToolResultEndEvent(
                                                                                "reply-1",
                                                                                "call-x",
                                                                                "search",
                                                                                ToolResultState
                                                                                        .SUCCESS)))));
        result.collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(3, spans.size());

        SpanData invoke =
                spans.stream()
                        .filter(s -> s.getName().startsWith("invoke_agent"))
                        .findFirst()
                        .orElseThrow();
        SpanData chat =
                spans.stream()
                        .filter(s -> s.getName().startsWith("chat"))
                        .findFirst()
                        .orElseThrow();
        SpanData exec =
                spans.stream()
                        .filter(s -> s.getName().startsWith("execute_tool"))
                        .findFirst()
                        .orElseThrow();

        // Same trace
        assertEquals(invoke.getTraceId(), chat.getTraceId());
        assertEquals(invoke.getTraceId(), exec.getTraceId());
        // Children point at invoke_agent as parent
        assertEquals(invoke.getSpanId(), chat.getParentSpanId());
        assertEquals(invoke.getSpanId(), exec.getParentSpanId());
    }

    @Test
    void nestedSpans_preservedAcrossThreadHop() {
        Agent agent = stubAgent("async-agent", "agent-101");
        AgentInput agentIn = new AgentInput(List.of());
        ModelCallInput modelIn = new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o"));

        // publishOn() between the two middlewares forces a thread hop — the
        // OTel ThreadLocal-only approach would lose the parent here.
        Flux<AgentEvent> result =
                middleware.onAgent(
                        agent,
                        null,
                        agentIn,
                        in ->
                                Flux.just(new AgentStartEvent("s", "r", "async-agent"))
                                        .publishOn(Schedulers.parallel())
                                        .thenMany(
                                                middleware.onModelCall(
                                                        agent,
                                                        null,
                                                        modelIn,
                                                        m ->
                                                                Flux.just(
                                                                                (AgentEvent)
                                                                                        new ModelCallEndEvent(
                                                                                                "r",
                                                                                                new ChatUsage(
                                                                                                        1,
                                                                                                        1,
                                                                                                        0.1)))
                                                                        .publishOn(
                                                                                Schedulers
                                                                                        .parallel()))));
        result.collectList().block(Duration.ofSeconds(5));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size());

        SpanData invoke =
                spans.stream()
                        .filter(s -> s.getName().startsWith("invoke_agent"))
                        .findFirst()
                        .orElseThrow();
        SpanData chat =
                spans.stream()
                        .filter(s -> s.getName().startsWith("chat"))
                        .findFirst()
                        .orElseThrow();

        assertEquals(invoke.getTraceId(), chat.getTraceId());
        assertEquals(
                invoke.getSpanId(),
                chat.getParentSpanId(),
                "chat span must remain a child of invoke_agent across publishOn");
    }

    @Test
    void onAgent_endsSpanOnCancel() throws InterruptedException {
        Agent agent = stubAgent("cancel-agent", "agent-102");
        AgentInput input = new AgentInput(List.of());

        CountDownLatch subscribed = new CountDownLatch(1);
        Flux<AgentEvent> upstream =
                Flux.<AgentEvent>never().doOnSubscribe(s -> subscribed.countDown());

        AtomicReference<reactor.core.Disposable> disposableRef = new AtomicReference<>();
        disposableRef.set(middleware.onAgent(agent, null, input, in -> upstream).subscribe());

        assertTrue(subscribed.await(2, TimeUnit.SECONDS), "subscription should occur");
        disposableRef.get().dispose();

        // Span ends synchronously inside doOnCancel, but allow a brief moment.
        Thread.sleep(50);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "cancelled stream must still end the span");
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertEquals("cancelled", spans.get(0).getStatus().getDescription());
    }

    @Test
    void onActing_aggregatesAllToolCallIds() {
        Agent agent = stubAgent("multi-tool-agent", "agent-103");
        ToolUseBlock t1 =
                ToolUseBlock.builder().id("call-a").name("search").input(Map.of()).build();
        ToolUseBlock t2 = ToolUseBlock.builder().id("call-b").name("calc").input(Map.of()).build();
        ActingInput input = new ActingInput(List.of(t1, t2));

        ToolResultEndEvent r1 =
                new ToolResultEndEvent("reply-1", "call-a", "search", ToolResultState.SUCCESS);
        ToolResultEndEvent r2 =
                new ToolResultEndEvent("reply-1", "call-b", "calc", ToolResultState.SUCCESS);

        middleware.onActing(agent, null, input, in -> Flux.just(r1, r2)).collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        String callIds =
                spans.get(0)
                        .getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.tool.call.id"));
        assertNotNull(callIds);
        assertTrue(callIds.contains("call-a"));
        assertTrue(callIds.contains("call-b"));
    }

    @Test
    void onActing_multiTool_spanNameTruncatedButAttributeContainsAll() {
        Agent agent = stubAgent("multi-tool-agent2", "agent-104");
        ToolUseBlock t1 = ToolUseBlock.builder().id("c1").name("search").input(Map.of()).build();
        ToolUseBlock t2 = ToolUseBlock.builder().id("c2").name("calc").input(Map.of()).build();
        ToolUseBlock t3 = ToolUseBlock.builder().id("c3").name("fetchUrl").input(Map.of()).build();
        ActingInput input = new ActingInput(List.of(t1, t2, t3));

        ToolResultEndEvent r1 =
                new ToolResultEndEvent("reply-1", "c1", "search", ToolResultState.SUCCESS);
        ToolResultEndEvent r2 =
                new ToolResultEndEvent("reply-1", "c2", "calc", ToolResultState.SUCCESS);
        ToolResultEndEvent r3 =
                new ToolResultEndEvent("reply-1", "c3", "fetchUrl", ToolResultState.SUCCESS);

        middleware.onActing(agent, null, input, in -> Flux.just(r1, r2, r3)).collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        SpanData span = spans.get(0);
        // span name uses first tool + count suffix to cap cardinality
        assertEquals("execute_tool search (+2 more)", span.getName());
        // gen_ai.tool.name attribute still has all names
        String toolNames =
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "gen_ai.tool.name"));
        assertNotNull(toolNames);
        assertTrue(toolNames.contains("search"));
        assertTrue(toolNames.contains("calc"));
        assertTrue(toolNames.contains("fetchUrl"));
    }

    @Test
    void onActing_singleTool_spanNameIsToolName() {
        Agent agent = stubAgent("single-tool-agent", "agent-105");
        ToolUseBlock t1 = ToolUseBlock.builder().id("c1").name("myTool").input(Map.of()).build();
        ActingInput input = new ActingInput(List.of(t1));

        ToolResultEndEvent r1 =
                new ToolResultEndEvent("reply-1", "c1", "myTool", ToolResultState.SUCCESS);

        middleware.onActing(agent, null, input, in -> Flux.just(r1)).collectList().block();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("execute_tool myTool", spans.get(0).getName());
    }

    @Test
    void injectedSdk_recordsHooksOnSdkBAndLeavesGlobalSdkAUntouched() {
        InMemorySpanExporter exporterB = InMemorySpanExporter.create();
        OpenTelemetrySdk sdkB = buildSdk(exporterB, false);
        try {
            OtelTracingMiddleware injected = new OtelTracingMiddleware(sdkB);
            Agent agent = stubAgent("injected-agent", "agent-b");
            AgentStartEvent start = new AgentStartEvent("sess-b", "reply-b", "injected-agent");
            ModelCallEndEvent modelEnd = new ModelCallEndEvent("reply-b", new ChatUsage(1, 1, 0.1));
            ToolUseBlock toolCall =
                    ToolUseBlock.builder().id("call-b").name("search").input(Map.of()).build();
            ToolResultEndEvent toolEnd =
                    new ToolResultEndEvent("reply-b", "call-b", "search", ToolResultState.SUCCESS);

            injected.onAgent(agent, null, new AgentInput(List.of()), in -> Flux.just(start))
                    .collectList()
                    .block();
            injected.onModelCall(
                            agent,
                            null,
                            new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o")),
                            in -> Flux.just(modelEnd))
                    .collectList()
                    .block();
            injected.onActing(
                            agent,
                            null,
                            new ActingInput(List.of(toolCall)),
                            in -> Flux.just(toolEnd))
                    .collectList()
                    .block();

            List<SpanData> spansB = exporterB.getFinishedSpanItems();
            assertEquals(3, spansB.size());
            assertNotNull(spanNamed(spansB, "invoke_agent"));
            assertNotNull(spanNamed(spansB, "chat"));
            assertNotNull(spanNamed(spansB, "execute_tool"));
            for (SpanData span : spansB) {
                assertEquals("io.agentscope", span.getInstrumentationScopeInfo().getName());
                assertTrue(span.hasEnded());
            }
            assertEquals(0, spanExporter.getFinishedSpanItems().size());
            assertGlobalStillExports(openTelemetrySdk, spanExporter, exporterB);
        } finally {
            sdkB.close();
        }
    }

    @Test
    void injectedSdk_preservesNestedParentsAcrossSchedulerHopsAndUpstreamContext() {
        InMemorySpanExporter exporterB = InMemorySpanExporter.create();
        OpenTelemetrySdk sdkB = buildSdk(exporterB, false);
        Span upstream = sdkB.getTracer("io.agentscope").spanBuilder("upstream").startSpan();
        Context upstreamContext = Context.root().with(upstream);
        try {
            OtelTracingMiddleware injected = new OtelTracingMiddleware(sdkB);
            Agent agent = stubAgent("hop-agent", "agent-hop");
            ModelCallInput modelIn =
                    new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o"));
            ToolUseBlock toolCall =
                    ToolUseBlock.builder().id("call-hop").name("search").input(Map.of()).build();
            ActingInput actingIn = new ActingInput(List.of(toolCall));

            injected.onAgent(
                            agent,
                            null,
                            new AgentInput(List.of()),
                            in ->
                                    injected.onModelCall(
                                                    agent,
                                                    null,
                                                    modelIn,
                                                    m ->
                                                            Flux.just(
                                                                    new ModelCallEndEvent(
                                                                            "reply-hop",
                                                                            new ChatUsage(
                                                                                    1, 1, 0.1))))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .publishOn(Schedulers.parallel())
                                            .thenMany(
                                                    injected.onActing(
                                                                    agent,
                                                                    null,
                                                                    actingIn,
                                                                    a ->
                                                                            Flux.just(
                                                                                    new ToolResultEndEvent(
                                                                                            "reply-hop",
                                                                                            "call-hop",
                                                                                            "search",
                                                                                            ToolResultState
                                                                                                    .SUCCESS)))
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic())
                                                            .publishOn(Schedulers.parallel())))
                    .contextWrite(
                            reactorCtx ->
                                    ContextPropagationOperator.storeOpenTelemetryContext(
                                            reactorCtx, upstreamContext))
                    .collectList()
                    .block(Duration.ofSeconds(5));

            List<SpanData> spans = exporterB.getFinishedSpanItems();
            assertEquals(3, spans.size());
            SpanData invoke = spanNamed(spans, "invoke_agent");
            SpanData chat = spanNamed(spans, "chat");
            SpanData exec = spanNamed(spans, "execute_tool");

            assertEquals(upstream.getSpanContext().getTraceId(), invoke.getTraceId());
            assertEquals(upstream.getSpanContext().getSpanId(), invoke.getParentSpanId());
            assertEquals(invoke.getTraceId(), chat.getTraceId());
            assertEquals(invoke.getTraceId(), exec.getTraceId());
            assertEquals(invoke.getSpanId(), chat.getParentSpanId());
            assertEquals(invoke.getSpanId(), exec.getParentSpanId());
            assertEquals(0, spanExporter.getFinishedSpanItems().size());
            assertGlobalStillExports(openTelemetrySdk, spanExporter, exporterB);
        } finally {
            upstream.end();
            sdkB.close();
        }
    }

    @Test
    void defaultConstructor_resolvesGlobalSdkRegisteredAfterConstruction() {
        openTelemetrySdk.close();
        openTelemetrySdk = null;
        GlobalOpenTelemetry.resetForTest();

        OtelTracingMiddleware early = new OtelTracingMiddleware();
        InMemorySpanExporter lateExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk lateSdk = registerGlobalSdk(lateExporter);
        try {
            early.onAgent(
                            stubAgent("late-agent", "agent-late"),
                            null,
                            new AgentInput(List.of()),
                            in ->
                                    Flux.just(
                                            new AgentStartEvent(
                                                    "sess", "reply-late", "late-agent")))
                    .collectList()
                    .block();

            List<SpanData> spans = lateExporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            assertEquals("invoke_agent late-agent", spans.get(0).getName());
            assertEquals("io.agentscope", spans.get(0).getInstrumentationScopeInfo().getName());
            assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
            assertGlobalStillExports(lateSdk, lateExporter, null);
        } finally {
            lateSdk.close();
        }
    }

    @Test
    void injectedSdk_rejectsNullAndNoopRunsDownstreamWithoutSpans() {
        NullPointerException rejected =
                assertThrows(NullPointerException.class, () -> new OtelTracingMiddleware(null));
        assertEquals("openTelemetry must not be null", rejected.getMessage());

        OtelTracingMiddleware noopMiddleware = new OtelTracingMiddleware(OpenTelemetry.noop());
        Agent agent = stubAgent("noop-agent", "agent-noop");
        AgentStartEvent start = new AgentStartEvent("sess-n", "reply-n", "noop-agent");
        ModelCallEndEvent modelEnd = new ModelCallEndEvent("reply-n", new ChatUsage(2, 3, 0.2));
        ToolUseBlock toolCall =
                ToolUseBlock.builder().id("call-n").name("search").input(Map.of()).build();
        ToolResultEndEvent toolEnd =
                new ToolResultEndEvent("reply-n", "call-n", "search", ToolResultState.SUCCESS);

        List<AgentEvent> agentEvents =
                noopMiddleware
                        .onAgent(agent, null, new AgentInput(List.of()), in -> Flux.just(start))
                        .collectList()
                        .block();
        List<AgentEvent> modelEvents =
                noopMiddleware
                        .onModelCall(
                                agent,
                                null,
                                new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o")),
                                in -> Flux.just(modelEnd))
                        .collectList()
                        .block();
        List<AgentEvent> toolEvents =
                noopMiddleware
                        .onActing(
                                agent,
                                null,
                                new ActingInput(List.of(toolCall)),
                                in -> Flux.just(toolEnd))
                        .collectList()
                        .block();

        assertEquals(List.of(start), agentEvents);
        assertEquals(List.of(modelEnd), modelEvents);
        assertEquals(List.of(toolEnd), toolEvents);
        assertEquals(0, spanExporter.getFinishedSpanItems().size());
        assertGlobalStillExports(openTelemetrySdk, spanExporter, null);
    }

    @Test
    void injectedSdk_retainsErrorStatus() {
        InMemorySpanExporter exporterB = InMemorySpanExporter.create();
        OpenTelemetrySdk sdkB = buildSdk(exporterB, false);
        try {
            OtelTracingMiddleware injected = new OtelTracingMiddleware(sdkB);
            Agent agent = stubAgent("err-injected", "agent-err-b");
            runExpectingError(
                    injected.onAgent(
                            agent,
                            null,
                            new AgentInput(List.of()),
                            in -> Flux.error(new RuntimeException("agent-boom"))));
            runExpectingError(
                    injected.onModelCall(
                            agent,
                            null,
                            new ModelCallInput(List.of(), null, null, new StubModel("gpt-4o")),
                            in -> Flux.error(new RuntimeException("model-boom"))));
            runExpectingError(
                    injected.onActing(
                            agent,
                            null,
                            new ActingInput(List.of()),
                            in -> Flux.error(new RuntimeException("tool-boom"))));

            List<SpanData> spans = exporterB.getFinishedSpanItems();
            assertEquals(3, spans.size());
            assertError(spanNamed(spans, "invoke_agent"), "agent-boom");
            assertError(spanNamed(spans, "chat"), "model-boom");
            assertError(spanNamed(spans, "execute_tool"), "tool-boom");
            assertEquals(0, spanExporter.getFinishedSpanItems().size());
        } finally {
            sdkB.close();
        }
    }

    @Test
    void injectedSdk_endsSpanOnCancel() throws InterruptedException {
        InMemorySpanExporter exporterB = InMemorySpanExporter.create();
        OpenTelemetrySdk sdkB = buildSdk(exporterB, false);
        try {
            OtelTracingMiddleware injected = new OtelTracingMiddleware(sdkB);
            CountDownLatch subscribed = new CountDownLatch(1);
            Flux<AgentEvent> upstream =
                    Flux.<AgentEvent>never().doOnSubscribe(s -> subscribed.countDown());

            AtomicReference<reactor.core.Disposable> disposableRef = new AtomicReference<>();
            disposableRef.set(
                    injected.onAgent(
                                    stubAgent("cancel-injected", "agent-cancel-b"),
                                    null,
                                    new AgentInput(List.of()),
                                    in -> upstream)
                            .subscribe());

            assertTrue(subscribed.await(2, TimeUnit.SECONDS), "subscription should occur");
            disposableRef.get().dispose();
            Thread.sleep(50);

            List<SpanData> spans = exporterB.getFinishedSpanItems();
            assertEquals(1, spans.size(), "cancelled stream must still end the span");
            assertTrue(spans.get(0).hasEnded());
            assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
            assertEquals("cancelled", spans.get(0).getStatus().getDescription());
            assertEquals("io.agentscope", spans.get(0).getInstrumentationScopeInfo().getName());
            assertEquals(0, spanExporter.getFinishedSpanItems().size());
        } finally {
            sdkB.close();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static OpenTelemetrySdk registerGlobalSdk(InMemorySpanExporter exporter) {
        return buildSdk(exporter, true);
    }

    private static OpenTelemetrySdk buildSdk(
            InMemorySpanExporter exporter, boolean registerGlobal) {
        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var builder = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider);
        return registerGlobal ? builder.buildAndRegisterGlobal() : builder.build();
    }

    private static SpanData spanNamed(List<SpanData> spans, String prefix) {
        return spans.stream()
                .filter(span -> span.getName().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private static void runExpectingError(Flux<AgentEvent> flux) {
        try {
            flux.collectList().block();
        } catch (Exception ignored) {
        }
    }

    private static void assertError(SpanData span, String description) {
        assertTrue(span.hasEnded());
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertTrue(span.getStatus().getDescription().contains(description));
    }

    /**
     * GlobalOpenTelemetry.get() wraps the registered SDK, so identity is the tracer provider plus a
     * span that only the global exporter receives.
     */
    private static void assertGlobalStillExports(
            OpenTelemetrySdk sdk,
            InMemorySpanExporter globalExporter,
            InMemorySpanExporter otherExporter) {
        assertSame(sdk.getTracerProvider(), GlobalOpenTelemetry.get().getTracerProvider());
        int globalBefore = globalExporter.getFinishedSpanItems().size();
        int otherBefore = otherExporter == null ? 0 : otherExporter.getFinishedSpanItems().size();
        GlobalOpenTelemetry.getTracer("io.agentscope")
                .spanBuilder("global-probe")
                .startSpan()
                .end();
        List<SpanData> globalSpans = globalExporter.getFinishedSpanItems();
        assertEquals(globalBefore + 1, globalSpans.size());
        SpanData probe = globalSpans.get(globalSpans.size() - 1);
        assertEquals("global-probe", probe.getName());
        assertEquals("io.agentscope", probe.getInstrumentationScopeInfo().getName());
        if (otherExporter != null) {
            assertEquals(otherBefore, otherExporter.getFinishedSpanItems().size());
        }
    }

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
