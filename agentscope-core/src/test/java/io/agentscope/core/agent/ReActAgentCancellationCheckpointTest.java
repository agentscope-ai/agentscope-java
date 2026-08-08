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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.ToolContextState;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@DisplayName("ReActAgent cancellation checkpoints")
class ReActAgentCancellationCheckpointTest {

    private static final String USER_ID = "checkpoint-user";
    private static final String SESSION_ID = "checkpoint-session";
    private static final String AGENT_STATE_KEY = "agent_state";

    @Test
    @DisplayName("cancellation persists a reloadable snapshot of the call-scoped state")
    void cancellationPersistsReloadableCallScopedState() throws Exception {
        SnapshotStore store = new SnapshotStore();
        NeverModel model = new NeverModel();
        RuntimeContext runtimeContext = context(SESSION_ID);

        try (ReActAgent agent = agent(model, store)) {
            Disposable call =
                    agent.streamEvents(List.of(message("keep this turn")), runtimeContext)
                            .subscribe();
            try {
                await(model.subscribed, "model call did not start");
                AgentState live = runtimeContext.getAgentState();
                assertNotNull(live);

                live.setSummary("checkpoint summary");
                live.getPlanModeContext().setPlanActive(true);
                live.getPlanModeContext().setCurrentPlanFile("plans/recovery.md");
                live.getTasksContext()
                        .tasksMutable()
                        .add(
                                Task.builder()
                                        .id("checkpoint-task")
                                        .subject("Resume interrupted work")
                                        .description("State must survive downstream cancellation")
                                        .createdAt("2026-07-28T00:00:00Z")
                                        .build());
                ToolContextState.SpawnEntry spawn =
                        new ToolContextState.SpawnEntry(
                                "agent:worker:checkpoint",
                                "worker",
                                "child-checkpoint-session",
                                "Worker",
                                1);
                live.getToolContext().putSpawnEntry(spawn.key(), spawn);

                call.dispose();
                await(store.saved, "cancellation checkpoint did not finish");

                AgentState restored =
                        store.get(USER_ID, SESSION_ID, AGENT_STATE_KEY, AgentState.class)
                                .orElseThrow();
                assertEquals(1, store.saveCount.get());
                assertEquals(USER_ID, restored.getUserId());
                assertEquals(SESSION_ID, restored.getSessionId());
                assertEquals("checkpoint summary", restored.getSummary());
                assertTrue(restored.getPlanModeContext().isPlanActive());
                assertEquals(
                        "plans/recovery.md", restored.getPlanModeContext().getCurrentPlanFile());
                assertEquals(
                        "Resume interrupted work",
                        restored.getTasksContext().getTasks().get(0).getSubject());
                assertEquals(spawn, restored.getToolContext().getSpawnRegistry().get(spawn.key()));
                assertTrue(texts(restored).contains("keep this turn"));
            } finally {
                call.dispose();
            }
        }
    }

    @Test
    @DisplayName("normal completion keeps exactly one state save")
    void normalCompletionDoesNotAddCancellationSave() {
        SnapshotStore store = new SnapshotStore();

        try (ReActAgent agent = agent(new FixedModel(), store)) {
            agent.streamEvents(List.of(message("complete")), context(SESSION_ID))
                    .blockLast(Duration.ofSeconds(5));

            assertEquals(1, store.saveCount.get());
        }
    }

    @Test
    @DisplayName("cancellation without a state store is a safe no-op")
    void cancellationWithoutStoreIsSafe() throws Exception {
        NeverModel model = new NeverModel();

        try (ReActAgent agent = agent(model, null)) {
            Disposable call =
                    agent.streamEvents(List.of(message("no persistence")), context(SESSION_ID))
                            .subscribe();
            await(model.subscribed, "model call did not start");

            call.dispose();

            assertTrue(call.isDisposed());
            assertEquals(0, GracefulShutdownManager.getInstance().getActiveRequestCount());
        }
    }

