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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Task continuation through {@code context.session_id}: a submission naming an existing session
 * continues that conversation, so the agent sees the new input as the next turn while each
 * submission keeps its own record and result.
 */
class AgentProtocolTaskContinuationTest {

    private static final String SESSION_FIELD = "session_id";

    @TempDir Path tempDir;

    private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();

    private HarnessAgent agent;

    @BeforeEach
    void setUp() {
        agent = replyingAgent(exchanges);
    }

    @Test
    void continuedTaskRunsInTheParentSession() throws Exception {
        AgentProtocolTaskStore store = store(tempDir);

        store.submit("t-1", "worker", "first question", Map.of());
        awaitTerminal(store, "t-1");
        store.submit("t-2", "worker", "follow-up question", Map.of(SESSION_FIELD, "t-1"));
        awaitTerminal(store, "t-2");

        // Same session for both turns: the remote agent loads the stored conversation and treats
        // the
        // second input as the next user message rather than a fresh conversation.
        assertEquals("t-1", sessionOf("first question"));
        assertEquals("t-1", sessionOf("follow-up question"));
        // Task identity stays per submission, so both results remain addressable by their own id.
        assertEquals("success", store.snapshot("t-1").get("status"));
        assertEquals("success", store.snapshot("t-2").get("status"));
    }

    @Test
    void submissionKeepsTheProtocolNamespaceByDefault() throws Exception {
        AgentProtocolTaskStore store = store(tempDir, new AgentProtocolProperties());

        store.submit(
                "t-origin",
                "worker",
                "hello",
                Map.of("parent_session_id", "session-of-caller", "parent_agent_id", "planner"));
        awaitTerminal(store, "t-origin");

        TaskRecord record =
                new WorkspaceProtocolTaskRepository(tempDir).find("t-origin").orElseThrow();
        // By default the record's parent fields name the namespace it is stored in, not the caller.
        assertEquals(AgentProtocolConstants.PROTOCOL_AGENT_ID, record.getParentAgentId());
        assertEquals(AgentProtocolConstants.PROTOCOL_SESSION_ID, record.getParentSessionId());
        assertEquals("t-origin", record.getSubSessionId());
    }

    @Test
    void callerParentIdentityIsRecordedWhenEnabled() throws Exception {
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setUseCallerParentIdentity(true);
        AgentProtocolTaskStore store = store(tempDir, properties);

        store.submit(
                "t-caller",
                "worker",
                "hello",
                Map.of("parent_session_id", "session-of-caller", "parent_agent_id", "planner"));
        awaitTerminal(store, "t-caller");

        TaskRecord record =
                new WorkspaceProtocolTaskRepository(tempDir).find("t-caller").orElseThrow();
        assertEquals("planner", record.getParentAgentId());
        assertEquals("session-of-caller", record.getParentSessionId());
        // The caller identity is reported only; the run still executes in its own session.
        assertEquals("t-caller", record.getSubSessionId());
    }

    @Test
    void callerParentIdentityFallsBackToTheNamespaceWhenAbsent() throws Exception {
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setUseCallerParentIdentity(true);
        AgentProtocolTaskStore store = store(tempDir, properties);

        store.submit("t-anon", "worker", "hello", Map.of());
        awaitTerminal(store, "t-anon");

        TaskRecord record =
                new WorkspaceProtocolTaskRepository(tempDir).find("t-anon").orElseThrow();
        assertEquals(AgentProtocolConstants.PROTOCOL_AGENT_ID, record.getParentAgentId());
        assertEquals(AgentProtocolConstants.PROTOCOL_SESSION_ID, record.getParentSessionId());
    }

    @Test
    void callerParentIdentityFallsBackPerField() throws Exception {
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setUseCallerParentIdentity(true);
        AgentProtocolTaskStore store = store(tempDir, properties);

        store.submit("t-agent-only", "worker", "hello", Map.of("parent_agent_id", "planner"));
        awaitTerminal(store, "t-agent-only");
        store.submit(
                "t-session-only", "worker", "hello", Map.of("parent_session_id", "sess-caller"));
        awaitTerminal(store, "t-session-only");

        WorkspaceProtocolTaskRepository repository = new WorkspaceProtocolTaskRepository(tempDir);
        TaskRecord agentOnly = repository.find("t-agent-only").orElseThrow();
        assertEquals("planner", agentOnly.getParentAgentId());
        assertEquals(AgentProtocolConstants.PROTOCOL_SESSION_ID, agentOnly.getParentSessionId());

        TaskRecord sessionOnly = repository.find("t-session-only").orElseThrow();
        assertEquals(AgentProtocolConstants.PROTOCOL_AGENT_ID, sessionOnly.getParentAgentId());
        assertEquals("sess-caller", sessionOnly.getParentSessionId());
    }

