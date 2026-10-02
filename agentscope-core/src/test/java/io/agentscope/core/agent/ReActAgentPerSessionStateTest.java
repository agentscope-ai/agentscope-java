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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
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

    private ReActAgent agent(InMemoryAgentStateStore store) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hi")
                .model(new NoopModel())
                .stateStore(store)
                .build();
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

        ReActAgent reborn = agent(store);
        List<String> texts = allText(reborn.getAgentState("u1", "sessA"));
        assertTrue(texts.contains("hello"), "user input should remain in persisted session state");
        assertTrue(
                texts.contains("I noticed that you have interrupted me. What can I do for you?"),
                "interrupt recovery message should be persisted to the state store");
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
