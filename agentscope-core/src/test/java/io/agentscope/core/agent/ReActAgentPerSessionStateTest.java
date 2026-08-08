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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.legacy.ToolkitState;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Per-(userId, sessionId) state access / persistence API on {@link ReActAgent}. */
@DisplayName("ReActAgent per-session state API")
class ReActAgentPerSessionStateTest {

    private static final class NoopModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "noop";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private ReActAgent agent(AgentStateStore store) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hi")
                .model(new NoopModel())
                .stateStore(store)
                .build();
    }

    @Test
    @DisplayName("fresh slots inherit default tool groups without overriding persisted state")
    void freshSlotsInheritDefaultToolGroupsWithoutOverridingPersistedState() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        store.save(
                "u1",
                "persisted-empty",
                "agent_state",
                AgentState.builder().userId("u1").sessionId("persisted-empty").build());

        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("default-active", "Enabled during agent construction");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new NoopModel())
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build();

        assertEquals(
                List.of("default-active"),
                agent.getAgentState("u1", "fresh").getToolContext().getActivatedGroups());
        assertTrue(
                agent.getAgentState("u1", "persisted-empty")
                        .getToolContext()
                        .getActivatedGroups()
                        .isEmpty(),
                "An explicitly persisted empty group list must remain empty");
    }

    @Test
    @DisplayName("legacy empty tool groups remain explicitly empty")
    void legacyEmptyToolGroupsAreNotMistakenForMissingState() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        store.save("u1", "legacy-empty", "toolkit_activeGroups", new ToolkitState(List.of()));

        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("default-active", "Enabled during agent construction");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new NoopModel())
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build();

        assertTrue(
                agent.getAgentState("u1", "legacy-empty")
                        .getToolContext()
                        .getActivatedGroups()
                        .isEmpty(),
                "A present v1 toolkit_activeGroups=[] value must override fresh defaults");
    }

    @Test
    @DisplayName("getAgentState(uid,sid) caches and isolates per slot")
    void cachesAndIsolatesPerSlot() {
        ReActAgent agent = agent(new InMemoryAgentStateStore());

        AgentState s1 = agent.getAgentState("u1", "sessA");
        AgentState s1Again = agent.getAgentState("u1", "sessA");
        AgentState s2 = agent.getAgentState("u2", "sessB");

        assertSame(s1, s1Again, "same slot must return the cached instance");
        assertNotSame(s1, s2, "different slots must be distinct instances");

        s1.getPlanModeContext().setPlanActive(true);
        assertTrue(s1.getPlanModeContext().isPlanActive());
        assertFalse(
                s2.getPlanModeContext().isPlanActive(),
                "mutating one slot must not leak into another");
    }

    @Test
    @DisplayName("saveAgentState(uid,sid) round-trips through the store into a fresh engine")
    void savePersistsPerSlot() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent = agent(store);

        agent.getAgentState("u1", "sessA").getPlanModeContext().setPlanActive(true);
        agent.getAgentState("u1", "sessA").setSummary("remembered");
        agent.saveAgentState("u1", "sessA");

        // A brand-new engine over the same store must load the persisted slot state.
        ReActAgent reborn = agent(store);
        AgentState loaded = reborn.getAgentState("u1", "sessA");
        assertTrue(loaded.getPlanModeContext().isPlanActive());
        assertEquals("remembered", loaded.getSummary());

        // An untouched slot stays fresh.
        AgentState other = reborn.getAgentState("u1", "other");
        assertFalse(other.getPlanModeContext().isPlanActive());
        assertEquals("", other.getSummary());
    }

    @Test
    @DisplayName("clearContext removes one session's conversation and persists the same session")
    void clearContextClearsAndPersistsOneSession() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent = agent(store);
        AgentState target = agent.getAgentState("u1", "sessA");
        target.contextMutable().add(userMsg("forget this"));
        target.setSummary("old summary");
        target.getPlanModeContext().setPlanActive(true);
        agent.saveAgentState("u1", "sessA");

        AgentState other = agent.getAgentState("u1", "sessB");
        other.contextMutable().add(userMsg("keep this"));
        other.setSummary("other summary");
        agent.saveAgentState("u1", "sessB");

        agent.clearContext("u1", "sessA");

        assertEquals("sessA", target.getSessionId());
        assertTrue(target.getContext().isEmpty());
        assertEquals("", target.getSummary());
        assertTrue(target.getPlanModeContext().isPlanActive(), "non-context state is preserved");
        assertEquals(List.of("keep this"), allText(other));
        assertEquals("other summary", other.getSummary());

        ReActAgent reborn = agent(store);
        AgentState restored = reborn.getAgentState("u1", "sessA");
        assertEquals("sessA", restored.getSessionId());
        assertTrue(restored.getContext().isEmpty());
        assertEquals("", restored.getSummary());
        assertTrue(restored.getPlanModeContext().isPlanActive());
    }

    @Test
    @DisplayName("clearContext uses the session from RuntimeContext")
    void clearContextUsesRuntimeContext() {
        ReActAgent agent = agent(new InMemoryAgentStateStore());
        agent.getAgentState("u1", "sessA").contextMutable().add(userMsg("forget this"));
        agent.getAgentState("u1", "sessB").contextMutable().add(userMsg("keep this"));

        agent.clearContext(RuntimeContext.builder().userId("u1").sessionId("sessA").build());

        assertTrue(agent.getAgentState("u1", "sessA").getContext().isEmpty());
        assertEquals(List.of("keep this"), allText(agent.getAgentState("u1", "sessB")));
    }

    @Test
    @DisplayName("clearContext reloads persisted state before clearing conversation")
    void clearContextReloadsPersistedStateBeforeClearingConversation(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        ReActAgent staleAgent = agent(store);
        AgentState staleState = staleAgent.getAgentState("u1", "sessA");
        staleState.contextMutable().add(userMsg("stale context"));
        staleAgent.saveAgentState("u1", "sessA");

        ReActAgent writerAgent = agent(store);
        AgentState latestState = writerAgent.getAgentState("u1", "sessA");
        latestState.contextMutable().add(userMsg("latest context"));
        latestState.setSummary("latest summary");
        latestState.getPlanModeContext().setPlanActive(true);
        writerAgent.saveAgentState("u1", "sessA");

        staleAgent.clearContext("u1", "sessA");

        ReActAgent restoredAgent = agent(store);
        AgentState restoredState = restoredAgent.getAgentState("u1", "sessA");
        assertTrue(restoredState.getContext().isEmpty());
        assertEquals("", restoredState.getSummary());
        assertTrue(
                restoredState.getPlanModeContext().isPlanActive(),
                "the latest non-conversation state must be preserved");
    }

    @Test
    @DisplayName("clearContext can clear a persisted session not cached in this agent")
    void clearContextClearsPersistedSessionWithoutLocalCache(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        ReActAgent writerAgent = agent(store);
        AgentState persistedState = writerAgent.getAgentState("u1", "sessA");
        persistedState.contextMutable().add(userMsg("persisted context"));
        persistedState.setSummary("persisted summary");
        persistedState.getPlanModeContext().setPlanActive(true);
        writerAgent.saveAgentState("u1", "sessA");

        ReActAgent freshAgent = agent(store);
        freshAgent.clearContext("u1", "sessA");

        ReActAgent restoredAgent = agent(store);
        AgentState restoredState = restoredAgent.getAgentState("u1", "sessA");
        assertTrue(restoredState.getContext().isEmpty());
        assertEquals("", restoredState.getSummary());
        assertTrue(restoredState.getPlanModeContext().isPlanActive());
    }

    @Test
    @DisplayName("clearContext preserves in-memory non-conversation state without a store")
    void clearContextPreservesInMemoryNonConversationStateWithoutStore() {
        ReActAgent agent =
                ReActAgent.builder().name("asst").sysPrompt("hi").model(new NoopModel()).build();
        AgentState state = agent.getAgentState("u1", "sessA");
        state.contextMutable().add(userMsg("forget this"));
        state.setSummary("old summary");
        state.getPlanModeContext().setPlanActive(true);

        agent.clearContext("u1", "sessA");

        AgentState restored = agent.getAgentState("u1", "sessA");
        assertSame(state, restored);
        assertTrue(restored.getContext().isEmpty());
        assertEquals("", restored.getSummary());
        assertTrue(restored.getPlanModeContext().isPlanActive());
    }

    @Test
    @DisplayName("clearContext falls back to the default session for absent session identity")
    void clearContextFallsBackToDefaultSession() {
        ReActAgent agent = agent(new InMemoryAgentStateStore());
        String defaultSessionId = agent.getDefaultSessionId();
        AgentState defaultState = agent.getAgentState(null, defaultSessionId);

        defaultState.contextMutable().add(userMsg("clear through null context"));
        agent.clearContext((RuntimeContext) null);
        assertTrue(agent.getAgentState(null, defaultSessionId).getContext().isEmpty());

        defaultState.contextMutable().add(userMsg("clear through blank session id"));
        agent.clearContext(null, " ");
        assertTrue(agent.getAgentState(null, defaultSessionId).getContext().isEmpty());
    }

    @Test
    @DisplayName("replacePermissionContext updates and persists only the targeted slot")
    void replacePermissionContextUpdatesOnlyTargetSlot() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent = agent(store);
        PermissionRule denyRule =
                new PermissionRule("blocked_tool", null, PermissionBehavior.DENY, "parent-policy");
        PermissionContextState replacement =
                PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .addDenyRule("blocked_tool", denyRule)
                        .build();

        agent.replacePermissionContext("u1", "sessA", replacement);

        assertEquals(replacement, agent.getAgentState("u1", "sessA").getPermissionContext());
        assertTrue(
                agent.getAgentState("u1", "sessB").getPermissionContext().isTrivial(),
                "replacing one slot must not alter another slot");

        ReActAgent reborn = agent(store);
        assertEquals(
                replacement,
                reborn.getAgentState("u1", "sessA").getPermissionContext(),
                "the replacement must survive state-store reload");
    }

    @Test
    @DisplayName("user interrupt persists recovery state to the store")
    void userInterruptPersistsRecoveryState() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CountDownLatch subscribed = new CountDownLatch(1);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new DelayedFirstChunkModel(subscribed))
                        .stateStore(store)
                        .build();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("sessA").build();

        CompletableFuture<Msg> future =
                agent.call(List.of(userMsg("hello")), ctx)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();

        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "model stream should start");
        agent.interrupt("u1", "sessA");

        Msg reply = future.get(5, TimeUnit.SECONDS);
        assertEquals(
                "I noticed that you have interrupted me. What can I do for you?",
                reply.getTextContent());
        assertEquals(GenerateReason.INTERRUPTED, reply.getGenerateReason());

        ReActAgent reborn = agent(store);
        AgentState restoredState = reborn.getAgentState("u1", "sessA");
        List<String> texts = allText(restoredState);
        assertTrue(texts.contains("hello"), "user input should remain in persisted session state");
        assertTrue(
                texts.contains("I noticed that you have interrupted me. What can I do for you?"),
                "interrupt recovery message should be persisted to the state store");
        Msg restoredRecovery =
                restoredState.getContext().stream()
                        .filter(
                                msg ->
                                        "I noticed that you have interrupted me. What can I do for you?"
                                                .equals(msg.getTextContent()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(GenerateReason.INTERRUPTED, restoredRecovery.getGenerateReason());
    }

    @Test
    @DisplayName("user interrupt drops a partial streaming tool call from persisted reasoning")
    void userInterruptDropsPartialStreamingToolCall() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        GatedReasoningModel model =
                new GatedReasoningModel(
                        List.of(
                                TextBlock.builder().text("partial response").build(),
                                ToolUseBlock.builder()
                                        .id("call-streaming")
                                        .name("echo")
                                        .content("{\"value\":")
                                        .build()));
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("streaming").build();
        ReActAgent first =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(model)
                        .stateStore(store)
                        .build();

        CompletableFuture<Msg> future =
                first.call(List.of(userMsg("hello")), ctx)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(
                model.afterInitialChunks.await(5, TimeUnit.SECONDS),
                "partial tool call should be consumed before interrupt");

        first.interrupt(ctx);
        model.release.tryEmitEmpty();

        Msg reply = future.get(5, TimeUnit.SECONDS);
        assertEquals(GenerateReason.INTERRUPTED, reply.getGenerateReason());

        StrictHistoryModel strictModel = new StrictHistoryModel();
        ReActAgent restored =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(strictModel)
                        .stateStore(store)
                        .enablePendingToolRecovery(true)
                        .build();
        AgentState restoredState = restored.getAgentState("u1", "streaming");
        assertFalse(hasToolUse(restoredState, "call-streaming"));
        assertTrue(allText(restoredState).contains("partial response"));

        Msg continued =
                restored.call(List.of(userMsg("continue")), ctx).block(Duration.ofSeconds(5));
        assertEquals("continued", continued.getTextContent());
        assertTrue(strictModel.unresolvedIds.isEmpty());
    }

    @Test
    @DisplayName(
            "user interrupt before acting drops a completed tool call from persisted reasoning")
    void userInterruptBeforeActingDropsCompletedToolCall() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        GateReasoningCompletionMiddleware gate = new GateReasoningCompletionMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("pre-acting").build();
        ReActAgent first =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new CompletedToolCallModel())
                        .stateStore(store)
                        .middleware(gate)
                        .build();

        CompletableFuture<Msg> future =
                first.call(List.of(userMsg("hello")), ctx)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(
                gate.reasoningCompleted.await(5, TimeUnit.SECONDS),
                "model reasoning should complete before interrupt");

        first.interrupt(ctx);
        gate.release.tryEmitEmpty();

        Msg reply = future.get(5, TimeUnit.SECONDS);
        assertEquals(GenerateReason.INTERRUPTED, reply.getGenerateReason());

        AgentState restoredState = agent(store).getAgentState("u1", "pre-acting");
        assertFalse(hasToolUse(restoredState, "call-complete"));
        assertTrue(allText(restoredState).contains("completed response"));
    }

    @Test
    @DisplayName("user interrupt preserves partial reasoning when no tool call was generated")
    void userInterruptPreservesPartialTextWithoutToolCall() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        GatedReasoningModel model =
                new GatedReasoningModel(
                        List.of(TextBlock.builder().text("text before interrupt").build()));
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("text-only").build();
        ReActAgent first =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(model)
                        .stateStore(store)
                        .build();

        CompletableFuture<Msg> future =
                first.call(List.of(userMsg("hello")), ctx)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(
                model.afterInitialChunks.await(5, TimeUnit.SECONDS),
                "partial text should be consumed before interrupt");

        first.interrupt(ctx);
        model.release.tryEmitEmpty();

        assertEquals(
                GenerateReason.INTERRUPTED, future.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertTrue(
                allText(first.getAgentState("u1", "text-only")).contains("text before interrupt"));
    }

    @Test
    @DisplayName("user interrupt does not persist a reasoning message containing only a tool call")
    void userInterruptDropsToolOnlyReasoningMessage() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        GatedReasoningModel model =
                new GatedReasoningModel(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call-only")
                                        .name("echo")
                                        .input(Map.of("value", "pending"))
                                        .build()));
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("tool-only").build();
        ReActAgent first =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(model)
                        .stateStore(store)
                        .build();

        CompletableFuture<Msg> future =
                first.call(List.of(userMsg("hello")), ctx)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(
                model.afterInitialChunks.await(5, TimeUnit.SECONDS),
                "tool call should be consumed before interrupt");

        first.interrupt(ctx);
        model.release.tryEmitEmpty();

        assertEquals(
                GenerateReason.INTERRUPTED, future.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertFalse(hasToolUse(first.getAgentState("u1", "tool-only"), "call-only"));
    }

    @Test
    @DisplayName("system interrupt saves partial reasoning under the SAVE policy")
    void systemInterruptSavesPartialReasoning() throws Exception {
        AgentState state =
                runSystemInterruptDuringReasoning(PartialReasoningPolicy.SAVE, "system-save");

        assertTrue(hasToolUse(state, "call-complete"));
        assertTrue(allText(state).contains("completed response"));
    }

    @Test
    @DisplayName("system interrupt discards partial reasoning under the DISCARD policy")
    void systemInterruptDiscardsPartialReasoning() throws Exception {
        AgentState state =
                runSystemInterruptDuringReasoning(PartialReasoningPolicy.DISCARD, "system-discard");

        assertFalse(hasToolUse(state, "call-complete"));
        assertFalse(allText(state).contains("completed response"));
    }

    @Test
    @DisplayName("gotoReasoning persists only non-null reasoning messages")
    @SuppressWarnings("removal")
    void gotoReasoningPersistsOnlyNonNullReasoningMessages() {
        AtomicInteger reasoningRound = new AtomicInteger();
        Hook gotoHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostReasoningEvent reasoningEvent) {
                            int round = reasoningRound.getAndIncrement();
                            if (round == 0) {
                                reasoningEvent.gotoReasoning();
                            } else if (round == 1) {
                                reasoningEvent.setReasoningMessage(null);
                                reasoningEvent.gotoReasoning();
                            }
                        }
                        return Mono.just(event);
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new SequentialTextModel("first response", "discarded", "final"))
                        .hook(gotoHook)
                        .build();

        Msg result = agent.call(userMsg("hello")).block(Duration.ofSeconds(5));

        assertEquals("final", result.getTextContent());
        List<String> texts = allText(agent.getAgentState());
        assertTrue(texts.contains("first response"));
        assertFalse(texts.contains("discarded"));
        assertTrue(texts.contains("final"));
    }

    private AgentState runSystemInterruptDuringReasoning(
            PartialReasoningPolicy policy, String sessionId) throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        manager.resetForTesting();
        manager.setConfig(new GracefulShutdownConfig(Duration.ofSeconds(30), policy));

        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        GateReasoningCompletionMiddleware gate = new GateReasoningCompletionMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId(sessionId).build();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new CompletedToolCallModel())
                        .stateStore(store)
                        .middleware(gate)
                        .build();
        try {
            CompletableFuture<Msg> future =
                    agent.call(List.of(userMsg("hello")), ctx)
                            .subscribeOn(Schedulers.parallel())
                            .toFuture();
            assertTrue(
                    gate.reasoningCompleted.await(5, TimeUnit.SECONDS),
                    "model reasoning should complete before shutdown");

            assertTrue(manager.performGracefulShutdown());
            gate.release.tryEmitEmpty();

            ExecutionException error =
                    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(AgentShuttingDownException.class, error.getCause());
            return agent.getAgentState("u1", sessionId);
        } finally {
            gate.release.tryEmitEmpty();
            agent.close();
            manager.resetForTesting();
            manager.setConfig(GracefulShutdownConfig.DEFAULT);
        }
    }

    private static final class DelayedFirstChunkModel extends ChatModelBase {
        private final CountDownLatch subscribed;

        private DelayedFirstChunkModel(CountDownLatch subscribed) {
            this.subscribed = subscribed;
        }

        @Override
        public String getModelName() {
            return "delayed-first-chunk";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        subscribed.countDown();
                        return Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("model reply")
                                                                        .build()))
                                                .build())
                                .delaySubscription(Duration.ofMillis(200));
                    });
        }
    }

    private static final class GatedReasoningModel extends ChatModelBase {
        private final List<ContentBlock> initialBlocks;
        private final CountDownLatch afterInitialChunks = new CountDownLatch(1);
        private final Sinks.One<Void> release = Sinks.one();

        private GatedReasoningModel(List<ContentBlock> initialBlocks) {
            this.initialBlocks = List.copyOf(initialBlocks);
        }

        @Override
        public String getModelName() {
            return "gated-reasoning";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.concat(
                    Flux.fromIterable(initialBlocks).map(ReActAgentPerSessionStateTest::response),
                    Flux.defer(
                            () -> {
                                afterInitialChunks.countDown();
                                return release.asMono()
                                        .thenReturn(
                                                response(
                                                        TextBlock.builder()
                                                                .text("not consumed")
                                                                .build()));
                            }));
        }
    }

    private static final class SequentialTextModel extends ChatModelBase {
        private final List<String> responses;
        private final AtomicInteger index = new AtomicInteger();

        private SequentialTextModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String getModelName() {
            return "sequential-text";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    response(
                            TextBlock.builder()
                                    .text(responses.get(index.getAndIncrement()))
                                    .build()));
        }
    }

    private static final class CompletedToolCallModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "completed-tool-call";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id("call-complete")
                            .name("echo")
                            .input(Map.of("value", "done"))
                            .build();
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            TextBlock.builder().text("completed response").build(),
                                            toolCall))
                            .build());
        }
    }

    private static final class GateReasoningCompletionMiddleware implements MiddlewareBase {
        private final CountDownLatch reasoningCompleted = new CountDownLatch(1);
        private final Sinks.One<Void> release = Sinks.one();

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            return next.apply(input)
                    .concatWith(
                            Flux.defer(
                                    () -> {
                                        reasoningCompleted.countDown();
                                        return release.asMono().thenMany(Flux.empty());
                                    }));
        }
    }

    private static final class StrictHistoryModel extends ChatModelBase {
        private volatile Set<String> unresolvedIds = Set.of();

        @Override
        public String getModelName() {
            return "strict-history";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            Set<String> toolUseIds = new HashSet<>();
            Set<String> toolResultIds = new HashSet<>();
            for (Msg message : messages) {
                message.getContentBlocks(ToolUseBlock.class)
                        .forEach(block -> toolUseIds.add(block.getId()));
                message.getContentBlocks(ToolResultBlock.class)
                        .forEach(block -> toolResultIds.add(block.getId()));
            }
            toolUseIds.removeAll(toolResultIds);
            unresolvedIds = Set.copyOf(toolUseIds);
            if (!unresolvedIds.isEmpty()) {
                return Flux.error(
                        new IllegalStateException(
                                "Unresolved tool calls in model history: " + unresolvedIds));
            }
            return Flux.just(response(TextBlock.builder().text("continued").build()));
        }
    }

    private static ChatResponse response(ContentBlock block) {
        return ChatResponse.builder().content(List.of(block)).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static boolean hasToolUse(AgentState state, String id) {
        return state.getContext().stream()
                .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                .anyMatch(block -> id.equals(block.getId()));
    }

    private static List<String> allText(AgentState state) {
        List<String> out = new ArrayList<>();
        for (Msg m : state.getContext()) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof TextBlock t) {
                    out.add(t.getText());
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName(
            "concurrent calls to distinct sessions run in parallel without cross-contamination")
    void concurrentDistinctSessionsAreIsolated() {
        ReActAgent agent = agent(new InMemoryAgentStateStore());
        int sessions = 16;

        List<Mono<Msg>> calls =
                IntStream.range(0, sessions)
                        .mapToObj(
                                i ->
                                        agent.call(
                                                        List.of(userMsg("hello-" + i)),
                                                        RuntimeContext.builder()
                                                                .userId("u")
                                                                .sessionId("sess-" + i)
                                                                .build())
                                                .subscribeOn(Schedulers.parallel()))
                        .collect(Collectors.toList());

        // Run all sessions concurrently and wait for completion.
        Flux.merge(calls).blockLast(Duration.ofSeconds(30));

        // Each session must contain exactly its own user input, never another session's.
        for (int i = 0; i < sessions; i++) {
            AgentState s = agent.getAgentState("u", "sess-" + i);
            List<String> texts = allText(s);
            assertTrue(
                    texts.contains("hello-" + i),
                    "session " + i + " should contain its own input; was " + texts);
            for (int j = 0; j < sessions; j++) {
                if (j != i) {
                    assertFalse(
                            texts.contains("hello-" + j),
                            "session " + i + " leaked input from session " + j + ": " + texts);
                }
            }
        }
    }

    @Test
    @DisplayName("concurrent calls to the same session are serialized (no lost updates)")
    void concurrentSameSessionIsSerialized() {
        ReActAgent agent = agent(new InMemoryAgentStateStore());
        int calls = 24;

        List<Mono<Msg>> monos =
                IntStream.range(0, calls)
                        .mapToObj(
                                i ->
                                        agent.call(
                                                        List.of(userMsg("msg-" + i)),
                                                        RuntimeContext.builder()
                                                                .userId("u")
                                                                .sessionId("shared")
                                                                .build())
                                                .subscribeOn(Schedulers.parallel()))
                        .collect(Collectors.toList());

        Flux.merge(monos).blockLast(Duration.ofSeconds(30));

        // The per-session gate serializes same-session calls, so every distinct user input must be
        // present in the shared conversation buffer (no concurrent-mutation loss / corruption).
        List<String> texts = allText(agent.getAgentState("u", "shared"));
        for (int i = 0; i < calls; i++) {
            assertTrue(texts.contains("msg-" + i), "lost input msg-" + i + "; buffer was " + texts);
        }
    }

    @Test
    @DisplayName("concurrent streamEvents each receive their own bookended event stream")
    void concurrentStreamEventsAreIsolated() {
        ReActAgent agent = agent(new InMemoryAgentStateStore());
        int streams = 16;

        // Each subscription carries its own event sink via the Reactor Context (no shared instance
        // field), so concurrent streamEvents calls must not lose or cross-deliver lifecycle events.
        List<Mono<List<AgentEvent>>> collectors =
                IntStream.range(0, streams)
                        .mapToObj(
                                i ->
                                        agent.streamEvents(
                                                        List.of(userMsg("hello-" + i)),
                                                        RuntimeContext.builder()
                                                                .userId("u")
                                                                .sessionId("sess-" + i)
                                                                .build())
                                                .subscribeOn(Schedulers.parallel())
                                                .collectList())
                        .collect(Collectors.toList());

        List<List<AgentEvent>> results =
                Flux.merge(collectors).collectList().block(Duration.ofSeconds(30));

        assertEquals(streams, results.size(), "every stream must complete");
        for (List<AgentEvent> events : results) {
            long starts = events.stream().filter(e -> e instanceof AgentStartEvent).count();
            long ends = events.stream().filter(e -> e instanceof AgentEndEvent).count();
            assertEquals(1, starts, "each stream must be opened by exactly one AgentStartEvent");
            assertEquals(1, ends, "each stream must be closed by exactly one AgentEndEvent");
        }
    }
}