    @Test
    void callerIdentityDoesNotChangeTheSessionAContinuationRunsIn() throws Exception {
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setUseCallerParentIdentity(true);
        AgentProtocolTaskStore store = store(tempDir, properties);

        store.submit(
                "t-first", "worker", "first question", Map.of("parent_session_id", "sess-caller"));
        awaitTerminal(store, "t-first");
        store.submit(
                "t-second",
                "worker",
                "follow-up question",
                Map.of("session_id", "t-first", "parent_session_id", "sess-caller"));
        awaitTerminal(store, "t-second");

        // The reported caller session is descriptive: continuation still follows the parent task's
        // session, so the agent sees the earlier turn.
        assertEquals("t-first", sessionOf("first question"));
        assertEquals("t-first", sessionOf("follow-up question"));

        TaskRecord second =
                new WorkspaceProtocolTaskRepository(tempDir).find("t-second").orElseThrow();
        assertEquals("sess-caller", second.getParentSessionId());
        assertEquals("t-first", second.getSubSessionId());
    }

    @Test
    void callerParentIdentityDoesNotMoveTheRecordOutOfTheProtocolBucket() throws Exception {
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setUseCallerParentIdentity(true);
        AgentProtocolTaskStore store = store(tempDir, properties);

        store.submit(
                "t-located",
                "worker",
                "hello",
                Map.of("parent_agent_id", "planner", "parent_session_id", "sess-caller"));
        awaitTerminal(store, "t-located");

        // The reported identity changes; the storage location does not. Inspection and cleanup
        // resolve paths from the bucket, so a record must stay where the repository writes it.
        assertTrue(
                Files.exists(tempDir.resolve("agents/_agentscope_protocol/tasks/_tasks.json")),
                "record must stay in the protocol bucket");
        assertTrue(
                new WorkspaceProtocolTaskRepository(tempDir).find("t-located").isPresent(),
                "a fresh repository over the same directory must still find the task");
    }

    @Test
    void taskWithoutParentTaskIdKeepsItsOwnSession() throws Exception {
        AgentProtocolTaskStore store = store(tempDir);

        store.submit("t-1", "worker", "first question", Map.of());
        awaitTerminal(store, "t-1");
        store.submit("t-2", "worker", "unrelated task", Map.of());
        awaitTerminal(store, "t-2");

        assertEquals("t-1", sessionOf("first question"));
        assertEquals("t-2", sessionOf("unrelated task"));
    }

    @Test
    void continuationOfAContinuationStaysInTheOriginalSession() throws Exception {
        AgentProtocolTaskStore store = store(tempDir);

        store.submit("t-1", "worker", "turn one", Map.of());
        awaitTerminal(store, "t-1");
        store.submit("t-2", "worker", "turn two", Map.of(SESSION_FIELD, "t-1"));
        awaitTerminal(store, "t-2");
        store.submit("t-3", "worker", "turn three", Map.of(SESSION_FIELD, "t-1"));
        awaitTerminal(store, "t-3");

        assertEquals("t-1", sessionOf("turn two"));
        assertEquals("t-1", sessionOf("turn three"));
    }

    @Test
    void continuationResolvesTheSessionFromTheStoredRecord() throws Exception {
        AgentProtocolTaskStore first = store(tempDir);
        first.submit("t-1", "worker", "first question", Map.of());
        awaitTerminal(first, "t-1");

        // A second store on the same control-plane repository stands in for another instance: the
        // session must come from the persisted record, not from in-memory state.
        AgentProtocolTaskStore second = store(tempDir);
        second.submit("t-2", "worker", "follow-up question", Map.of(SESSION_FIELD, "t-1"));
        awaitTerminal(second, "t-2");

        assertEquals("t-1", sessionOf("follow-up question"));
    }

    @Test
    void anUnknownSessionIsOpenedByItsFirstSubmission() throws Exception {
        AgentProtocolTaskStore store = store(tempDir);

        // A caller-named session is created on first use: the previous design required an existing
        // task to continue, which forced callers to remember a per-turn id.
        store.submit("t-2", "worker", "first turn", Map.of(SESSION_FIELD, "sub-new"));
        awaitTerminal(store, "t-2");

        assertEquals("sub-new", sessionOf("first turn"));
    }

