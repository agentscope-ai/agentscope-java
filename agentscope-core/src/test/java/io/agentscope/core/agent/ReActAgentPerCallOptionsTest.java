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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SuppressWarnings("deprecation")
class ReActAgentPerCallOptionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void shouldSuspendAfterExternalToolCallWithoutCallingModelAgain() {
        AtomicInteger modelCallCount = new AtomicInteger();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            if (modelCallCount.incrementAndGet() > 1) {
                                return Flux.error(
                                        new AssertionError(
                                                "Model must not be called again while awaiting an"
                                                        + " external tool result"));
                            }
                            Map<String, Object> input = Map.of("city", "Hangzhou");
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("call-weather")
                                                                    .name("get_weather")
                                                                    .input(input)
                                                                    .content(
                                                                            "{\"city\":\"Hangzhou\"}")
                                                                    .build()))
                                            .build());
                        });
        ToolSchema schema =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get the current weather for a city")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("city", Map.of("type", "string"))))
                        .build();
        RuntimeContext context =
                RuntimeContext.builder()
                        .put(
                                AgentCallOptions.class,
                                AgentCallOptions.builder().externalToolSchema(schema).build())
                        .build();
        ReActAgent agent =
                ReActAgent.builder().name("external-tool-agent").model(model).maxIters(3).build();

        Msg response = agent.call(List.of(userMessage("weather")), context).block(TIMEOUT);

        assertEquals(1, modelCallCount.get());
        assertEquals(GenerateReason.TOOL_SUSPENDED, response.getGenerateReason());
        assertEquals(1, response.getContentBlocks(ToolUseBlock.class).size());
        assertTrue(response.getContentBlocks(ToolResultBlock.class).get(0).isSuspended());
    }

    @Test
    void shouldIsolatePerCallHooksAndExternalToolSchemas() {
        List<ModelCall> modelCalls = new ArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            modelCalls.add(
                                    new ModelCall(
                                            List.copyOf(invocation.getArgument(0)),
                                            List.copyOf(invocation.getArgument(1)),
                                            invocation.getArgument(2)));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("shared-agent")
                        .sysPrompt("base")
                        .model(model)
                        .maxIters(1)
                        .build();

        agent.call(
                        List.of(userMessage("first")),
                        callContext("session-a", "instruction-a", 0.1, "tool_a"))
                .block(TIMEOUT);
        agent.call(
                        List.of(userMessage("second")),
                        callContext("session-b", "instruction-b", 0.9, "tool_b"))
                .block(TIMEOUT);

        assertEquals(2, modelCalls.size());
        assertCall(modelCalls.get(0), "instruction-a", "instruction-b", 0.1, "tool_a", "tool_b");
        assertCall(modelCalls.get(1), "instruction-b", "instruction-a", 0.9, "tool_b", "tool_a");
        assertTrue(agent.getHooks().isEmpty());
        assertTrue(agent.getToolkit().getToolNames().isEmpty());
    }

    @Test
    void shouldExposeDynamicSkillLoaderOnResponsesIsolatedCall() {
        List<ToolSchema> observedTools = new ArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            observedTools.addAll(invocation.getArgument(1));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });
        AgentSkillRepository repository = mock(AgentSkillRepository.class);
        when(repository.getAllSkills())
                .thenReturn(
                        List.of(
                                new AgentSkill(
                                        "weather",
                                        "Weather instructions",
                                        "Use the weather workflow.",
                                        Map.of())));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("dynamic-skill-agent")
                        .model(model)
                        .skillRepository(repository)
                        .maxIters(1)
                        .build();

        agent.call(
                        List.of(userMessage("first request")),
                        statelessContext("responses-dynamic-skill"))
                .block(TIMEOUT);

        assertTrue(
                observedTools.stream()
                        .anyMatch(tool -> "load_skill_through_path".equals(tool.getName())));
    }

    @Test
    void shouldReuseConfiguredToolkitWhenCallOptionsAreAbsent() {
        AtomicReference<Toolkit> observedToolkit = new AtomicReference<>();
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreActingEvent preActing) {
                            observedToolkit.set(preActing.getToolkit());
                        }
                        return Mono.just(event);
                    }
                };
        ToolSchema schema =
                ToolSchema.builder()
                        .name("external_lookup")
                        .description("request tool")
                        .parameters(Map.of("type", "object"))
                        .build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchemas(List.of(schema));
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("call-1")
                                                                .name("external_lookup")
                                                                .input(Map.of())
                                                                .content("{}")
                                                                .build()))
                                        .build()));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ordinary-call-agent")
                        .model(model)
                        .toolkit(toolkit)
                        .hooks(List.of(hook))
                        .maxIters(1)
                        .build();

        agent.call(
                        List.of(userMessage("lookup")),
                        RuntimeContext.builder().sessionId("ordinary-session").build())
                .block(TIMEOUT);

        assertSame(agent.getToolkit(), observedToolkit.get());
    }

    @Test
    void shouldIsolateConcurrentLegacyStreams() throws InterruptedException {
        CountDownLatch callsStarted = new CountDownLatch(2);
        List<ModelCall> modelCalls = new CopyOnWriteArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String input = messages.get(messages.size() - 1).getTextContent();
                            modelCalls.add(
                                    new ModelCall(
                                            List.copyOf(messages),
                                            List.copyOf(invocation.getArgument(1)),
                                            invocation.getArgument(2)));
                            callsStarted.countDown();
                            assertTrue(callsStarted.await(2, TimeUnit.SECONDS));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("reply-" + input)
                                                                    .build()))
                                            .build());
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("shared-streaming-agent")
                        .model(model)
                        .maxIters(1)
                        .build();
        StreamOptions streamOptions =
                StreamOptions.builder().eventTypes(EventType.REASONING).incremental(true).build();

        Mono<List<Event>> first =
                agent.stream(
                                List.of(userMessage("first")),
                                streamOptions,
                                callContext("stream-a", "instruction-a", 0.1, "tool_a"))
                        .collectList()
                        .subscribeOn(Schedulers.parallel());
        Mono<List<Event>> second =
                agent.stream(
                                List.of(userMessage("second")),
                                streamOptions,
                                callContext("stream-b", "instruction-b", 0.9, "tool_b"))
                        .collectList()
                        .subscribeOn(Schedulers.parallel());

        List<List<Event>> streams =
                Mono.zip(first, second)
                        .map(tuple -> List.of(tuple.getT1(), tuple.getT2()))
                        .block(TIMEOUT);

        assertEquals(2, streams.size());
        assertStreamContainsOnly(streams.get(0), "reply-first", "reply-second");
        assertStreamContainsOnly(streams.get(1), "reply-second", "reply-first");
        assertEquals(2, modelCalls.size());
        ModelCall firstCall = callWithInstruction(modelCalls, "instruction-a");
        ModelCall secondCall = callWithInstruction(modelCalls, "instruction-b");
        assertCall(firstCall, "instruction-a", "instruction-b", 0.1, "tool_a", "tool_b");
        assertCall(secondCall, "instruction-b", "instruction-a", 0.9, "tool_b", "tool_a");
        assertTrue(agent.getHooks().isEmpty());
    }

    @Test
    void shouldDiscardSessionStateAfterStatelessCalls() {
        List<List<Msg>> modelInputs = new CopyOnWriteArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            modelInputs.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });

        ReActAgent agent =
                ReActAgent.builder().name("stateless-agent").model(model).maxIters(1).build();
        RuntimeContext context = statelessContext("ephemeral-session");

        agent.call(List.of(userMessage("first")), context).block(TIMEOUT);
        assertNull(context.getAgentState());
        agent.call(List.of(userMessage("second")), context).block(TIMEOUT);

        assertEquals(2, modelInputs.size());
        assertFalse(messageText(modelInputs.get(1)).contains("first"));
        assertTrue(messageText(modelInputs.get(1)).contains("second"));
        assertNull(context.getAgentState());
    }

    @Test
    void shouldRestoreRuntimeContextAgentStateAfterCallOptionsCall() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build()));
        ReActAgent agent =
                ReActAgent.builder().name("state-restore-agent").model(model).maxIters(1).build();
        AgentState callerState = AgentState.builder().sessionId("parent-session").build();
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("child-session")
                        .agentState(callerState)
                        .put(AgentCallOptions.class, AgentCallOptions.builder().build())
                        .build();

        agent.call(List.of(userMessage("hello")), context).block(TIMEOUT);

        assertSame(callerState, context.getAgentState());
    }

    @Test
    void shouldRetainResolvedAgentStateOnPlainRuntimeContext() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build()));
        ReActAgent agent =
                ReActAgent.builder().name("plain-state-agent").model(model).maxIters(1).build();
        RuntimeContext context = RuntimeContext.builder().sessionId("plain-state-session").build();

        agent.call(List.of(userMessage("hello")), context).block(TIMEOUT);

        assertSame(agent.getAgentState(null, "plain-state-session"), context.getAgentState());
    }

    @Test
    void shouldExcludeConfiguredToolsWhenCallUsesExternalToolsOnly() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            List<ToolSchema> schemas = invocation.getArgument(1);
                            assertEquals(
                                    List.of("frontend_tool"),
                                    schemas.stream().map(ToolSchema::getName).toList());
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchemas(List.of(toolSchema("configured_tool")));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("external-only-agent")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(1)
                        .build();
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("external-only-session")
                        .put(
                                AgentCallOptions.class,
                                AgentCallOptions.builder()
                                        .includeConfiguredTools(false)
                                        .externalToolSchema(toolSchema("frontend_tool"))
                                        .build())
                        .build();

        agent.call(List.of(userMessage("hello")), context).block(TIMEOUT);

        assertEquals(Set.of("configured_tool"), agent.getToolkit().getToolNames());
    }

    @Test
    void shouldNotReuseOrEvictExistingStateForStatelessCall() {
        List<List<Msg>> modelInputs = new CopyOnWriteArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            modelInputs.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });

        ReActAgent agent =
                ReActAgent.builder().name("mixed-state-agent").model(model).maxIters(1).build();
        RuntimeContext stateful = RuntimeContext.builder().sessionId("shared-session").build();

        agent.call(List.of(userMessage("stateful-input")), stateful).block(TIMEOUT);
        AgentState existing = agent.getAgentState(null, "shared-session");
        agent.call(List.of(userMessage("stateless-input")), statelessContext("shared-session"))
                .block(TIMEOUT);

        assertEquals(2, modelInputs.size());
        assertFalse(messageText(modelInputs.get(1)).contains("stateful-input"));
        assertSame(existing, agent.getAgentState(null, "shared-session"));
        assertTrue(messageText(existing.getContext()).contains("stateful-input"));
        assertFalse(messageText(existing.getContext()).contains("stateless-input"));
    }

    @Test
    void shouldSerializeCallsWithConfiguredRuntimeContextAwareHook() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        Map<String, String> observedSessions = new ConcurrentHashMap<>();
        RecordingContextHook hook = new RecordingContextHook(observedSessions);
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String input = messages.get(messages.size() - 1).getTextContent();
                            ChatResponse response =
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("reply-" + input)
                                                                    .build()))
                                            .build();
                            if ("first".equals(input)) {
                                firstStarted.countDown();
                                return Mono.fromFuture(releaseFirst).thenMany(Flux.just(response));
                            }
                            secondStarted.countDown();
                            return Flux.just(response);
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("legacy-context-agent")
                        .model(model)
                        .hooks(List.of(hook))
                        .maxIters(1)
                        .build();

        CompletableFuture<Msg> first =
                agent.call(List.of(userMessage("first")), responsesContext("session-a"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        CompletableFuture<Msg> second =
                agent.call(List.of(userMessage("second")), responsesContext("session-b"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        boolean secondStartedBeforeRelease;
        try {
            secondStartedBeforeRelease = secondStarted.await(200, TimeUnit.MILLISECONDS);
        } finally {
            releaseFirst.complete(null);
        }

        assertEquals("reply-first", first.get(2, TimeUnit.SECONDS).getTextContent());
        assertEquals("reply-second", second.get(2, TimeUnit.SECONDS).getTextContent());
        assertFalse(secondStartedBeforeRelease);
        assertEquals("session-a", observedSessions.get("reply-first"));
        assertEquals("session-b", observedSessions.get("reply-second"));
    }

    @Test
    void shouldNotSerializePlainCallsBecauseOfConfiguredRuntimeContextAwareHook() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        RecordingContextHook hook = new RecordingContextHook(new ConcurrentHashMap<>());
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String input = messages.get(messages.size() - 1).getTextContent();
                            ChatResponse response =
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("reply-" + input)
                                                                    .build()))
                                            .build();
                            if ("first".equals(input)) {
                                firstStarted.countDown();
                                return Mono.fromFuture(releaseFirst).thenMany(Flux.just(response));
                            }
                            secondStarted.countDown();
                            return Flux.just(response);
                        });
        ReActAgent agent =
                ReActAgent.builder()
                        .name("plain-legacy-context-agent")
                        .model(model)
                        .hooks(List.of(hook))
                        .maxIters(1)
                        .build();

        CompletableFuture<Msg> first =
                agent.call(
                                List.of(userMessage("first")),
                                RuntimeContext.builder().sessionId("plain-session-a").build())
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        CompletableFuture<Msg> second =
                agent.call(
                                List.of(userMessage("second")),
                                RuntimeContext.builder().sessionId("plain-session-b").build())
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();

        boolean secondStartedBeforeRelease;
        try {
            secondStartedBeforeRelease = secondStarted.await(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.complete(null);
        }

        assertTrue(secondStartedBeforeRelease);
        assertEquals("reply-first", first.get(2, TimeUnit.SECONDS).getTextContent());
        assertEquals("reply-second", second.get(2, TimeUnit.SECONDS).getTextContent());
    }

    @Test
    void shouldBindRuntimeContextToInvocationLocalContextAwareHook() {
        Map<String, String> observedSessions = new ConcurrentHashMap<>();
        RecordingContextHook hook = new RecordingContextHook(observedSessions);
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("reply-local")
                                                                .build()))
                                        .build()));
        ReActAgent agent =
                ReActAgent.builder().name("local-context-hook-agent").model(model).build();
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("local-session")
                        .put(AgentCallOptions.class, AgentCallOptions.builder().hook(hook).build())
                        .build();

        Msg response = agent.call(List.of(userMessage("local")), context).block(TIMEOUT);

        assertEquals("reply-local", response.getTextContent());
        assertEquals("local-session", observedSessions.get("reply-local"));
        assertNull(hook.runtimeContext);
    }

    @Test
    void shouldSerializeInvocationLocalContextHookWithPlainSameSessionCall() throws Exception {
        CountDownLatch localStarted = new CountDownLatch(1);
        CountDownLatch plainStarted = new CountDownLatch(1);
        CompletableFuture<Void> releaseLocal = new CompletableFuture<>();
        RecordingContextHook hook = new RecordingContextHook(new ConcurrentHashMap<>());
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String input = messages.get(messages.size() - 1).getTextContent();
                            ChatResponse response =
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("reply-" + input)
                                                                    .build()))
                                            .build();
                            if ("local".equals(input)) {
                                localStarted.countDown();
                                return Mono.fromFuture(releaseLocal).thenMany(Flux.just(response));
                            }
                            plainStarted.countDown();
                            return Flux.just(response);
                        });
        ReActAgent agent =
                ReActAgent.builder()
                        .name("mixed-context-hook-agent")
                        .model(model)
                        .maxIters(1)
                        .build();
        RuntimeContext localContext =
                RuntimeContext.builder()
                        .sessionId("shared-session")
                        .put(AgentCallOptions.class, AgentCallOptions.builder().hook(hook).build())
                        .build();
        RuntimeContext plainContext = RuntimeContext.builder().sessionId("shared-session").build();

        CompletableFuture<Msg> local =
                agent.call(List.of(userMessage("local")), localContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(localStarted.await(2, TimeUnit.SECONDS));
        CompletableFuture<Msg> plain =
                agent.call(List.of(userMessage("plain")), plainContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();

        boolean plainStartedBeforeRelease;
        try {
            plainStartedBeforeRelease = plainStarted.await(200, TimeUnit.MILLISECONDS);
        } finally {
            releaseLocal.complete(null);
        }

        assertFalse(plainStartedBeforeRelease);
        assertEquals("reply-local", local.get(2, TimeUnit.SECONDS).getTextContent());
        assertEquals("reply-plain", plain.get(2, TimeUnit.SECONDS).getTextContent());
        assertNull(hook.runtimeContext);
    }

    @Test
    void shouldKeepLegacyHookQueueOrderedWhenQueuedCallIsCancelled() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondSubscribed = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch thirdSubscribed = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        Map<String, String> observedSessions = new ConcurrentHashMap<>();
        RecordingContextHook hook = new RecordingContextHook(observedSessions);
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String input = messages.get(messages.size() - 1).getTextContent();
                            ChatResponse response =
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("reply-" + input)
                                                                    .build()))
                                            .build();
                            if ("first".equals(input)) {
                                firstStarted.countDown();
                                return Mono.fromFuture(releaseFirst).thenMany(Flux.just(response));
                            }
                            if ("second".equals(input)) {
                                secondStarted.countDown();
                            } else {
                                thirdStarted.countDown();
                            }
                            return Flux.just(response);
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("legacy-context-cancel-agent")
                        .model(model)
                        .hooks(List.of(hook))
                        .maxIters(1)
                        .build();

        CompletableFuture<Msg> first =
                agent.call(List.of(userMessage("first")), responsesContext("session-a"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        Disposable second =
                agent.call(List.of(userMessage("second")), responsesContext("session-b"))
                        .doOnSubscribe(ignored -> secondSubscribed.countDown())
                        .subscribeOn(Schedulers.parallel())
                        .subscribe();
        assertTrue(secondSubscribed.await(2, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));

        CompletableFuture<Msg> third =
                agent.call(List.of(userMessage("third")), responsesContext("session-c"))
                        .doOnSubscribe(ignored -> thirdSubscribed.countDown())
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(thirdSubscribed.await(2, TimeUnit.SECONDS));
        second.dispose();
        boolean thirdStartedBeforeRelease;
        try {
            thirdStartedBeforeRelease = thirdStarted.await(200, TimeUnit.MILLISECONDS);
        } finally {
            releaseFirst.complete(null);
        }

        assertFalse(thirdStartedBeforeRelease);
        assertEquals("reply-first", first.get(2, TimeUnit.SECONDS).getTextContent());
        assertEquals("reply-third", third.get(2, TimeUnit.SECONDS).getTextContent());
        assertEquals("session-a", observedSessions.get("reply-first"));
        assertEquals("session-c", observedSessions.get("reply-third"));
    }

    private RuntimeContext callContext(
            String sessionId, String instruction, double temperature, String toolName) {
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent preCall) {
                            preCall.appendSystemContent(instruction);
                        }
                        if (event instanceof PreReasoningEvent preReasoning) {
                            preReasoning.setGenerateOptions(
                                    GenerateOptions.builder().temperature(temperature).build());
                        }
                        return Mono.just(event);
                    }
                };
        ToolSchema schema =
                ToolSchema.builder()
                        .name(toolName)
                        .description("request tool")
                        .parameters(Map.of("type", "object"))
                        .build();
        AgentCallOptions options =
                AgentCallOptions.builder().hook(hook).externalToolSchema(schema).build();
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .put(AgentCallOptions.class, options)
                .build();
    }

    private RuntimeContext statelessContext(String sessionId) {
        AgentCallOptions options = AgentCallOptions.builder().stateless(true).build();
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .put(AgentCallOptions.class, options)
                .build();
    }

    private RuntimeContext responsesContext(String sessionId) {
        return statelessContext(sessionId);
    }

    private ToolSchema toolSchema(String name) {
        return ToolSchema.builder()
                .name(name)
                .description("request tool")
                .parameters(Map.of("type", "object"))
                .build();
    }

    private void assertStreamContainsOnly(
            List<Event> events, String expectedText, String unexpectedText) {
        String text =
                events.stream()
                        .filter(event -> event.getMessage() != null)
                        .map(event -> event.getMessage().getTextContent())
                        .reduce("", String::concat);
        assertTrue(text.contains(expectedText));
        assertFalse(text.contains(unexpectedText));
    }

    private String messageText(List<Msg> messages) {
        return messages.stream().map(Msg::getTextContent).reduce("", String::concat);
    }

    private ModelCall callWithInstruction(List<ModelCall> calls, String instruction) {
        return calls.stream()
                .filter(call -> call.messages().get(0).getTextContent().contains(instruction))
                .findFirst()
                .orElseThrow();
    }

    private void assertCall(
            ModelCall call,
            String expectedInstruction,
            String unexpectedInstruction,
            double expectedTemperature,
            String expectedTool,
            String unexpectedTool) {
        String systemText = call.messages().get(0).getTextContent();
        assertTrue(systemText.contains(expectedInstruction));
        assertFalse(systemText.contains(unexpectedInstruction));
        assertEquals(expectedTemperature, call.options().getTemperature());
        assertTrue(call.tools().stream().anyMatch(tool -> expectedTool.equals(tool.getName())));
        assertFalse(call.tools().stream().anyMatch(tool -> unexpectedTool.equals(tool.getName())));
    }

    private Msg userMessage(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static final class RecordingContextHook implements Hook, RuntimeContextAware {

        private final Map<String, String> observedSessions;
        private volatile RuntimeContext runtimeContext;

        private RecordingContextHook(Map<String, String> observedSessions) {
            this.observedSessions = observedSessions;
        }

        @Override
        public void setRuntimeContext(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PostCallEvent postCallEvent) {
                RuntimeContext current = runtimeContext;
                observedSessions.put(
                        postCallEvent.getFinalMessage().getTextContent(),
                        current != null ? current.getSessionId() : "cleared");
            }
            return Mono.just(event);
        }
    }

    private record ModelCall(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {}
}