    @Test
    @DisplayName("a failed cancellation save does not prevent lifecycle or gate cleanup")
    void failedCheckpointStillCleansUpAndReleasesSession() throws Exception {
        FailingOnceStore store = new FailingOnceStore();
        FirstCallNeverModel model = new FirstCallNeverModel();
        RuntimeContext runtimeContext = context(SESSION_ID);

        try (ReActAgent agent = agent(model, store)) {
            Disposable cancelled =
                    agent.streamEvents(List.of(message("cancel first")), runtimeContext)
                            .subscribe();
            await(model.firstSubscribed, "first model call did not start");

            cancelled.dispose();
            await(store.failedSaveAttempted, "failing checkpoint was not attempted");
            assertEquals(0, GracefulShutdownManager.getInstance().getActiveRequestCount());

            AgentEvent last =
                    agent.streamEvents(List.of(message("run second")), context(SESSION_ID))
                            .blockLast(Duration.ofSeconds(5));

            assertNotNull(last, "same-session call should proceed after checkpoint failure");
            assertEquals(2, model.subscriptions.get());
            assertEquals(0, GracefulShutdownManager.getInstance().getActiveRequestCount());
        }
    }

    @Test
    @DisplayName("the session gate orders cancellation saves without blocking other sessions")
    void cancellationCheckpointIsOrderedPerSessionAndIsolatedAcrossSessions() throws Exception {
        BlockingFirstSaveStore store = new BlockingFirstSaveStore(SESSION_ID);
        RoutingModel model = new RoutingModel();

        try (ReActAgent agent = agent(model, store)) {
            Disposable cancelled =
                    agent.streamEvents(List.of(message("cancelled input")), context(SESSION_ID))
                            .subscribe();
            try {
                await(model.cancelledSubscribed, "cancelled model call did not start");
                cancelled.dispose();
                await(store.blockingSaveStarted, "cancellation save did not start");

                CompletableFuture<List<AgentEvent>> sameSession =
                        agent.streamEvents(
                                        List.of(message("same session follow-up")),
                                        context(SESSION_ID))
                                .subscribeOn(Schedulers.parallel())
                                .collectList()
                                .toFuture();
                assertFalse(
                        model.sameSessionSubscribed.await(200, TimeUnit.MILLISECONDS),
                        "same-session call must wait for the cancellation checkpoint");

                CompletableFuture<List<AgentEvent>> otherSession =
                        agent.streamEvents(
                                        List.of(message("other session input")),
                                        context("independent-session"))
                                .subscribeOn(Schedulers.parallel())
                                .collectList()
                                .toFuture();
                await(
                        model.otherSessionSubscribed,
                        "an independent session should not wait for the blocked checkpoint");
                assertFalse(otherSession.get(5, TimeUnit.SECONDS).isEmpty());

                store.allowBlockingSave.countDown();
                await(
                        model.sameSessionSubscribed,
                        "same-session call did not resume after checkpoint completion");
                assertFalse(sameSession.get(5, TimeUnit.SECONDS).isEmpty());

                AgentState same =
                        store.get(USER_ID, SESSION_ID, AGENT_STATE_KEY, AgentState.class)
                                .orElseThrow();
                AgentState other =
                        store.get(USER_ID, "independent-session", AGENT_STATE_KEY, AgentState.class)
                                .orElseThrow();
                assertTrue(texts(same).contains("cancelled input"));
                assertTrue(texts(same).contains("same session follow-up"));
                assertFalse(texts(other).contains("cancelled input"));
                assertTrue(texts(other).contains("other session input"));
            } finally {
                store.allowBlockingSave.countDown();
                cancelled.dispose();
            }
        }
    }

    private static ReActAgent agent(ChatModelBase model, InMemoryAgentStateStore store) {
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("cancellation-checkpoint-agent")
                        .sysPrompt("Test cancellation persistence.")
                        .model(model);
        if (store != null) {
            builder.stateStore(store);
        }
        return builder.build();
    }

