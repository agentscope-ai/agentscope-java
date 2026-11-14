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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentThreadIsolationTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    enum Backend {
        MEMORY,
        STATE_STORE,
        FILE,
        JOURNAL
    }

    @TempDir Path directory;
    private final InMemoryAgentStateStore states = new InMemoryAgentStateStore();
    private final InMemoryThreadJournalStore journals = new InMemoryThreadJournalStore();

    private ReActAgent agent(Backend backend, Model model) {
        var builder = ReActAgent.builder().name("assistant").model(model);
        switch (backend) {
            case MEMORY -> {}
            case STATE_STORE -> builder.stateStore(states);
            case FILE -> builder.stateStore(new JsonFileAgentStateStore(directory));
            case JOURNAL -> builder.threadJournalStore(journals);
        }
        return builder.build();
    }

    private RuntimeContext context(String thread) {
        return RuntimeContext.builder()
                .userId("owner")
                .sessionId("conversation")
                .threadId(thread)
                .build();
    }

    private static ChatResponse response() {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("answer").build()))
                .build();
    }

    private static class CaptureModel implements Model {
        final List<List<Msg>> requests = new CopyOnWriteArrayList<>();

        public String getModelName() {
            return "offline";
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            return Flux.just(response());
        }
    }

    private static List<String> text(AgentState state) {
        return state.getContext().stream().map(Msg::getTextContent).toList();
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void siblingThreadsExecuteConcurrentlyWithoutSharingHistory(Backend backend) throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CompletableFuture<ChatResponse> release = new CompletableFuture<>();
        Model blocking =
                new CaptureModel() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    entered.countDown();
                                    return Mono.fromFuture(release, true).flux();
                                });
                    }
                };
        var agent = agent(backend, blocking);
        var left = agent.call(List.of(new UserMessage("left-only")), context("left")).toFuture();
        var right = agent.call(List.of(new UserMessage("right-only")), context("right")).toFuture();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Sibling Thread calls were serialized");
            release.complete(response());
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
            assertTrue(text(agent.getAgentState(context("left"))).contains("left-only"));
            assertFalse(text(agent.getAgentState(context("left"))).contains("right-only"));
            assertTrue(text(agent.getAgentState(context("right"))).contains("right-only"));
            assertFalse(text(agent.getAgentState(context("right"))).contains("left-only"));
        } finally {
            release.complete(response());
            left.cancel(true);
            right.cancel(true);
        }
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void administrativeMutationsAndWorkingCopiesTargetOneThread(Backend backend) {
        var agent = agent(backend, new CaptureModel());
        var left = context("left");
        var right = context("right");
        agent.call(List.of(new UserMessage("left-only")), left).block(WAIT);
        agent.call(List.of(new UserMessage("right-only")), right).block(WAIT);
        var leftCopy = agent.getAgentState(left);
        var rightCopy = agent.getAgentState(right);
        leftCopy.setSummary("left-summary");
        rightCopy.setSummary("right-summary");
        agent.saveAgentState(left);
        agent.saveAgentState(right);
        assertEquals("left-summary", agent.getAgentState(left).getSummary());
        agent.setPermissionMode(left, PermissionMode.BYPASS);
        assertEquals(
                PermissionMode.BYPASS, agent.getAgentState(left).getPermissionContext().getMode());
        assertNotEquals(
                PermissionMode.BYPASS, agent.getAgentState(right).getPermissionContext().getMode());
        agent.clearContext(left);
        assertTrue(agent.getAgentState(left).getContext().isEmpty());
        assertTrue(text(agent.getAgentState(right)).contains("right-only"));
        assertEquals("right-summary", agent.getAgentState(right).getSummary());
        assertEquals("conversation", left.getSessionId());
        assertEquals("right", agent.getAgentState(right).getThreadId());
    }

    @ParameterizedTest
    @EnumSource(
            value = Backend.class,
            names = {"STATE_STORE", "FILE", "JOURNAL"})
    void rebuiltAgentRestoresOnlyTheSelectedThread(Backend backend) {
        var first = agent(backend, new CaptureModel());
        first.call(List.of(new UserMessage("left-history")), context("left")).block(WAIT);
        first.call(List.of(new UserMessage("right-history")), context("right")).block(WAIT);
        var capture = new CaptureModel();
        var restored = agent(backend, capture);
        restored.call(List.of(new UserMessage("continue")), context("left")).block(WAIT);
        var messages = capture.requests.get(0).stream().map(Msg::getTextContent).toList();
        assertTrue(messages.contains("left-history"));
        assertFalse(messages.contains("right-history"));
        assertEquals("left", restored.getAgentState(context("left")).getThreadId());
    }

    @Test
    void encodedThreadKeysDoNotCollideOrEscapeTheirNamespace() {
        var a = new ThreadKey("a/b", "agent", "c", "../left");
        var b = new ThreadKey("a", "agent", "b/c", "../left");
        var c = new ThreadKey("a/b", "agent", "c", "right");
        assertNotEquals(a.storagePath(), b.storagePath());
        assertNotEquals(a.storagePath(), c.storagePath());
        assertNotEquals(a.stateEntry("agent_state"), c.stateEntry("agent_state"));
        assertFalse(a.storagePath().contains(".."));
        assertFalse(a.stateEntry("../state").contains("/"));
        assertThrows(IllegalArgumentException.class, () -> new ThreadKey(null, "a", "s", ""));
    }

    @Test
    void refusesStateFromAnotherThreadAndMapsLegacyStateToMainThread() {
        var agent = agent(Backend.STATE_STORE, new CaptureModel());
        var left = context("left");
        states.save(
                "owner",
                "conversation",
                agent.threadKey(left).stateEntry("agent_state"),
                AgentState.builder()
                        .userId("owner")
                        .sessionId("conversation")
                        .threadId("right")
                        .build());
        assertThrows(IllegalStateException.class, () -> agent.getAgentState(left));
        assertEquals("main", AgentState.fromJsonString("{\"session_id\":\"s\"}").getThreadId());
    }
}
