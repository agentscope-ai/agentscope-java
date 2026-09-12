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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Verifies transcript completion and sandbox lifecycle behavior. */
class TranscriptMiddlewareAsyncTest {

    @TempDir Path workspace;

    @Test
    void onCompleteFiresBeforeTranscriptAppendFinishes() throws Exception {
        CountDownLatch appendStarted = new CountDownLatch(1);
        CountDownLatch appendFinishGate = new CountDownLatch(1);
        BlockingWorkspaceManager workspaceManager =
                new BlockingWorkspaceManager(workspace, appendStarted, appendFinishGate);
        TranscriptMiddleware middleware = new TranscriptMiddleware(workspaceManager);

        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent-a");
        Msg userMessage = message("m1", MsgRole.USER, "hello");
        AgentState state = AgentState.builder().addMessage(userMessage).build();
        RuntimeContext rc =
                RuntimeContext.builder().sessionId("session-1").agentState(state).build();
        AgentEndEvent endEvent = new AgentEndEvent("reply-1");

        try {
            List<AgentEvent> events =
                    middleware
                            .onAgent(
                                    agent,
                                    rc,
                                    new AgentInput(List.of(userMessage)),
                                    input -> Flux.just(endEvent))
                            .collectList()
                            .block(Duration.ofSeconds(2));

            assertEquals(List.of(endEvent), events);
            assertTrue(appendStarted.await(5, TimeUnit.SECONDS));
            assertFalse(
                    MemoryBackgroundTasks.awaitQuiescence(100, TimeUnit.MILLISECONDS),
                    "shutdown tracking must include the pending transcript write");
        } finally {
            appendFinishGate.countDown();
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "transcript write must eventually quiesce");
            workspaceManager.close();
        }
    }

    @Test
    void sandboxTranscriptWriteStaysInStreamUntilPersistenceFinishes() throws Exception {
        CountDownLatch appendStarted = new CountDownLatch(1);
        CountDownLatch appendFinishGate = new CountDownLatch(1);
        BlockingWorkspaceManager workspaceManager =
                new BlockingWorkspaceManager(workspace, appendStarted, appendFinishGate);
        TranscriptMiddleware middleware = new TranscriptMiddleware(workspaceManager);

        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent-a");
        Msg userMessage = message("m1", MsgRole.USER, "hello");
        AgentState state = AgentState.builder().addMessage(userMessage).build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .sessionId("session-1")
                        .agentState(state)
                        .put(
                                SandboxAcquireResult.class,
                                SandboxAcquireResult.userManaged(mock(Sandbox.class)))
                        .build();
        AgentEndEvent endEvent = new AgentEndEvent("reply-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean released = new AtomicBoolean();

        try {
            Future<List<AgentEvent>> completion =
                    executor.submit(
                            () ->
                                    Flux.using(
                                                    () -> rc,
                                                    ignored ->
                                                            middleware.onAgent(
                                                                    agent,
                                                                    rc,
                                                                    new AgentInput(
                                                                            List.of(userMessage)),
                                                                    input -> Flux.just(endEvent)),
                                                    ignored -> released.set(true))
                                            .collectList()
                                            .block(Duration.ofSeconds(5)));

            assertTrue(appendStarted.await(5, TimeUnit.SECONDS));
            assertFalse(
                    completion.isDone(),
                    "sandbox transcript writes must finish before releasing the per-call sandbox");
            assertFalse(
                    released.get(), "sandbox must remain live while transcript is being written");

            appendFinishGate.countDown();
            assertEquals(List.of(endEvent), completion.get(5, TimeUnit.SECONDS));
            assertTrue(released.get(), "sandbox release must follow transcript persistence");
        } finally {
            appendFinishGate.countDown();
            executor.shutdownNow();
            workspaceManager.close();
        }
    }

    @Test
    void capturesMessagesBeforeBackgroundWriteStarts() throws Exception {
        CountDownLatch resolveStarted = new CountDownLatch(1);
        CountDownLatch resolveFinishGate = new CountDownLatch(1);
        BlockingResolveWorkspaceManager workspaceManager =
                new BlockingResolveWorkspaceManager(workspace, resolveStarted, resolveFinishGate);
        TranscriptMiddleware middleware = new TranscriptMiddleware(workspaceManager);

        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent-a");
        Msg first = message("m1", MsgRole.USER, "first");
        Msg later = message("m2", MsgRole.ASSISTANT, "later");
        AgentState state = AgentState.builder().addMessage(first).build();
        RuntimeContext rc =
                RuntimeContext.builder().sessionId("session-1").agentState(state).build();

        try {
            middleware
                    .onAgent(
                            agent,
                            rc,
                            new AgentInput(List.of(first)),
                            input -> Flux.just(new AgentEndEvent("reply-1")))
                    .blockLast(Duration.ofSeconds(2));

            assertTrue(resolveStarted.await(5, TimeUnit.SECONDS));
            state.contextMutable().add(later);
            resolveFinishGate.countDown();
            assertTrue(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS));

            Path context = workspace.resolve("agents/agent-a/sessions/session-1.jsonl");
            assertEquals(1, Files.readAllLines(context).size());
            assertTrue(Files.readString(context).contains("\"id\":\"m1\""));
            assertFalse(Files.readString(context).contains("\"id\":\"m2\""));
        } finally {
            resolveFinishGate.countDown();
            MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS);
            workspaceManager.close();
        }
    }

    @Test
    void skipsBackgroundWriteWhenRuntimeStateIsAbsent() {
        TranscriptMiddleware middleware = new TranscriptMiddleware(new WorkspaceManager(workspace));
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent-a");
        Msg userMessage = message("m1", MsgRole.USER, "hello");
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();
        AgentEndEvent endEvent = new AgentEndEvent("reply-1");

        assertEquals(
                List.of(endEvent),
                middleware
                        .onAgent(
                                agent,
                                rc,
                                new AgentInput(List.of(userMessage)),
                                input -> Flux.just(endEvent))
                        .collectList()
                        .block(Duration.ofSeconds(2)));
    }

    @Test
    void skipsBackgroundWriteWhenRuntimeStateContextIsEmpty() {
        TranscriptMiddleware middleware = new TranscriptMiddleware(new WorkspaceManager(workspace));
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent-a");
        Msg userMessage = message("m1", MsgRole.USER, "hello");
        RuntimeContext rc =
                RuntimeContext.builder()
                        .sessionId("session-1")
                        .agentState(AgentState.builder().build())
                        .build();

        assertEquals(
                1,
                middleware
                        .onAgent(
                                agent,
                                rc,
                                new AgentInput(List.of(userMessage)),
                                input -> Flux.just(new AgentEndEvent("reply-1")))
                        .count()
                        .block(Duration.ofSeconds(2)));
    }

    @Test
    void sandboxTranscriptFailureDoesNotFailAgentStream() {
        TranscriptMiddleware middleware = new TranscriptMiddleware(new WorkspaceManager(workspace));
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenThrow(new IllegalStateException("writer setup failed"));
        Msg userMessage = message("m1", MsgRole.USER, "hello");
        RuntimeContext rc =
                RuntimeContext.builder()
                        .sessionId("session-1")
                        .agentState(AgentState.builder().addMessage(userMessage).build())
                        .put(
                                SandboxAcquireResult.class,
                                SandboxAcquireResult.userManaged(mock(Sandbox.class)))
                        .build();

        assertEquals(
                1,
                middleware
                        .onAgent(
                                agent,
                                rc,
                                new AgentInput(List.of(userMessage)),
                                input -> Flux.just(new AgentEndEvent("reply-1")))
                        .count()
                        .block(Duration.ofSeconds(2)));
    }

    @Test
    void backgroundTranscriptFailureDoesNotFailAgentStream() {
        TranscriptMiddleware middleware = new TranscriptMiddleware(new WorkspaceManager(workspace));
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenThrow(new IllegalStateException("writer setup failed"));
        Msg userMessage = message("m1", MsgRole.USER, "hello");
        RuntimeContext rc =
                RuntimeContext.builder()
                        .sessionId("session-1")
                        .agentState(AgentState.builder().addMessage(userMessage).build())
                        .build();

        assertEquals(
                1,
                middleware
                        .onAgent(
                                agent,
                                rc,
                                new AgentInput(List.of(userMessage)),
                                input -> Flux.just(new AgentEndEvent("reply-1")))
                        .count()
                        .block(Duration.ofSeconds(2)));
        assertTrue(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS));
    }

    private static Msg message(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static final class BlockingWorkspaceManager extends WorkspaceManager {

        private final CountDownLatch appendStarted;
        private final CountDownLatch appendFinishGate;

        private BlockingWorkspaceManager(
                Path workspace, CountDownLatch appendStarted, CountDownLatch appendFinishGate) {
            super(workspace);
            this.appendStarted = appendStarted;
            this.appendFinishGate = appendFinishGate;
        }

        @Override
        public void updateSessionIndex(
                RuntimeContext rc, String agentId, String sessionId, String summary) {
            appendStarted.countDown();
            await(appendFinishGate);
        }
    }

    private static final class BlockingResolveWorkspaceManager extends WorkspaceManager {

        private final CountDownLatch resolveStarted;
        private final CountDownLatch resolveFinishGate;

        private BlockingResolveWorkspaceManager(
                Path workspace, CountDownLatch resolveStarted, CountDownLatch resolveFinishGate) {
            super(workspace);
            this.resolveStarted = resolveStarted;
            this.resolveFinishGate = resolveFinishGate;
        }

        @Override
        public Path resolveSessionContextFile(RuntimeContext rc, String agentId, String sessionId) {
            resolveStarted.countDown();
            await(resolveFinishGate);
            return super.resolveSessionContextFile(rc, agentId, sessionId);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for latch", e);
        }
    }
}
