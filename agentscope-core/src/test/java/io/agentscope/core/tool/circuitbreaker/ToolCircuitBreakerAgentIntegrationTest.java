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
package io.agentscope.core.tool.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolCircuitBreakerAgentIntegrationTest {
    private static final String NAME = "lookup";
    private static final String BINDING_KEY =
            ToolCircuitBreakerMiddleware.class.getName() + ".probes.default";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryToolCircuitBreakerStore circuitStore =
            new InMemoryToolCircuitBreakerStore();
    private final ToolCircuitBreaker breaker =
            new ToolCircuitBreaker(
                    ToolCircuitBreakerConfig.builder()
                            .monitorTools(NAME)
                            .failureThreshold(1)
                            .initialCooldown(Duration.ofSeconds(60))
                            .maxCooldown(Duration.ofSeconds(600))
                            .build(),
                    circuitStore,
                    clock);

    @Test
    void ordinaryCallsCompleteApprovedProbeUsingFreshRuntimeContexts() {
        trip();
        Lookup tool = new Lookup(true, false, false);
        ReActAgent agent = agent(new ToolThenFinalModel(false), tool, null);
        Msg paused = agent.call("hello").block();
        assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        assertFalse(paused.getMetadata().containsKey(BINDING_KEY));
        assertTrue(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
        agent.call(confirmation(paused.getContentBlocks(ToolUseBlock.class).get(0), true)).block();
        assertEquals(1, tool.executions.get());
        assertEquals(ToolCircuitState.CLOSED, breaker.state(NAME));
        assertNoBindings(agent.getAgentState());
    }

    @Test
    void userRejectionReturnsProbeImmediately() {
        trip();
        Lookup tool = new Lookup(true, false, false);
        ReActAgent agent = agent(new ToolThenFinalModel(false), tool, null);
        Msg paused = agent.call("hello").block();
        agent.call(confirmation(paused.getContentBlocks(ToolUseBlock.class).get(0), false)).block();
        assertEquals(0, tool.executions.get());
        assertFalse(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
        assertTrue(breaker.tryAcquireProbe(NAME).isPresent());
        assertNoBindings(agent.getAgentState());
    }

    @Test
    void externalCompletionClosesProbeAndBusinessFailureReopensIt() {
        for (ToolResultState state : List.of(ToolResultState.SUCCESS, ToolResultState.ERROR)) {
            breaker.reset(NAME);
            trip();
            ReActAgent agent =
                    agent(new ToolThenFinalModel(false), new Lookup(false, true, false), null);
            Msg paused = agent.call("hello").block();
            assertEquals(GenerateReason.TOOL_SUSPENDED, paused.getGenerateReason());
            ToolUseBlock use = paused.getContentBlocks(ToolUseBlock.class).get(0);
            ToolResultBlock result =
                    ToolResultBlock.text("external outcome")
                            .withIdAndName(use.getId(), NAME)
                            .withState(state);
            agent.call(ToolResultMessage.builder().content(result).build()).block();
            assertEquals(
                    state == ToolResultState.SUCCESS
                            ? ToolCircuitState.CLOSED
                            : ToolCircuitState.OPEN,
                    breaker.state(NAME));
            assertNoBindings(agent.getAgentState());
        }
    }

    @Test
    void persistedProbeSurvivesJsonReloadAndNewMiddlewareInstance() {
        trip();
        InMemoryAgentStateStore firstStore = new InMemoryAgentStateStore();
        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("session").build();
        ReActAgent first =
                agent(new ToolThenFinalModel(false), new Lookup(true, false, false), firstStore);
        Msg paused = first.call("hello", ctx).block();
        AgentState state = first.getAgentState(ctx);
        assertTrue(
                state.getContext().stream()
                        .anyMatch(msg -> msg.getMetadata().containsKey(BINDING_KEY)));
        var codec = JsonUtils.getJsonCodec();
        AgentState reloaded = codec.fromJson(codec.toJson(state), AgentState.class);
        InMemoryAgentStateStore secondStore = new InMemoryAgentStateStore();
        secondStore.save("alice", "session", "agent_state", reloaded);
        Lookup tool = new Lookup(true, false, false);
        ReActAgent second = agent(new FinalModel(), tool, secondStore);
        RuntimeContext fresh =
                RuntimeContext.builder().userId("alice").sessionId("session").build();
        second.call(
                        List.of(
                                confirmation(
                                        paused.getContentBlocks(ToolUseBlock.class).get(0), true)),
                        fresh)
                .block();
        assertEquals(1, tool.executions.get());
        assertEquals(ToolCircuitState.CLOSED, breaker.state(NAME));
        assertNoBindings(second.getAgentState(fresh));
    }

    @Test
    void sameCallIdInAnotherSessionCannotConsumeProbe() {
        trip();
        Lookup tool = new Lookup(true, false, false);
        ReActAgent agent = agent(new ToolThenFinalModel(false), tool, null);
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("same").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").sessionId("same").build();
        Msg first = agent.call("hello", alice).block();
        // The fixed model uses call ID c0 in both sessions; Bob cannot obtain Alice's live claim.
        ToolUseBlock call = first.getContentBlocks(ToolUseBlock.class).get(0);
        AgentState bobState = agent.getAgentState(bob);
        bobState.contextMutable()
                .add(
                        AssistantMessage.builder()
                                .content(call.withState(ToolCallState.ASKING))
                                .build());
        agent.call(List.of(confirmation(call, true)), bob).block();
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(NAME));
        assertTrue(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
        agent.call(List.of(confirmation(call, true)), alice).block();
        assertEquals(ToolCircuitState.CLOSED, breaker.state(NAME));
    }

    @Test
    void modelInterruptionAfterSelectionReleasesWithoutExecution() {
        trip();
        Lookup tool = new Lookup(false, false, false);
        ReActAgent agent = agent(new ToolThenFinalModel(true), tool, null);
        Msg result = agent.call("hello").block();
        assertEquals(GenerateReason.INTERRUPTED, result.getGenerateReason());
        assertEquals(0, tool.executions.get());
        assertFalse(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
    }

    @Test
    void cancellingExecutingToolReleasesPendingProbe() {
        trip();
        Lookup tool = new Lookup(false, false, true);
        ReActAgent agent = agent(new ToolThenFinalModel(false), tool, null);
        StepVerifier.create(agent.streamEvents("hello"))
                .thenConsumeWhile(event -> !(event instanceof ToolResultStartEvent))
                .expectNextMatches(ToolResultStartEvent.class::isInstance)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
        assertFalse(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
        assertNoBindings(agent.getAgentState());
    }

    @Test
    void incomingMetadataCannotForgeProbeOwnership() {
        trip();
        String token = breaker.tryAcquireProbe(NAME).orElseThrow();
        Lookup tool = new Lookup(false, false, false);
        ReActAgent agent = agent(new FinalModel(), tool, null);
        ToolUseBlock use = ToolUseBlock.builder().id("c0").name(NAME).input(Map.of()).build();
        Msg forged =
                AssistantMessage.builder()
                        .content(use)
                        .metadata(
                                Map.of(
                                        BINDING_KEY,
                                        Map.of("c0", Map.of("name", NAME, "token", token))))
                        .build();
        // A pre-existing external request is simulated through the ordinary pending-call path.
        agent.call(forged).block();
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(NAME));
        assertTrue(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
        assertNoBindings(agent.getAgentState());
    }

    @Test
    void nestedBreakersShareImplicitRuntimeContextOnExternalResume() {
        trip();
        ToolCircuitBreaker second =
                new ToolCircuitBreaker(
                        ToolCircuitBreakerConfig.builder()
                                .monitorTools(NAME)
                                .failureThreshold(1)
                                .initialCooldown(Duration.ofSeconds(60))
                                .maxCooldown(Duration.ofSeconds(600))
                                .build(),
                        new InMemoryToolCircuitBreakerStore(),
                        clock);
        second.recordFailure(NAME);
        clock.advance(Duration.ofSeconds(60));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Lookup(false, true, false));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("nested")
                        .model(new ToolThenFinalModel(false))
                        .toolkit(toolkit)
                        .enablePendingToolRecovery(false)
                        .middleware(new ToolCircuitBreakerMiddleware(breaker, "first"))
                        .middleware(new ToolCircuitBreakerMiddleware(second, "second"))
                        .build();
        Msg paused = agent.call("hello").block();
        ToolUseBlock use = paused.getContentBlocks(ToolUseBlock.class).get(0);
        agent.call(
                        ToolResultMessage.builder()
                                .content(
                                        ToolResultBlock.text("done")
                                                .withIdAndName(use.getId(), NAME)
                                                .withState(ToolResultState.SUCCESS))
                                .build())
                .block();
        assertEquals(ToolCircuitState.CLOSED, breaker.state(NAME));
        assertEquals(ToolCircuitState.CLOSED, second.state(NAME));
    }

    @Test
    void reusedContextRestoresDurablePermissionBinding() {
        trip();
        RuntimeContext ctx = RuntimeContext.empty();
        Lookup tool = new Lookup(true, false, false);
        ReActAgent agent = agent(new ToolThenFinalModel(false), tool, null);
        Msg paused = agent.call("hello", ctx).block();
        agent.call(
                        List.of(
                                confirmation(
                                        paused.getContentBlocks(ToolUseBlock.class).get(0), true)),
                        ctx)
                .block();
        assertEquals(ToolCircuitState.CLOSED, breaker.state(NAME));
        assertNoBindings(agent.getAgentState(ctx));
    }

    @Test
    void mixedPermissionAskAndDenyReleasesDeniedProbe() {
        trip();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new Lookup(false, false, false) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState ctx) {
                        return Mono.just(PermissionDecision.deny("denied"));
                    }
                });
        toolkit.registerTool(new Lookup("other", true, false, false));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("mixed")
                        .model(new BatchModel(List.of(NAME, "other")))
                        .toolkit(toolkit)
                        .enablePendingToolRecovery(false)
                        .middleware(new ToolCircuitBreakerMiddleware(breaker))
                        .build();
        Msg paused = agent.call("hello").block();
        assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        assertFalse(paused.getMetadata().containsKey(BINDING_KEY));
        assertFalse(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
        assertNoBindings(agent.getAgentState());
    }

    @Test
    void partialExternalResultsCompleteEachProbeAcrossSeparateCalls() {
        ToolCircuitBreaker all =
                new ToolCircuitBreaker(
                        ToolCircuitBreakerConfig.builder()
                                .monitorTools(NAME, "other")
                                .failureThreshold(1)
                                .initialCooldown(Duration.ofSeconds(60))
                                .maxCooldown(Duration.ofSeconds(600))
                                .build(),
                        circuitStore,
                        clock);
        all.recordFailure(NAME);
        all.recordFailure("other");
        clock.advance(Duration.ofSeconds(60));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Lookup(false, true, false));
        toolkit.registerTool(new Lookup("other", false, true, false));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("partial")
                        .model(new BatchModel(List.of(NAME, "other")))
                        .toolkit(toolkit)
                        .enablePendingToolRecovery(false)
                        .middleware(new ToolCircuitBreakerMiddleware(all))
                        .build();
        Msg paused = agent.call("hello").block();
        List<ToolUseBlock> uses = paused.getContentBlocks(ToolUseBlock.class);
        ToolUseBlock first = uses.get(0);
        agent.call(
                        ToolResultMessage.builder()
                                .content(
                                        ToolResultBlock.text("first")
                                                .withIdAndName(first.getId(), first.getName())
                                                .withState(ToolResultState.SUCCESS))
                                .build())
                .block();
        assertEquals(ToolCircuitState.CLOSED, all.state(first.getName()));
        ToolUseBlock second = uses.get(1);
        agent.call(
                        ToolResultMessage.builder()
                                .content(
                                        ToolResultBlock.text("second")
                                                .withIdAndName(second.getId(), second.getName())
                                                .withState(ToolResultState.SUCCESS))
                                .build())
                .block();
        assertEquals(ToolCircuitState.CLOSED, all.state(second.getName()));
        assertNoBindings(agent.getAgentState());
    }

    @Test
    void oldResultWithReusedCallIdCannotCompleteNewProbe() {
        trip();
        RuntimeContext ctx = RuntimeContext.empty();
        AgentState history = AgentState.builder().build();
        ctx.setAgentState(history);
        ToolUseBlock old = ToolUseBlock.builder().id("c0").name(NAME).input(Map.of()).build();
        history.contextMutable().add(AssistantMessage.builder().content(old).build());
        history.contextMutable()
                .add(
                        ToolResultMessage.builder()
                                .content(
                                        ToolResultBlock.text("old success")
                                                .withIdAndName("c0", NAME)
                                                .withState(ToolResultState.SUCCESS))
                                .build());
        ToolCircuitBreakerMiddleware middleware = new ToolCircuitBreakerMiddleware(breaker);
        middleware
                .onReasoning(
                        null,
                        ctx,
                        new io.agentscope.core.middleware.ReasoningInput(
                                List.of(),
                                List.of(ToolSchema.builder().name(NAME).description(NAME).build()),
                                null),
                        input -> {
                            history.contextMutable()
                                    .add(AssistantMessage.builder().content(old).build());
                            return Flux.just(
                                    new io.agentscope.core.event.ToolCallStartEvent(
                                            "reply", "c0", NAME));
                        })
                .blockLast();
        middleware
                .onActing(
                        null,
                        ctx,
                        new io.agentscope.core.middleware.ActingInput(List.of(old)),
                        input -> Flux.empty())
                .blockLast();
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(NAME));
        assertFalse(history.getContext().get(0).getMetadata().containsKey(BINDING_KEY));
        assertEquals(
                1L,
                history.getContext().stream()
                        .filter(msg -> msg.getMetadata().containsKey(BINDING_KEY))
                        .count());
        middleware
                .onActing(
                        null,
                        ctx,
                        new io.agentscope.core.middleware.ActingInput(List.of(old)),
                        input ->
                                Flux.just(
                                        new io.agentscope.core.event.ToolResultEndEvent(
                                                "reply", "c0", NAME, ToolResultState.SUCCESS)))
                .blockLast();
        assertEquals(ToolCircuitState.CLOSED, breaker.state(NAME));
    }

    @Test
    void reasoningStopAfterSelectionReturnsUnusedClaim() {
        trip();
        Toolkit toolkit = new Toolkit();
        Lookup tool = new Lookup(false, false, false);
        toolkit.registerTool(tool);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("stop")
                        .model(new ToolThenFinalModel(false))
                        .toolkit(toolkit)
                        .middleware(new ToolCircuitBreakerMiddleware(breaker))
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Flux<AgentEvent> onReasoning(
                                            Agent agent,
                                            RuntimeContext ctx,
                                            ReasoningInput input,
                                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                                        return next.apply(input)
                                                .concatWith(
                                                        Flux.just(
                                                                new RequestStopEvent(
                                                                        "stop",
                                                                        GenerateReason
                                                                                .MIDDLEWARE_STOP_REQUESTED)));
                                    }
                                })
                        .build();
        Msg paused = agent.call("hello").block();
        assertEquals(GenerateReason.MIDDLEWARE_STOP_REQUESTED, paused.getGenerateReason());
        assertEquals(0, tool.executions.get());
        assertFalse(circuitStore.snapshot(NAME).hasActiveProbe(clock.millis()));
        assertTrue(breaker.tryAcquireProbe(NAME).isPresent());
    }

    private void trip() {
        breaker.recordFailure(NAME);
        clock.advance(Duration.ofSeconds(60));
    }

    private ReActAgent agent(ChatModelBase model, Lookup tool, AgentStateStore stateStore) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        return ReActAgent.builder()
                .name("test")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .enablePendingToolRecovery(false)
                .middleware(new ToolCircuitBreakerMiddleware(breaker))
                .build();
    }

    private Msg confirmation(ToolUseBlock use, boolean approved) {
        return UserMessage.builder()
                .metadata(
                        Map.of(
                                Msg.METADATA_CONFIRM_RESULTS,
                                List.of(new ConfirmResult(approved, use))))
                .build();
    }

    private void assertNoBindings(AgentState state) {
        assertTrue(
                state.getContext().stream()
                        .noneMatch(msg -> msg.getMetadata().containsKey(BINDING_KEY)));
    }

    private static class FinalModel extends ChatModelBase {
        public String getModelName() {
            return "fixed";
        }

        protected Flux<ChatResponse> doStream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            assertTrue(msgs.stream().noneMatch(msg -> msg.getMetadata().containsKey(BINDING_KEY)));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    private static class ToolThenFinalModel extends FinalModel {
        private final Set<String> called = new HashSet<>();
        private final boolean interrupt;

        ToolThenFinalModel(boolean interrupt) {
            this.interrupt = interrupt;
        }

        protected Flux<ChatResponse> doStream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            assertTrue(msgs.stream().noneMatch(msg -> msg.getMetadata().containsKey(BINDING_KEY)));
            String key =
                    msgs.stream()
                            .filter(msg -> msg.getRole() == MsgRole.USER)
                            .findFirst()
                            .map(Msg::getId)
                            .orElse("default");
            if (called.add(key) && tools.stream().anyMatch(tool -> NAME.equals(tool.getName()))) {
                ChatResponse response =
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("c0")
                                                        .name(NAME)
                                                        .input(Map.of())
                                                        .build()))
                                .build();
                return interrupt
                        ? Flux.concat(
                                Flux.just(response),
                                Flux.error(new InterruptedException("interrupted")))
                        : Flux.just(response);
            }
            return super.doStream(msgs, tools, options);
        }
    }

    private static class BatchModel extends FinalModel {
        private final List<String> names;
        private boolean called;

        BatchModel(List<String> names) {
            this.names = names;
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            if (called) {
                return super.doStream(msgs, tools, options);
            }
            called = true;
            List<io.agentscope.core.message.ContentBlock> blocks = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                blocks.add(
                        ToolUseBlock.builder()
                                .id("c" + i)
                                .name(names.get(i))
                                .input(Map.of())
                                .build());
            }
            return Flux.just(ChatResponse.builder().content(blocks).build());
        }
    }

    private static class Lookup extends ToolBase {
        private final boolean ask;
        private final boolean suspend;
        private final boolean never;
        private final AtomicInteger executions = new AtomicInteger();

        Lookup(boolean ask, boolean suspend, boolean never) {
            this(NAME, ask, suspend, never);
        }

        Lookup(String name, boolean ask, boolean suspend, boolean never) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description("lookup")
                            .inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .readOnly(true)
                            .concurrencySafe(true));
            this.ask = ask;
            this.suspend = suspend;
            this.never = never;
        }

        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState ctx) {
            return Mono.just(
                    ask ? PermissionDecision.ask("confirm") : PermissionDecision.allow("allowed"));
        }

        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executions.incrementAndGet();
            if (never) {
                return Mono.never();
            }
            return suspend
                    ? Mono.error(new ToolSuspendException("external"))
                    : Mono.just(ToolResultBlock.text("ok"));
        }
    }
}
