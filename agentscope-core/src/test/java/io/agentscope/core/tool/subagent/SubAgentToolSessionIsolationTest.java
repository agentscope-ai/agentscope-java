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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

class SubAgentToolSessionIsolationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singletonDirectAndDelegatedSessionsKeepSeparateHistories(boolean streaming) {
        MockModel model = new MockModel("OK");
        try (ReActAgent agent = ReActAgent.builder().name("shared").model(model).build()) {
            RuntimeContext direct =
                    RuntimeContext.builder().userId("alice").sessionId("direct").build();
            agent.call(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("direct secret")
                                            .build()),
                            direct)
                    .block();
            SubAgentTool tool =
                    new SubAgentTool(
                            () -> agent, SubAgentConfig.builder().forwardEvents(streaming).build());
            tool.callAsync(param("delegated", "tool-session", "alice", "direct")).block();
            assertEquals(List.of("delegated"), users(model));
            agent.call(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("direct again")
                                            .build()),
                            direct)
                    .block();
            assertEquals(List.of("direct secret", "direct again"), users(model));
            tool.callAsync(param("tool again", "tool-session", "alice", "direct")).block();
            assertEquals(List.of("delegated", "tool again"), users(model));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalSessionIdsRemainIsolatedBetweenUsers(boolean streaming) {
        MockModel model = new MockModel("OK");
        SubAgentTool tool =
                new SubAgentTool(
                        () -> ReActAgent.builder().name("factory").model(model).build(),
                        SubAgentConfig.builder().forwardEvents(streaming).build());
        tool.callAsync(param("alice secret", "same", "alice", "parent")).block();
        tool.callAsync(param("bob secret", "same", "bob", "parent")).block();
        assertEquals(List.of("bob secret"), users(model));
        tool.callAsync(param("alice again", "same", "alice", "parent")).block();
        assertEquals(List.of("alice secret", "alice again"), users(model));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledAttemptCannotOverwriteRetryState(boolean versioned) throws Exception {
        BlockingStore store = new BlockingStore(versioned);
        CountDownLatch retryExecuted = new CountDownLatch(1);
        SubAgentTool tool =
                new SubAgentTool(
                        () ->
                                ReActAgent.builder()
                                        .name("factory")
                                        .model(
                                                new MockModel(
                                                        messages -> {
                                                            if (messages.stream()
                                                                    .anyMatch(
                                                                            m ->
                                                                                    "new"
                                                                                            .equals(
                                                                                                    m
                                                                                                            .getTextContent())))
                                                                retryExecuted.countDown();
                                                            return List.of(
                                                                    io.agentscope.core.model
                                                                            .ChatResponse.builder()
                                                                            .content(
                                                                                    List.of(
                                                                                            TextBlock
                                                                                                    .builder()
                                                                                                    .text(
                                                                                                            "OK")
                                                                                                    .build()))
                                                                            .build());
                                                        }))
                                        .build(),
                        SubAgentConfig.builder().forwardEvents(false).stateStore(store).build());
        var first =
                tool.callAsync(param("old", "retry", null, null))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe();
        var retry = new CompletableFuture<ToolResultBlock>();
        try {
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            first.dispose();
            tool.callAsync(param("new", "retry", null, null))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(retry::complete, retry::completeExceptionally);
            assertTrue(retryExecuted.await(5, TimeUnit.SECONDS));
            // Both versioned and legacy stores serialize actual writes off the signal thread.
        } finally {
            store.release.countDown();
            first.dispose();
        }
        retry.get(5, TimeUnit.SECONDS);
        assertTrue(store.finished.await(5, TimeUnit.SECONDS));
        assertEquals(
                List.of("new"),
                store
                        .get(null, "retry", "agent_state", AgentState.class)
                        .orElseThrow()
                        .getContext()
                        .stream()
                        .filter(m -> m.getRole() == MsgRole.USER)
                        .map(Msg::getTextContent)
                        .toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDirectCallSurvivesDelegationCancellation(boolean streaming) throws Exception {
        var directReply = Sinks.<ChatResponse>one();
        CountDownLatch delegated = new CountDownLatch(1);
        MockModel model =
                new MockModel("unused") {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (messages.stream()
                                .anyMatch(m -> "direct pending".equals(m.getTextContent()))) {
                            return directReply.asMono().flux();
                        }
                        delegated.countDown();
                        return Flux.never();
                    }
                };
        try (ReActAgent agent = ReActAgent.builder().name("shared").model(model).build()) {
            RuntimeContext directContext =
                    RuntimeContext.builder().userId("alice").sessionId("direct").build();
            var direct =
                    agent.call(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("direct pending")
                                                    .build()),
                                    directContext)
                            .toFuture();
            SubAgentTool tool =
                    new SubAgentTool(
                            () -> agent, SubAgentConfig.builder().forwardEvents(streaming).build());
            var call =
                    tool.callAsync(param("delegated pending", "child", "alice", "direct"))
                            .subscribe();
            try {
                assertTrue(delegated.await(5, TimeUnit.SECONDS));
                call.dispose();
                assertFalse(
                        direct.isDone(),
                        "Delegation cancellation must not interrupt the direct session");
                directReply.tryEmitValue(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("direct OK").build()))
                                .build());
                assertEquals("direct OK", direct.get(5, TimeUnit.SECONDS).getTextContent());
            } finally {
                call.dispose();
                direct.cancel(true);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingQueuedDelegationDoesNotInterruptDirectCallInSameSession(boolean streaming)
            throws Exception {
        var directReply = Sinks.<ChatResponse>one();
        CountDownLatch delegated = new CountDownLatch(1);
        MockModel model =
                new MockModel("unused") {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (messages.stream()
                                .anyMatch(m -> "direct pending".equals(m.getTextContent()))) {
                            return directReply.asMono().flux();
                        }
                        delegated.countDown();
                        return Flux.never();
                    }
                };
        try (ReActAgent agent = ReActAgent.builder().name("shared").model(model).build()) {
            RuntimeContext directContext =
                    RuntimeContext.builder().userId("alice").sessionId("direct").build();
            var direct =
                    agent.call(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("direct pending")
                                                    .build()),
                                    directContext)
                            .toFuture();
            SubAgentTool tool =
                    new SubAgentTool(
                            () -> agent, SubAgentConfig.builder().forwardEvents(streaming).build());
            var call =
                    tool.callAsync(param("delegated pending", "direct", "alice", "direct"))
                            .subscribe();
            try {
                // The direct call owns the session; the delegated call is queued behind it.
                call.dispose();
                assertFalse(
                        direct.isDone(),
                        "Delegation cancellation must not interrupt the direct session");
                directReply.tryEmitValue(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("direct OK").build()))
                                .build());
                assertEquals("direct OK", direct.get(5, TimeUnit.SECONDS).getTextContent());
            } finally {
                call.dispose();
                direct.cancel(true);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void factoryPreservesAgentOwnedSessionLog(boolean streaming) {
        MockModel model = new MockModel("OK");
        var log = new InMemorySessionLogStore();
        InMemoryAgentStateStore toolStore = new InMemoryAgentStateStore();
        SubAgentTool tool =
                new SubAgentTool(
                        () ->
                                ReActAgent.builder()
                                        .name("logged-child")
                                        .sessionLogStore(log)
                                        .model(model)
                                        .build(),
                        SubAgentConfig.builder()
                                .stateStore(toolStore)
                                .forwardEvents(streaming)
                                .build());
        tool.callAsync(param("first", "child", "alice", "parent")).block(Duration.ofSeconds(5));
        tool.callAsync(param("second", "child", "alice", "parent")).block(Duration.ofSeconds(5));
        assertEquals(List.of("first", "second"), users(model));
        assertFalse(toolStore.exists("alice", "child"));
    }

    @Test
    void oldAttemptCommittingBeforeRetryResponseDoesNotDiscardRetry() throws Exception {
        BlockingStore store = new BlockingStore(true);
        CountDownLatch retryStarted = new CountDownLatch(1);
        var retryReply = Sinks.<ChatResponse>one();
        MockModel model =
                new MockModel("unused") {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (messages.stream().anyMatch(m -> "new".equals(m.getTextContent()))) {
                            retryStarted.countDown();
                            return retryReply.asMono().flux();
                        }
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("OK").build()))
                                        .build());
                    }
                };
        SubAgentTool tool =
                new SubAgentTool(
                        () -> ReActAgent.builder().name("factory").model(model).build(),
                        SubAgentConfig.builder().forwardEvents(false).stateStore(store).build());
        var old = tool.callAsync(param("old", "retry", null, null)).subscribe();
        try {
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            old.dispose();
            var retry = tool.callAsync(param("new", "retry", null, null)).toFuture();
            assertTrue(retryStarted.await(5, TimeUnit.SECONDS));
            store.release.countDown();
            assertTrue(store.finished.await(5, TimeUnit.SECONDS));
            retryReply.tryEmitValue(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("OK").build()))
                            .build());
            retry.get(5, TimeUnit.SECONDS);
            assertEquals(
                    List.of("new"),
                    store
                            .get(null, "retry", "agent_state", AgentState.class)
                            .orElseThrow()
                            .getContext()
                            .stream()
                            .filter(m -> m.getRole() == MsgRole.USER)
                            .map(Msg::getTextContent)
                            .toList());
        } finally {
            store.release.countDown();
            old.dispose();
        }
    }

    private static List<String> users(MockModel model) {
        return model.getLastMessages().stream()
                .filter(m -> m.getRole() == MsgRole.USER)
                .map(Msg::getTextContent)
                .toList();
    }

    private static ToolCallParam param(String text, String session, String user, String parent) {
        return ToolCallParam.builder()
                .input(Map.of("message", text, "session_id", session))
                .runtimeContext(RuntimeContext.builder().userId(user).sessionId(parent).build())
                .emitter(chunk -> {})
                .build();
    }

    private static final class BlockingStore extends InMemoryAgentStateStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        final boolean versioned;

        BlockingStore(boolean versioned) {
            this.versioned = versioned;
        }

        @Override
        public boolean supportsVersioning() {
            return versioned;
        }

        private boolean gate() {
            if (!first.compareAndSet(true, false)) return false;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("save not released");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return true;
        }

        @Override
        public void save(String user, String session, String key, State state) {
            boolean old = gate();
            try {
                super.save(user, session, key, state);
            } finally {
                if (old) finished.countDown();
            }
        }

        @Override
        public long saveIfVersion(
                String user, String session, String key, State state, long version) {
            boolean old = gate();
            try {
                return super.saveIfVersion(user, session, key, state, version);
            } finally {
                if (old) finished.countDown();
            }
        }
    }
}