    private static RuntimeContext context(String sessionId) {
        return RuntimeContext.builder().userId(USER_ID).sessionId(sessionId).build();
    }

    private static Msg message(String text) {
        return new UserMessage(text);
    }

    private static List<String> texts(AgentState state) {
        return state.getContext().stream().map(Msg::getTextContent).toList();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static ChatResponse response() {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text("done").build()))
                .build();
    }

    private static final class NeverModel extends ChatModelBase {
        private final CountDownLatch subscribed = new CountDownLatch(1);

        @Override
        public String getModelName() {
            return "never";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        subscribed.countDown();
                        return Flux.never();
                    });
        }
    }

    private static final class FixedModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "fixed";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(response());
        }
    }

    private static final class FirstCallNeverModel extends ChatModelBase {
        private final CountDownLatch firstSubscribed = new CountDownLatch(1);
        private final AtomicInteger subscriptions = new AtomicInteger();

        @Override
        public String getModelName() {
            return "first-never";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        if (subscriptions.incrementAndGet() == 1) {
                            firstSubscribed.countDown();
                            return Flux.never();
                        }
                        return Flux.just(response());
                    });
        }
    }

    private static final class RoutingModel extends ChatModelBase {
        private final CountDownLatch cancelledSubscribed = new CountDownLatch(1);
        private final CountDownLatch sameSessionSubscribed = new CountDownLatch(1);
        private final CountDownLatch otherSessionSubscribed = new CountDownLatch(1);

        @Override
        public String getModelName() {
            return "routing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        String latest = messages.get(messages.size() - 1).getTextContent();
                        if ("cancelled input".equals(latest)) {
                            cancelledSubscribed.countDown();
                            return Flux.never();
                        }
                        if ("same session follow-up".equals(latest)) {
                            sameSessionSubscribed.countDown();
                        } else if ("other session input".equals(latest)) {
                            otherSessionSubscribed.countDown();
                        }
                        return Flux.just(response());
                    });
        }
    }

    private static class SnapshotStore extends InMemoryAgentStateStore {
        private final CountDownLatch saved = new CountDownLatch(1);
        private final AtomicInteger saveCount = new AtomicInteger();

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            if (AGENT_STATE_KEY.equals(key) && value instanceof AgentState state) {
                saveCount.incrementAndGet();
                super.save(userId, sessionId, key, AgentState.fromJsonString(state.toJson()));
                saved.countDown();
                return;
            }
            super.save(userId, sessionId, key, value);
        }
    }

    private static final class FailingOnceStore extends SnapshotStore {
        private final CountDownLatch failedSaveAttempted = new CountDownLatch(1);
        private final AtomicBoolean failNextSave = new AtomicBoolean(true);

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            if (AGENT_STATE_KEY.equals(key) && failNextSave.compareAndSet(true, false)) {
                failedSaveAttempted.countDown();
                throw new IllegalStateException("simulated checkpoint failure");
            }
            super.save(userId, sessionId, key, value);
        }
    }

    private static final class BlockingFirstSaveStore extends SnapshotStore {
        private final String blockedSessionId;
        private final AtomicBoolean blockNextSave = new AtomicBoolean(true);
        private final CountDownLatch blockingSaveStarted = new CountDownLatch(1);
        private final CountDownLatch allowBlockingSave = new CountDownLatch(1);

        private BlockingFirstSaveStore(String blockedSessionId) {
            this.blockedSessionId = blockedSessionId;
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            if (AGENT_STATE_KEY.equals(key)
                    && blockedSessionId.equals(sessionId)
                    && blockNextSave.compareAndSet(true, false)) {
                blockingSaveStarted.countDown();
                try {
                    if (!allowBlockingSave.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release test save");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test save interrupted", error);
                }
            }
            super.save(userId, sessionId, key, value);
        }
    }
}
