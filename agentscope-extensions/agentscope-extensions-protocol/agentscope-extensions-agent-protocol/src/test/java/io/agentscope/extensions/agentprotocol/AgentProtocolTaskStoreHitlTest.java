/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Lightweight HITL path for {@link AgentProtocolTaskStore}: mock {@link HarnessAgent} streams a
 * {@link GenerateReason#PERMISSION_ASKING} result, then a completed reply after resume/deny.
 */
class AgentProtocolTaskStoreHitlTest {

    @TempDir Path tempDir;

    private HarnessAgent agent;
    private ProtocolTaskRepository taskRepository;
    private AgentProtocolTaskStore store;
    private final AtomicInteger streamCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        taskRepository = new WorkspaceProtocolTaskRepository(tempDir);
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setStreamingEnabled(true);
        store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        new AgentProtocolTaskEventBus(),
                        props);

        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        inv -> {
                            int n = streamCalls.incrementAndGet();
                            if (n == 1) {
                                return Flux.just(
                                        new AgentStartEvent("sess", null, "worker"),
                                        new AgentResultEvent(askingMsg()),
                                        new AgentEndEvent(null));
                            }
                            return Flux.just(
                                    new AgentStartEvent("sess", null, "worker"),
                                    new AgentResultEvent(completedMsg()),
                                    new AgentEndEvent(null));
                        });
    }

    @Test
    void submit_pausesOnPermissionAsking_andResumeDenyCompletes() throws Exception {
        store.submit("hitl-1", "worker", "please run bash", Map.of("detail", "full"));

        awaitCondition(
                () -> "awaiting_confirm".equals(store.snapshot("hitl-1").get("status")), 5_000);

        Map<String, Object> snap = store.snapshot("hitl-1");
        assertEquals("awaiting_confirm", snap.get("status"));
        assertNotNull(snap.get("pending_confirms"));
        @SuppressWarnings("unchecked")
        List<RemotePendingConfirm> pending =
                (List<RemotePendingConfirm>) snap.get("pending_confirms");
        assertEquals(1, pending.size());
        assertEquals("tc-ask", pending.get(0).getToolCallId());
        assertEquals("bash", pending.get(0).getToolName());
        // Submit context must survive awaiting_confirm so resume can reuse detail/userId.
        assertTrue(store.hasSubmitContext("hitl-1"));

        store.resume("hitl-1", List.of(new RemoteConfirmDecision("tc-ask", false, "not allowed")));

        awaitCondition(() -> "success".equals(store.snapshot("hitl-1").get("status")), 5_000);

        Map<String, Object> done = store.snapshot("hitl-1");
        assertEquals("success", done.get("status"));
        assertEquals("denied and done", done.get("result"));
        assertEquals(2, streamCalls.get());
        assertFalse(store.hasSubmitContext("hitl-1"));

        ArgumentCaptor<Msg> messages = ArgumentCaptor.forClass(Msg.class);
        verify(agent, times(2)).streamEvents(messages.capture(), any(RuntimeContext.class));
        Object raw = messages.getAllValues().get(1).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertTrue(raw instanceof List);
        List<?> results = (List<?>) raw;
        assertEquals(1, results.size());
        ConfirmResult result = assertInstanceOf(ConfirmResult.class, results.get(0));
        assertFalse(result.isConfirmed());
        assertEquals("not allowed", result.getReason());
    }

    @Test
    void submitContextClearedOnDirectSuccess() throws Exception {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new AgentStartEvent("sess", null, "worker"),
                                new AgentResultEvent(completedMsg()),
                                new AgentEndEvent(null)));

        store.submit("ok-1", "worker", "hello", Map.of("user_id", "u1", "detail", "status"));
        awaitCondition(() -> "success".equals(store.snapshot("ok-1").get("status")), 5_000);
        assertFalse(store.hasSubmitContext("ok-1"));
    }

    @Test
    void resumeOnBusySessionLeavesTheTaskResumable() throws Exception {
        ProtocolTaskRunRegistry runRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        ProtocolTaskContextStore contextStore = BaseStoreProtocolTaskContextStore.inMemory();
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        AgentProtocolTaskStore resumable =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        new AgentProtocolTaskEventBus(),
                        props,
                        List.of(),
                        runRegistry,
                        contextStore);
        resumable.submit(
                "hitl-busy",
                "worker",
                "please run bash",
                Map.of("session_id", "conv-1", "detail", "full"));
        awaitCondition(
                () -> "awaiting_confirm".equals(resumable.snapshot("hitl-busy").get("status")),
                5_000);

        // A pause releases the session, so another turn may own the conversation when the user
        // finally confirms.
        Optional<ProtocolTaskLease> otherTurn =
                runRegistry.tryAcquire("session:conv-1", Duration.ofSeconds(30));
        assertTrue(otherTurn.isPresent(), "the pause must have released the session slot");

        assertThrows(
                SessionBusyException.class,
                () ->
                        resumable.resume(
                                "hitl-busy",
                                List.of(new RemoteConfirmDecision("tc-ask", true, null))));

        assertEquals(
                "awaiting_confirm",
                resumable.snapshot("hitl-busy").get("status"),
                "a transient session conflict must leave the task resumable, not strand it");
        assertTrue(
                resumable.hasSubmitContext("hitl-busy"),
                "and must keep the submission metadata the retry reads");

        runRegistry.release(otherTurn.get());
        resumable.resume("hitl-busy", List.of(new RemoteConfirmDecision("tc-ask", true, null)));
        awaitCondition(
                () -> "success".equals(resumable.snapshot("hitl-busy").get("status")), 5_000);
        assertFalse(resumable.hasSubmitContext("hitl-busy"));
    }

    /**
     * A paused turn keeps its conversation. The confirm decisions it waits for were produced
     * against the state it paused in, so a new turn in the same session must not advance that
     * state first — and cancelling the pending turn is the way out.
     */
    @Test
    void aPausedTurnOwnsItsSessionUntilItIsCancelled() throws Exception {
        store.submit("t-pause", "worker", "please run bash", Map.of("session_id", "conv-1"));
        awaitCondition(
                () -> "awaiting_confirm".equals(store.snapshot("t-pause").get("status")), 5_000);
        assertNotNull(
                store.snapshot("t-pause").get("confirm_expires_at"),
                "a bounded pause must tell the client when the turn expires");

        SessionAwaitingConfirmException blocked =
                assertThrows(
                        SessionAwaitingConfirmException.class,
                        () ->
                                store.submit(
                                        "t-next",
                                        "worker",
                                        "carry on",
                                        Map.of("session_id", "conv-1")));
        assertEquals("t-pause", blocked.pendingTaskId());
        assertEquals("conv-1", blocked.sessionId());

        // Another session is unaffected by the pause.
        store.submit("t-other", "worker", "unrelated", Map.of("session_id", "conv-2"));
        awaitCondition(() -> "success".equals(store.snapshot("t-other").get("status")), 5_000);

        store.cancel("t-pause");
        store.submit("t-next", "worker", "carry on", Map.of("session_id", "conv-1"));
        awaitCondition(() -> "success".equals(store.snapshot("t-next").get("status")), 5_000);
    }

    /**
     * The bound is what releases a session nobody answers: the pause ends as a failure, the event
     * stream closes, and the next submission is accepted.
     */
    @Test
    void anUnansweredPauseEndsAfterTheTimeoutAndFreesTheSession() throws Exception {
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setConfirmTimeoutMs(1_000L);
        AgentProtocolTaskEventBus bus = new AgentProtocolTaskEventBus();
        AgentProtocolTaskStore timed =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent), taskRepository, bus, props, List.of());
        List<RemoteAgentEvent> events = new ArrayList<>();
        Disposable subscription = bus.subscribe("t-timeout", 0).subscribe(events::add);
        try {
            timed.submit("t-timeout", "worker", "please run bash", Map.of("session_id", "conv-t"));
            awaitCondition(
                    () -> "awaiting_confirm".equals(timed.snapshot("t-timeout").get("status")),
                    5_000);

            awaitCondition(() -> "error".equals(timed.snapshot("t-timeout").get("status")), 5_000);
            Map<String, Object> settled = timed.snapshot("t-timeout");
            assertTrue(
                    String.valueOf(settled.get("error")).contains("confirm_timeout"),
                    "the settlement must name the reason: " + settled);
            assertFalse(
                    timed.hasSubmitContext("t-timeout"), "the turn is over, so its context goes");
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e.getType() == RemoteEventType.RUN_ERROR
                                                    && String.valueOf(e.getError())
                                                            .contains("confirm_timeout")),
                    "subscribers must see the turn end: " + events);

            // The session is free again, so the next turn runs.
            timed.submit("t-after", "worker", "carry on", Map.of("session_id", "conv-t"));
            awaitCondition(() -> "success".equals(timed.snapshot("t-after").get("status")), 5_000);
        } finally {
            subscription.dispose();
        }
    }

    /**
     * The configured pause length is taken as given: an operator who needs month-long approvals
     * raises {@code confirm-timeout-ms} and the deadline moves with it, rather than being truncated
     * to some implicit maximum.
     */
    @Test
    void aLongConfirmTimeoutIsHonouredWithoutAnUpperBound() throws Exception {
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setConfirmTimeoutMs(Duration.ofDays(30).toMillis());
        AgentProtocolTaskStore longPause =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        new AgentProtocolTaskEventBus(),
                        props);

        longPause.submit("t-long", "worker", "please run bash", Map.of("session_id", "conv-30"));
        awaitCondition(
                () -> "awaiting_confirm".equals(longPause.snapshot("t-long").get("status")), 5_000);

        Instant deadline =
                Instant.parse(
                        String.valueOf(longPause.snapshot("t-long").get("confirm_expires_at")));
        Instant now = Instant.now();
        assertTrue(
                deadline.isAfter(now.plus(Duration.ofDays(29))),
                "a 30-day pause must not be truncated, got " + deadline);
        assertFalse(
                deadline.isAfter(now.plus(Duration.ofDays(31))),
                "the deadline must follow the configured value, got " + deadline);
    }

    /**
     * The session already names this turn, so pausing must not write a second piece of state: a
     * second write is a second thing that can be lost, and its loss is exactly what let a new turn
     * slip into a paused session.
     */
    @Test
    void pausingWritesNothingToTheSessionRegistry() throws Exception {
        PointerCountingStore counting = new PointerCountingStore();
        BaseStoreProtocolSessionRegistry registry = new BaseStoreProtocolSessionRegistry(counting);
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        AgentProtocolTaskStore pointerStore =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        new AgentProtocolTaskEventBus(),
                        props,
                        List.of(),
                        BaseStoreProtocolTaskRunRegistry.inMemory(),
                        BaseStoreProtocolTaskContextStore.inMemory(),
                        registry);

        pointerStore.submit("t-ptr", "worker", "please run bash", Map.of("session_id", "conv-p"));
        awaitCondition(
                () -> "awaiting_confirm".equals(pointerStore.snapshot("t-ptr").get("status")),
                5_000);

        assertEquals("t-ptr", registry.currentTurn("conv-p").orElseThrow());
        assertEquals(1, counting.pointerWrites(), "the pointer is written once, at submit");

        pointerStore.snapshot("t-ptr");
        assertEquals(
                1, counting.pointerWrites(), "reading a paused turn must not write the pointer");
    }

    /** A submission that names no session cannot be shared, so it must not leave a pointer behind. */
    @Test
    void aSubmissionWithoutANamedSessionWritesNoPointer() throws Exception {
        PointerCountingStore counting = new PointerCountingStore();
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        AgentProtocolTaskStore plain =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        new AgentProtocolTaskEventBus(),
                        props,
                        List.of(),
                        BaseStoreProtocolTaskRunRegistry.inMemory(),
                        BaseStoreProtocolTaskContextStore.inMemory(),
                        new BaseStoreProtocolSessionRegistry(counting));

        plain.submit("t-solo", "worker", "no session here", Map.of());
        awaitCondition(
                () -> "awaiting_confirm".equals(plain.snapshot("t-solo").get("status")), 5_000);

        assertEquals(
                0, counting.pointerWrites(), "an unnamed session must not accumulate a pointer");
    }

    @Test
    void resumeAfterTheConfirmDeadlineIsRejected() throws Exception {
        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setConfirmTimeoutMs(1_000L);
        AgentProtocolTaskStore timed =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        new AgentProtocolTaskEventBus(),
                        props,
                        List.of());

        timed.submit("t-late", "worker", "please run bash", Map.of("session_id", "conv-l"));
        awaitCondition(
                () -> "awaiting_confirm".equals(timed.snapshot("t-late").get("status")), 5_000);

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && !"error".equals(timed.snapshot("t-late").get("status"))) {
            Thread.sleep(50);
        }
        assertEquals("error", timed.snapshot("t-late").get("status"), "the pause must settle");
        assertThrows(
                IllegalStateException.class,
                () ->
                        timed.resume(
                                "t-late", List.of(new RemoteConfirmDecision("tc-ask", true, null))),
                "a turn that already timed out must not be resumed");
    }

    @Test
    void agentResultEvent_carriesPermissionAskingMsg() {
        Msg asking = askingMsg();
        assertEquals(GenerateReason.PERMISSION_ASKING, asking.getGenerateReason());
        ToolUseBlock block = asking.getContentBlocks(ToolUseBlock.class).get(0);
        assertEquals(ToolCallState.ASKING, block.getState());

        AgentEvent event = new AgentResultEvent(asking);
        assertInstanceOf(AgentResultEvent.class, event);
        assertEquals(asking, ((AgentResultEvent) event).getResult());
    }

    private static Msg askingMsg() {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id("tc-ask")
                                .name("bash")
                                .input(Map.of("cmd", "rm -rf /"))
                                .state(ToolCallState.ASKING)
                                .build())
                .generateReason(GenerateReason.PERMISSION_ASKING)
                .build();
    }

    private static Msg completedMsg() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("denied and done").build();
    }

    @Test
    void resumeLeaseWaitIsConfigurableAndClamped() {
        AgentProtocolProperties defaults = new AgentProtocolProperties();
        assertEquals(
                2_000L,
                storeWith(defaults).resumeLeaseWaitMs(),
                "the default keeps the previous fixed two-second bound");

        AgentProtocolProperties custom = new AgentProtocolProperties();
        custom.setResumeLeaseWaitMs(750L);
        assertEquals(750L, storeWith(custom).resumeLeaseWaitMs());

        AgentProtocolProperties tooSmall = new AgentProtocolProperties();
        tooSmall.setResumeLeaseWaitMs(0L);
        assertEquals(100L, storeWith(tooSmall).resumeLeaseWaitMs(), "clamped to the floor");

        AgentProtocolProperties tooLarge = new AgentProtocolProperties();
        tooLarge.setResumeLeaseWaitMs(60_000L);
        assertEquals(30_000L, storeWith(tooLarge).resumeLeaseWaitMs(), "clamped to the ceiling");
    }

    private AgentProtocolTaskStore storeWith(AgentProtocolProperties props) {
        props.setHitlEnabled(true);
        return new AgentProtocolTaskStore(
                AgentFactory.fixed(agent), taskRepository, new AgentProtocolTaskEventBus(), props);
    }

    private static void awaitCondition(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean get() throws Exception;
    }

    /** Counts writes that land in the current-turn namespace only. */
    private static final class PointerCountingStore extends InMemoryStore {

        private static final List<String> CURRENT = List.of("agent-protocol", "session-current");

        private final AtomicInteger pointerWrites = new AtomicInteger();

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            if (CURRENT.equals(namespace)) {
                pointerWrites.incrementAndGet();
            }
            super.put(namespace, key, value);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            if (CURRENT.equals(namespace)) {
                pointerWrites.incrementAndGet();
            }
            super.delete(namespace, key);
        }

        int pointerWrites() {
            return pointerWrites.get();
        }
    }
}
