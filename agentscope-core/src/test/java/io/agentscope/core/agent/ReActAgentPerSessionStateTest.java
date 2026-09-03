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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.legacy.ToolkitState;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    @DisplayName("clearStateCache releases all local session state and permission engines")
    void clearStateCacheReleasesAllLocalCaches() {
        ReActAgent agent =
                ReActAgent.builder().name("asst").sysPrompt("hi").model(new NoopModel()).build();
        AgentState sessA = agent.getAgentState("u1", "sessA");
        AgentState sessB = agent.getAgentState("u1", "sessB");
        var defaultPermissionEngine = agent.getPermissionEngine();

        agent.clearStateCache();

        assertNotSame(sessA, agent.getAgentState("u1", "sessA"));
        assertNotSame(sessB, agent.getAgentState("u1", "sessB"));
        assertNotSame(defaultPermissionEngine, agent.getPermissionEngine());
    }

    @Test
    @DisplayName("clearStateCache removes only the targeted session")
    void clearStateCacheRemovesOnlyTargetedSession() {
        ReActAgent agent =
                ReActAgent.builder().name("asst").sysPrompt("hi").model(new NoopModel()).build();
        AgentState target = agent.getAgentState("u1", "sessA");
        AgentState other = agent.getAgentState("u1", "sessB");

        agent.clearStateCache(RuntimeContext.builder().userId("u1").sessionId("sessA").build());

        assertNotSame(target, agent.getAgentState("u1", "sessA"));
        assertSame(other, agent.getAgentState("u1", "sessB"));
    }

    @Test
    @DisplayName("clearStateCache preserves persisted session state")
    void clearStateCachePreservesPersistedState(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        ReActAgent agent = agent(store);
        AgentState state = agent.getAgentState("u1", "sessA");
        state.setSummary("remembered");
        agent.saveAgentState("u1", "sessA");

        agent.clearStateCache("u1", "sessA");

        AgentState reloaded = agent.getAgentState("u1", "sessA");
        assertNotSame(state, reloaded);
        assertEquals("remembered", reloaded.getSummary());
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
    @DisplayName("shutdown retry clears and uses the current non-default session state")
    void shutdownRetryUsesCurrentSessionState() {
        ReActAgent agent =
                ReActAgent.builder().name("asst").sysPrompt("hi").model(new NoopModel()).build();
        AgentState defaultState = agent.getAgentState();
        AgentState sessionState = agent.getAgentState("u1", "sessA");
        sessionState.setShutdownInterrupted(true);

        Msg response =
                agent.call(
                                List.of(userMsg("duplicate prompt")),
                                RuntimeContext.builder().userId("u1").sessionId("sessA").build())
                        .block(Duration.ofSeconds(5));

        assertEquals("ok", response.getTextContent());
        assertFalse(sessionState.isShutdownInterrupted());
        assertFalse(defaultState.isShutdownInterrupted());
        assertTrue(
                sessionState.getContext().stream()
                        .noneMatch(msg -> "duplicate prompt".equals(msg.getTextContent())),
                "the retry input must be discarded for the interrupted session");

        ReActAgent otherAgent =
                ReActAgent.builder().name("asst").sysPrompt("hi").model(new NoopModel()).build();
        AgentState otherDefaultState = otherAgent.getAgentState();
        AgentState otherSessionState = otherAgent.getAgentState("u1", "sessA");
        otherDefaultState.setShutdownInterrupted(true);

        otherAgent
                .call(
                        List.of(userMsg("new prompt")),
                        RuntimeContext.builder().userId("u1").sessionId("sessA").build())
                .block(Duration.ofSeconds(5));

        assertTrue(otherDefaultState.isShutdownInterrupted());
        assertTrue(
                otherSessionState.getContext().stream()
                        .anyMatch(msg -> "new prompt".equals(msg.getTextContent())),
                "a default-session flag must not discard another session's input");
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

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
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