    @Test
    void continuingATaskThatIsStillRunningIsRejected() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HarnessAgent blocking = mock(HarnessAgent.class);
        when(blocking.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.defer(
                                () ->
                                        Mono.fromCallable(
                                                        () -> {
                                                            release.await(10, TimeUnit.SECONDS);
                                                            return (AgentEvent)
                                                                    new AgentResultEvent(
                                                                            reply("done"));
                                                        })
                                                .flux()));
        AgentProtocolTaskStore store = store(tempDir, null, blocking);

        try {
            store.submit("t-live", "worker", "long task", Map.of());
            awaitCondition(() -> !"pending".equals(store.snapshot("t-live").get("status")), 30_000);

            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    store.submit(
                                            "t-next",
                                            "worker",
                                            "follow-up",
                                            Map.of(SESSION_FIELD, "t-live")));

            assertTrue(error.getMessage().contains("still running"));
        } finally {
            release.countDown();
            // Let the blocked run settle before the store closes, so TempDir cleanup cannot race a
            // record write from the still-running task.
            awaitTerminal(store, "t-live");
            store.close();
        }
    }

    @Test
    void continuingATaskRunningOnAnotherInstanceIsRejected() throws Exception {
        ProtocolTaskRunRegistry sharedRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        CountDownLatch release = new CountDownLatch(1);
        HarnessAgent blocking = mock(HarnessAgent.class);
        when(blocking.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.defer(
                                () ->
                                        Mono.fromCallable(
                                                        () -> {
                                                            release.await(10, TimeUnit.SECONDS);
                                                            return (AgentEvent)
                                                                    new AgentResultEvent(
                                                                            reply("done"));
                                                        })
                                                .flux()));
        AgentProtocolTaskStore owner = store(tempDir, sharedRegistry, blocking);
        AgentProtocolTaskStore other = store(tempDir, sharedRegistry, agent);

        try {
            owner.submit("t-live", "worker", "long task", Map.of());
            awaitCondition(() -> !"pending".equals(owner.snapshot("t-live").get("status")), 30_000);

            // The lease lives in the shared registry, so the guard must fire even though the live
            // run belongs to a different store instance.
            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    other.submit(
                                            "t-next",
                                            "worker",
                                            "follow-up",
                                            Map.of(SESSION_FIELD, "t-live")));

            assertTrue(error.getMessage().contains("still running"));
        } finally {
            release.countDown();
            awaitTerminal(owner, "t-live");
            owner.close();
            other.close();
        }
    }

    private AgentProtocolTaskStore store(Path directory) {
        return store(directory, null, agent);
    }

    private AgentProtocolTaskStore store(Path directory, AgentProtocolProperties properties) {
        return store(directory, null, agent, properties);
    }

    /**
     * Builds a store on {@code directory}, optionally sharing {@code registry} — two stores over one
     * repository and registry stand in for two instances of a clustered deployment.
     */
    private AgentProtocolTaskStore store(
            Path directory, ProtocolTaskRunRegistry registry, HarnessAgent executionAgent) {
        return store(directory, registry, executionAgent, new AgentProtocolProperties());
    }

    private AgentProtocolTaskStore store(
            Path directory,
            ProtocolTaskRunRegistry registry,
            HarnessAgent executionAgent,
            AgentProtocolProperties properties) {
        return new AgentProtocolTaskStore(
                request -> executionAgent,
                new WorkspaceProtocolTaskRepository(directory),
                new AgentProtocolTaskEventBus(),
                properties,
                List.of(),
                registry != null ? registry : BaseStoreProtocolTaskRunRegistry.inMemory(),
                BaseStoreProtocolTaskContextStore.inMemory());
    }

    private String sessionOf(String input) {
        for (Exchange exchange : exchanges) {
            if (input.equals(exchange.input())) {
                return exchange.sessionId();
            }
        }
        throw new AssertionError("No run observed for input: " + input);
    }

    private static HarnessAgent replyingAgent(List<Exchange> sink) {
        HarnessAgent recording = mock(HarnessAgent.class);
        when(recording.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            Msg msg = invocation.getArgument(0);
                            RuntimeContext ctx = invocation.getArgument(1);
                            sink.add(new Exchange(msg.getTextContent(), ctx.getSessionId()));
                            return Flux.just(
                                    new AgentResultEvent(reply("ok")), new AgentEndEvent(null));
                        });
        return recording;
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static void awaitTerminal(AgentProtocolTaskStore store, String taskId)
            throws Exception {
        awaitCondition(
                () -> {
                    Object status = store.snapshot(taskId).get("status");
                    return "success".equals(status) || "error".equals(status);
                },
                30_000);
    }

    private static void awaitCondition(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(25);
        }
    }

    private record Exchange(String input, String sessionId) {}

    @FunctionalInterface
    private interface Condition {
        boolean get() throws Exception;
    }
}
