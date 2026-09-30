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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class ReActAgentCancellationPersistenceTest {
    @Test
    void shutdownWaitsForCancellationSave() throws Exception {
        shutdownDuringCancellation(false, false);
    }

    @Test
    void shutdownCompletesAfterCancellationSaveRetry() throws Exception {
        shutdownDuringCancellation(true, false);
    }

    @Test
    void shutdownWaitsForCancelledQueuedCallCleanup() throws Exception {
        shutdownDuringCancellation(false, true);
    }

    @Test
    void shutdownCheckpointLeavesActiveCancellationSaveAlone() throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        BlockingStore store = new BlockingStore();
        WaitingModel model = new WaitingModel(false);
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        Disposable call = agent.call(List.of(user("request")), CTX).subscribe();
        try {
            assertTrue(model.started.await(3, TimeUnit.SECONDS));
            call.dispose();
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            assertNotNull(model.requestId.get());
            CompletableFuture<Void> checkpoint =
                    CompletableFuture.runAsync(
                            () -> manager.saveOnInterruptObserved(model.requestId.get()));
            checkpoint.get(3, TimeUnit.SECONDS);
            assertEquals(1, store.writes.get(), "shutdown must not duplicate the terminal save");
            assertFalse(
                    store.snapshot.isShutdownInterrupted(),
                    "a checkpoint must not mutate the state being written");
            store.proceed.countDown();
            assertTrue(store.finished.await(3, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    store.getVersioned(
                                    "probe-user", "probe-session", "agent_state", AgentState.class)
                            .version());
        } finally {
            store.proceed.countDown();
            call.dispose();
        }
    }

    @Test
    void shutdownTimeoutProcessesAnotherRequestDuringCancellationSave() throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        manager.resetForTesting();
        BlockingStore store = new BlockingStore();
        WaitingModel model = new WaitingModel(false);
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        CountDownLatch otherSaved = new CountDownLatch(1);
        InMemoryAgentStateStore otherStore =
                new InMemoryAgentStateStore() {
                    @Override
                    public long saveIfVersion(
                            String uid, String sid, String key, State value, long version) {
                        long saved = super.saveIfVersion(uid, sid, key, value, version);
                        otherSaved.countDown();
                        return saved;
                    }
                };
        WaitingModel otherModel = new WaitingModel(false);
        ReActAgent otherAgent =
                ReActAgent.builder()
                        .name("other-probe")
                        .model(otherModel)
                        .stateStore(otherStore)
                        .build();
        Disposable call = agent.call(List.of(user("request")), CTX).subscribe();
        Disposable otherCall = null;
        try {
            assertTrue(model.started.await(3, TimeUnit.SECONDS));
            call.dispose();
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            otherCall = otherAgent.call(List.of(user("other request")), CTX).subscribe();
            assertTrue(otherModel.started.await(3, TimeUnit.SECONDS));
            manager.setConfig(
                    new GracefulShutdownConfig(
                            Duration.ofMillis(100), PartialReasoningPolicy.SAVE));
            manager.performGracefulShutdown();
            manager.getShutdownTimeoutSignal().block(Duration.ofSeconds(3));
            // The signal is emitted before checkpoints run. Wait for a checkpoint on a second
            // request that was already active when shutdown began.
            assertTrue(otherSaved.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.writes.get());
            assertFalse(manager.awaitTermination(Duration.ofMillis(100)));
            store.proceed.countDown();
            otherCall.dispose();
            assertTrue(manager.awaitTermination(Duration.ofSeconds(3)));
        } finally {
            store.proceed.countDown();
            call.dispose();
            if (otherCall != null) {
                otherCall.dispose();
            }
            manager.setConfig(GracefulShutdownConfig.DEFAULT);
            manager.resetForTesting();
        }
    }

    @Test
    void shutdownWithoutStateStoreDoesNotMarkStateForPersistence() throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        manager.resetForTesting();
        WaitingModel model = new WaitingModel(false);
        ReActAgent agent = ReActAgent.builder().name("probe").model(model).build();
        Disposable call = agent.call(List.of(user("request")), CTX).subscribe();
        try {
            assertTrue(model.started.await(3, TimeUnit.SECONDS));
            assertNotNull(model.requestId.get());
            manager.saveOnInterruptObserved(model.requestId.get());
            assertFalse(agent.getAgentState().isShutdownInterrupted());
        } finally {
            call.dispose();
            manager.resetForTesting();
        }
    }

    @Test
    void cancellationSaveFollowsInFlightShutdownCheckpoint() throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        BlockingStore store = new BlockingStore();
        WaitingModel model = new WaitingModel(false);
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        Disposable call = agent.call(List.of(user("request")), CTX).subscribe();
        try {
            assertTrue(model.started.await(3, TimeUnit.SECONDS));
            assertNotNull(model.requestId.get());
            CompletableFuture<Void> checkpoint =
                    CompletableFuture.runAsync(
                            () -> manager.saveOnInterruptObserved(model.requestId.get()));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            call.dispose();
            assertTrue(model.cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.writes.get(), "terminal save must wait for checkpoint I/O");
            store.proceed.countDown();
            checkpoint.get(3, TimeUnit.SECONDS);
            assertTrue(store.secondFinished.await(3, TimeUnit.SECONDS));
            assertEquals(2, store.writes.get());
            assertEquals(
                    2,
                    store.getVersioned(
                                    "probe-user", "probe-session", "agent_state", AgentState.class)
                            .version());
        } finally {
            store.proceed.countDown();
            call.dispose();
        }
    }

    private void shutdownDuringCancellation(boolean fail, boolean cancelWaiter) throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        manager.resetForTesting();
        BlockingStore store = new BlockingStore();
        store.fail = fail;
        WaitingModel model = new WaitingModel(false);
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        Disposable call = agent.call(List.of(user("must survive")), CTX).subscribe();
        try {
            assertTrue(model.started.await(3, TimeUnit.SECONDS));
            assertEquals(1, manager.getActiveRequestCount());
            call.dispose();
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            if (cancelWaiter) {
                Disposable waiter = agent.call(List.of(user("never executed")), CTX).subscribe();
                waiter.dispose();
            }
            assertEquals(cancelWaiter ? 2 : 1, manager.getActiveRequestCount());
            manager.performGracefulShutdown();
            assertFalse(
                    manager.awaitTermination(Duration.ofMillis(100)),
                    "shutdown must wait for cancellation persistence");
            store.proceed.countDown();
            assertTrue(manager.awaitTermination(Duration.ofSeconds(3)));
            assertEquals(0, manager.getActiveRequestCount());
            assertEquals(fail ? 2 : 1, store.writes.get(), "only the failed write is retried");
            assertTrue(
                    text(store.get("probe-user", "probe-session", "agent_state", AgentState.class)
                                    .orElseThrow()
                                    .getContext())
                            .contains("must survive"));
        } finally {
            store.proceed.countDown();
            call.dispose();
            manager.performGracefulShutdown();
            try {
                assertTrue(manager.awaitTermination(Duration.ofSeconds(3)));
            } finally {
                manager.resetForTesting();
            }
        }
    }

    @TempDir Path dir;
    static final RuntimeContext CTX =
            RuntimeContext.builder().userId("probe-user").sessionId("probe-session").build();

    enum End {
        CANCEL,
        ERROR,
        COMPLETE
    }

    @Test
    void cancellationPreservesCompletedToolRound() throws Exception {
        probe(End.CANCEL);
    }

    @Test
    void errorPreservesCompletedToolRound() throws Exception {
        probe(End.ERROR);
    }

    @Test
    void completionPreservesCompletedToolRound() throws Exception {
        probe(End.COMPLETE);
    }

    @Test
    void cancellationDuringSaveJoinsOneWriteAndKeepsSessionQueue() throws Exception {
        BlockingStore store = new BlockingStore();
        Capturing model = new Capturing();
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        Disposable first = agent.call(List.of(user("first")), CTX).subscribe();
        CompletableFuture<Msg> next = null;
        try {
            assertTrue(store.entered.await(10, TimeUnit.SECONDS));
            first.dispose();
            // A cancelled waiter must not allow its successor to skip the unfinished writer.
            Disposable abandoned = agent.call(List.of(user("abandoned")), CTX).subscribe();
            abandoned.dispose();
            next = agent.call(List.of(user("next")), CTX).toFuture();
            assertFalse(next.isDone());
            assertEquals(1, store.writes.get());
            RuntimeContext other =
                    RuntimeContext.builder().userId("probe-user").sessionId("other").build();
            assertNotNull(
                    agent.call(List.of(user("independent")), other).block(Duration.ofSeconds(3)));
            store.proceed.countDown();
            assertNotNull(next.get(10, TimeUnit.SECONDS));
            assertEquals(2, store.writes.get());
            VersionedState<AgentState> saved =
                    store.getVersioned(
                            "probe-user", "probe-session", "agent_state", AgentState.class);
            assertEquals(2, saved.version());
            assertTrue(text(saved.value().getContext()).containsAll(List.of("first", "next")));
            assertFalse(text(saved.value().getContext()).contains("abandoned"));
        } finally {
            store.proceed.countDown();
            first.dispose();
            if (next != null) next.cancel(true);
        }
    }

    @Test
    void cancellationRetriesFailedInFlightTerminalSave() throws Exception {
        BlockingStore store = new BlockingStore();
        store.fail = true;
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(new Capturing()).stateStore(store).build();
        Disposable call = agent.call(List.of(user("must survive")), CTX).subscribe();
        try {
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            call.dispose();
            store.proceed.countDown();
            assertTrue(store.secondFinished.await(3, TimeUnit.SECONDS));
            assertEquals(2, store.writes.get());
            assertTrue(
                    text(store.get("probe-user", "probe-session", "agent_state", AgentState.class)
                                    .orElseThrow()
                                    .getContext())
                            .contains("must survive"));
        } finally {
            store.proceed.countDown();
            call.dispose();
        }
    }

    @Test
    void cancellationSaveFailureReleasesSessionQueue() throws Exception {
        cancellationSaveAndResume(true);
    }

    @Test
    void immediateResumeWaitsForCancellationSave() throws Exception {
        cancellationSaveAndResume(false);
    }

    private void cancellationSaveAndResume(boolean fail) throws Exception {
        BlockingStore store = new BlockingStore();
        store.fail = fail;
        WaitingModel model = new WaitingModel(false);
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        Disposable first = agent.call(List.of(user("first")), CTX).subscribe();
        try {
            assertTrue(model.started.await(10, TimeUnit.SECONDS));
            first.dispose();
            assertTrue(model.cancelled.await(10, TimeUnit.SECONDS));
            assertTrue(store.entered.await(10, TimeUnit.SECONDS));
            assertNotSame(agent.getAgentState(CTX), store.snapshot);
            CompletableFuture<Msg> next = agent.call(List.of(user("next")), CTX).toFuture();
            assertFalse(next.isDone());
            store.proceed.countDown();
            assertNotNull(next.get(10, TimeUnit.SECONDS));
            assertEquals(fail ? 3 : 2, store.writes.get());
            assertTrue(text(model.prompt).contains("first"));
        } finally {
            store.proceed.countDown();
            first.dispose();
        }
    }

    @Test
    void nativeStructuredCancellationPersistsInput() throws Exception {
        structuredCancellation(true);
    }

    @Test
    void fallbackStructuredCancellationPersistsInput() throws Exception {
        structuredCancellation(false);
    }

    void structuredCancellation(boolean nativeOutput) throws Exception {
        BlockingStore store = new BlockingStore();
        WaitingModel model = new WaitingModel(nativeOutput);
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        Disposable call =
                agent.call(List.of(user("structured input")), Answer.class, CTX).subscribe();
        try {
            assertTrue(model.started.await(10, TimeUnit.SECONDS));
            call.dispose();
            assertTrue(model.cancelled.await(10, TimeUnit.SECONDS));
            assertTrue(store.entered.await(10, TimeUnit.SECONDS));
            assertTrue(text(store.snapshot.getContext()).contains("structured input"));
            store.proceed.countDown();
            assertTrue(store.finished.await(10, TimeUnit.SECONDS));
            assertEquals(1, store.writes.get());
        } finally {
            store.proceed.countDown();
            call.dispose();
        }
    }

    public record Answer(String answer) {}

    @Test
    void failedNativeSaveDoesNotPoisonFallbackSave() {
        BlockingStore store = new BlockingStore();
        store.fail = true;
        store.proceed.countDown();
        Capturing model =
                new Capturing() {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(reply("{\"answer\":\"ok\"}"));
                    }
                };
        ReActAgent agent =
                ReActAgent.builder().name("probe").model(model).stateStore(store).build();
        assertNotNull(
                agent.call(List.of(user("input")), Answer.class, CTX)
                        .block(Duration.ofSeconds(10)));
        assertEquals(2, store.writes.get());
        assertTrue(
                text(store.get("probe-user", "probe-session", "agent_state", AgentState.class)
                                .orElseThrow()
                                .getContext())
                        .contains("input"));
    }

    @Test
    void cancellationReconcilesPendingAndAllowedButPreservesAsking() throws Exception {
        ObservedStore store = new ObservedStore(dir);
        AgentState seed =
                AgentState.builder().userId("probe-user").sessionId("probe-session").build();
        seed.contextMutable()
                .add(
                        AssistantMessage.builder()
                                .name("probe")
                                .content(
                                        List.of(
                                                toolUse("pending", ToolCallState.PENDING),
                                                toolUse("allowed", ToolCallState.ALLOWED),
                                                toolUse("asking", ToolCallState.ASKING)))
                                .build());
        store.save("probe-user", "probe-session", "agent_state", seed);
        CountDownLatch started = new CountDownLatch(1);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("probe")
                        .model(new Capturing())
                        .stateStore(store)
                        .hook(
                                new io.agentscope.core.hook.Hook() {
                                    @Override
                                    public <T extends io.agentscope.core.hook.HookEvent>
                                            Mono<T> onEvent(T event) {
                                        if (event instanceof io.agentscope.core.hook.PreCallEvent) {
                                            return Mono.<T>never()
                                                    .doOnSubscribe(s -> started.countDown());
                                        }
                                        return Mono.just(event);
                                    }
                                })
                        .build();
        Disposable call = agent.call(List.of(), CTX).subscribe();
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            call.dispose();
            assertTrue(store.saved.await(10, TimeUnit.SECONDS));
            AgentState disk =
                    new JsonFileAgentStateStore(dir)
                            .get("probe-user", "probe-session", "agent_state", AgentState.class)
                            .orElseThrow();
            assertEquals(
                    Set.of("pending", "allowed"),
                    disk.getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .map(ToolResultBlock::getId)
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(
                    ToolCallState.ASKING,
                    disk.getContext()
                            .get(0)
                            .getContentBlocks(ToolUseBlock.class)
                            .get(2)
                            .getState());
        } finally {
            call.dispose();
        }
    }

    static ToolUseBlock toolUse(String id, ToolCallState state) {
        return ToolUseBlock.builder()
                .id(id)
                .name("waiting_tool")
                .input(Map.of())
                .content("{}")
                .build()
                .withState(state);
    }

    static class WaitingModel extends Capturing {
        final CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        final AtomicReference<String> requestId = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final boolean nativeOutput;

        WaitingModel(boolean nativeOutput) {
            this.nativeOutput = nativeOutput;
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return nativeOutput;
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            if (calls.incrementAndGet() != 1) return super.doStream(msgs, tools, options);
            return Flux.deferContextual(
                    cv -> {
                        requestId.set(cv.getOrDefault(AgentBase.SHUTDOWN_REQUEST_ID_KEY, null));
                        return Flux.<ChatResponse>never()
                                .doOnSubscribe(s -> started.countDown())
                                .doOnCancel(cancelled::countDown);
                    });
        }
    }

    static class BlockingStore extends InMemoryAgentStateStore {
        final CountDownLatch entered = new CountDownLatch(1),
                proceed = new CountDownLatch(1),
                finished = new CountDownLatch(1),
                secondFinished = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        volatile AgentState snapshot;
        boolean fail;

        @Override
        public long saveIfVersion(String uid, String sid, String key, State value, long version) {
            if ("probe-session".equals(sid) && writes.incrementAndGet() == 1) {
                snapshot = (AgentState) value;
                entered.countDown();
                try {
                    if (!proceed.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("test store timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                if (fail) throw new IllegalStateException("test save failed");
            }
            long result = super.saveIfVersion(uid, sid, key, value, version);
            finished.countDown();
            if ("probe-session".equals(sid) && writes.get() >= 2) secondFinished.countDown();
            return result;
        }
    }

    @Test
    void cancellingResumedApprovalPersistsMatchingResult() throws Exception {
        ToolUseBlock asking =
                ToolUseBlock.builder()
                        .id("prior-call")
                        .name("waiting_tool")
                        .input(Map.of())
                        .content("{}")
                        .build()
                        .withState(ToolCallState.ASKING);
        AgentState seed =
                AgentState.builder().userId("probe-user").sessionId("probe-session").build();
        seed.contextMutable().add(user("previous request"));
        seed.contextMutable()
                .add(AssistantMessage.builder().name("probe").content(List.of(asking)).build());
        ObservedStore store = new ObservedStore(dir);
        store.save("probe-user", "probe-session", "agent_state", seed);
        CountDownLatch running = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new AgentTool() {
                    public String getName() {
                        return "waiting_tool";
                    }

                    public String getDescription() {
                        return "Cancellable local tool";
                    }

                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.<ToolResultBlock>never()
                                .doOnSubscribe(s -> running.countDown())
                                .doOnCancel(cancelled::countDown);
                    }
                });
        ReActAgent agent =
                ReActAgent.builder()
                        .name("probe")
                        .model(new Capturing())
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build();
        Msg approval =
                Msg.builder()
                        .role(MsgRole.USER)
                        .metadata(
                                Map.of(
                                        Msg.METADATA_CONFIRM_RESULTS,
                                        List.of(
                                                new io.agentscope.core.event.ConfirmResult(
                                                        true, asking))))
                        .build();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean cancelledBeforeSave = new AtomicBoolean();
        store.beforeSave = () -> cancelledBeforeSave.set(cancelled.getCount() == 0);
        List<String> events = new CopyOnWriteArrayList<>();
        Disposable subscription =
                agent.streamEvents(List.of(approval), CTX)
                        .subscribe(e -> events.add(e.toString()), error::set);
        try {
            assertTrue(
                    running.await(3, TimeUnit.SECONDS),
                    () ->
                            "tool not running; error="
                                    + error.get()
                                    + "; events="
                                    + events
                                    + "; state="
                                    + io.agentscope.core.util.JsonUtils.getJsonCodec()
                                            .toJson(agent.getAgentState(CTX)));
            AgentState live = agent.getAgentState(CTX);
            assertEquals(
                    ToolCallState.ALLOWED,
                    live.getContext()
                            .get(1)
                            .getContentBlocks(ToolUseBlock.class)
                            .get(0)
                            .getState());
            subscription.dispose();
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
            assertTrue(store.saved.await(10, TimeUnit.SECONDS));
            assertTrue(
                    cancelledBeforeSave.get(),
                    "the tool must receive cancel before persistence starts");
            AgentState disk =
                    new JsonFileAgentStateStore(dir)
                            .get("probe-user", "probe-session", "agent_state", AgentState.class)
                            .orElseThrow();
            assertEquals(
                    ToolCallState.ALLOWED,
                    disk.getContext()
                            .get(1)
                            .getContentBlocks(ToolUseBlock.class)
                            .get(0)
                            .getState());
            assertTrue(
                    disk.getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .anyMatch(t -> "prior-call".equals(t.getId())));
        } finally {
            subscription.dispose();
        }
    }

    void probe(End end) throws Exception {
        ObservedStore store = new ObservedStore(dir);
        AtomicInteger toolRuns = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new AgentTool() {
                    public String getName() {
                        return "probe_tool";
                    }

                    public String getDescription() {
                        return "Local deterministic probe";
                    }

                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        toolRuns.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("COMPLETED_TOOL_RESULT"));
                    }
                });
        Scripted model = new Scripted();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("probe")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build();
        agent.call(List.of(user("old question")), CTX).block(Duration.ofSeconds(10));
        AgentState before =
                store.get("probe-user", "probe-session", "agent_state", AgentState.class)
                        .orElseThrow();
        assertTrue(text(before.getContext()).contains("old question"));

        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Disposable subscription =
                agent.streamEvents(List.of(user("new question")), CTX)
                        .subscribe(
                                e -> {},
                                e -> {
                                    error.set(e);
                                    terminal.countDown();
                                },
                                terminal::countDown);
        try {
            assertTrue(model.waiting.await(10, TimeUnit.SECONDS));
            assertEquals(1, toolRuns.get());
            assertTrue(
                    model.thirdPrompt.stream()
                            .anyMatch(m -> !m.getContentBlocks(ToolResultBlock.class).isEmpty()),
                    "completed result reached next model call");
            if (end == End.CANCEL) {
                subscription.dispose();
                assertTrue(model.cancelled.await(10, TimeUnit.SECONDS));
                assertTrue(store.saved.await(10, TimeUnit.SECONDS));
            } else {
                if (end == End.ERROR)
                    model.tail.tryEmitError(new IllegalStateException("probe model failure"));
                else {
                    model.tail.tryEmitNext(reply("final answer"));
                    model.tail.tryEmitComplete();
                }
                assertTrue(terminal.await(10, TimeUnit.SECONDS));
                if (end == End.ERROR) assertNotNull(error.get());
                else assertNull(error.get());
            }

            // Re-open the actual file store to rule out shared in-memory references.
            AgentState disk =
                    new JsonFileAgentStateStore(dir)
                            .get("probe-user", "probe-session", "agent_state", AgentState.class)
                            .orElseThrow();
            boolean newInput = text(disk.getContext()).contains("new question");
            boolean toolResult =
                    disk.getContext().stream()
                            .anyMatch(m -> !m.getContentBlocks(ToolResultBlock.class).isEmpty());
            assertEquals(
                    true,
                    newInput,
                    "new input should survive cancellation when testing the desired contract");
            assertTrue(toolResult);

            Capturing recovery = new Capturing();
            ReActAgent rebuilt =
                    ReActAgent.builder()
                            .name("probe")
                            .model(recovery)
                            .stateStore(new JsonFileAgentStateStore(dir))
                            .build();
            rebuilt.call(List.of(user("continue")), CTX).block(Duration.ofSeconds(10));
            assertTrue(text(recovery.prompt).contains("new question"));
        } finally {
            subscription.dispose();
        }
    }

    static class ObservedStore extends JsonFileAgentStateStore {
        final CountDownLatch saved = new CountDownLatch(2);
        Runnable beforeSave = () -> {};

        ObservedStore(Path path) {
            super(path);
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            beforeSave.run();
            super.save(userId, sessionId, key, value);
            saved.countDown();
        }
    }

    static Msg user(String s) {
        return Msg.builder().role(MsgRole.USER).textContent(s).build();
    }

    static ChatResponse reply(String s) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(s).build())).build();
    }

    static List<String> text(List<Msg> msgs) {
        return msgs.stream().map(Msg::getTextContent).toList();
    }

    static class Scripted extends ChatModelBase {
        final AtomicInteger count = new AtomicInteger();
        final CountDownLatch waiting = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        final Sinks.Many<ChatResponse> tail = Sinks.many().unicast().onBackpressureBuffer();
        volatile List<Msg> thirdPrompt;

        public String getModelName() {
            return "local-probe";
        }

        protected Flux<ChatResponse> doStream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            int n = count.incrementAndGet();
            if (n == 1) return Flux.just(reply("old answer"));
            if (n == 2)
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("probe-call")
                                                        .name("probe_tool")
                                                        .input(Map.of())
                                                        .build()))
                                .build());
            thirdPrompt = List.copyOf(msgs);
            return tail.asFlux()
                    .doOnSubscribe(s -> waiting.countDown())
                    .doOnCancel(cancelled::countDown);
        }
    }

    static class Capturing extends ChatModelBase {
        List<Msg> prompt;

        public String getModelName() {
            return "recovery-probe";
        }

        protected Flux<ChatResponse> doStream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            prompt = List.copyOf(msgs);
            return Flux.just(reply("recovered"));
        }
    }
}
