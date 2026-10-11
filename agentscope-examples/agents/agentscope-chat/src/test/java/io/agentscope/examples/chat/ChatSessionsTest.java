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
package io.agentscope.examples.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatSessionsTest {
    @TempDir Path workspace;

    @Test
    void queuedTasksAreDistinctAndSteeringStaysInCurrentExecution() throws Exception {
        try (var sessions =
                new ChatSessions(
                        workspace,
                        new DemoChatModel(Duration.ofMillis(15), Duration.ofMillis(20)))) {
            var session = sessions.session("conversation");
            var first = session.submit("first", "/slow investigate");
            var running =
                    await(
                            sessions,
                            s ->
                                    s.trace().stream()
                                            .anyMatch(e -> e.type().equals("model/dispatch")));
            String runId = running.turns().get(0).runId();
            var next = session.submit("second", "follow-up task");
            assertNotEquals(first.turnId(), next.turnId());
            assertEquals("queued", next.status());
            var steer = sessions.steer("conversation", "focus on operating cost");
            assertEquals(first.turnId(), steer.turnId());
            sessions.inject("conversation", "supporting material");
            var completed =
                    await(
                            sessions,
                            s -> s.turns().size() == 2 && lastStatus(s).equals("completed"));
            assertEquals(runId, completed.turns().get(0).runId());
            assertTrue(
                    completed.messages().stream()
                            .anyMatch(m -> m.getTextContent().equals("focus on operating cost")));
            assertTrue(
                    completed.messages().stream()
                            .anyMatch(m -> m.getTextContent().equals("supporting material")));
            var facts = session.log().scan(0, session.log().head().seq());
            long starts = 0;
            for (var fact : facts) if (fact.type().equals("run/start")) starts++;
            assertEquals(2, starts);
            assertEquals(
                    3,
                    completed.items().stream().filter(item -> item.kind().equals("tool")).count());
            assertThrows(IllegalStateException.class, () -> session.steer("too late"));
        }
    }

    @Test
    void injectionDoesNotStartExecutionAndSurvivesRestart() throws Exception {
        try (var sessions = new ChatSessions(workspace, new DemoChatModel())) {
            var session = sessions.session("conversation");
            session.inject("durable reference material");
            assertTrue(session.tasks().isEmpty());
            assertEquals(0, session.log().head().seq());
        }
        try (var sessions = new ChatSessions(workspace, new DemoChatModel())) {
            var session = sessions.session("conversation");
            var task = session.submit("read the reference");
            var done = session.await(task).block(Duration.ofSeconds(15));
            assertEquals("completed", done.status());
            assertEquals(
                    1,
                    session.transcript().messages().stream()
                            .filter(m -> m.getTextContent().equals("durable reference material"))
                            .count());
        }
    }

    @Test
    void interruptedTaskKeepsQueuedWorkUntilExplicitContinuation() throws Exception {
        String firstTurn, secondTurn;
        try (var sessions =
                new ChatSessions(
                        workspace,
                        new DemoChatModel(Duration.ofMillis(15), Duration.ofMillis(20)))) {
            var session = sessions.session("conversation");
            firstTurn = session.submit("first", "/slow pause").turnId();
            await(
                    sessions,
                    s -> s.trace().stream().anyMatch(e -> e.type().equals("model/dispatch")));
            secondTurn = session.submit("second", "queued work").turnId();
            assertTrue(session.interrupt());
            await(sessions, s -> lastStatus(s).equals("interrupted"));
            assertEquals("queued", session.task("second").status());
        }
        try (var sessions =
                new ChatSessions(
                        workspace,
                        new DemoChatModel(Duration.ofMillis(2), Duration.ofMillis(10)))) {
            var session = sessions.session("conversation");
            session.resume(firstTurn);
            // Two durable turns with streamed output can exceed 15 seconds on Windows runners.
            var done =
                    await(
                            sessions,
                            s -> s.turns().size() == 2 && lastStatus(s).equals("completed"),
                            Duration.ofSeconds(60));
            assertEquals(
                    List.of(firstTurn, secondTurn),
                    done.turns().stream().map(ChatHistory.Turn::turnId).toList());
        }
    }

    @Test
    void committedHistorySurvivesAgentRecreationAndSubmissionIsIdempotent() throws Exception {
        long before;
        try (var first = new ChatSessions(workspace, new DemoChatModel())) {
            var started = first.submit("conversation", "turn-one", "remember this");
            assertEquals(
                    started.commandId(),
                    first.submit("conversation", "turn-one", "remember this").commandId());
            var snapshot = await(first, s -> lastStatus(s).equals("completed"));
            assertEquals(2, snapshot.messages().size());
            before = snapshot.checkpointSeq();
            assertTrue(before > 0);
            assertEquals(
                    started.commandId(),
                    first.submit("conversation", "turn-one", "remember this").commandId());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> first.submit("conversation", "turn-one", "changed"));
        }
        try (var rebuilt = new ChatSessions(workspace, new DemoChatModel())) {
            assertTrue(rebuilt.list().contains("conversation"));
            assertEquals(2, rebuilt.snapshot("conversation").messages().size());
            rebuilt.submit("conversation", "turn-two", "continue");
            var snapshot =
                    await(rebuilt, s -> s.turns().size() == 2 && lastStatus(s).equals("completed"));
            assertEquals(4, snapshot.messages().size());
            assertTrue(snapshot.checkpointSeq() > before);
            assertTrue(snapshot.messages().get(3).getTextContent().contains("remember this"));
        }
    }

    @Test
    void pendingExternalRequestSurvivesRestartAndContinuesSameTurn() throws Exception {
        String requestId, firstRun, turnId;
        try (var first = new ChatSessions(workspace, new DemoChatModel())) {
            turnId = first.submit("conversation", "turn-one", "/ask preference").turnId();
            var snapshot = await(first, s -> lastStatus(s).equals("suspended"));
            firstRun = snapshot.turns().get(0).runId();
            requestId = String.valueOf(snapshot.pending().get(0).data().get("requestId"));
            assertThrows(IllegalStateException.class, () -> first.resume("conversation", turnId));
        }
        try (var rebuilt = new ChatSessions(workspace, new DemoChatModel())) {
            assertEquals(1, rebuilt.snapshot("conversation").pending().size());
            var resumed = rebuilt.answer("conversation", requestId, "keep it short");
            assertEquals(turnId, resumed.turnId());
            assertNotEquals(firstRun, resumed.runId());
            var done = await(rebuilt, s -> lastStatus(s).equals("completed"));
            assertTrue(done.pending().isEmpty());
            assertTrue(
                    done.messages()
                            .get(done.messages().size() - 1)
                            .getTextContent()
                            .contains("keep it short"));
        }
    }

    @Test
    void interruptThenResumeKeepsInputAndTurnAndSeparatesExecutionIds() throws Exception {
        String turnId;
        try (var sessions = new ChatSessions(workspace, new DemoChatModel())) {
            var run = sessions.submit("conversation", "turn-one", "/slow recovery");
            turnId = run.turnId();
            await(
                    sessions,
                    s -> s.trace().stream().anyMatch(e -> e.type().equals("model/dispatch")));
            sessions.interrupt("conversation", run.runId());
            var stopped = await(sessions, s -> lastStatus(s).equals("interrupted"));
            assertFalse(stopped.messages().isEmpty());
            assertTrue(stopped.uncertainTools().isEmpty());
        }
        try (var rebuilt = new ChatSessions(workspace, new DemoChatModel())) {
            var resumed = rebuilt.resume("conversation", turnId);
            assertEquals(turnId, resumed.turnId());
            await(rebuilt, s -> lastStatus(s).equals("running"));
            rebuilt.interrupt("conversation", resumed.runId());
            await(rebuilt, s -> lastStatus(s).equals("interrupted"));
        }
    }

    @Test
    void multiStepDemoRetainsEveryToolAndMessageAcrossReconstruction() throws Exception {
        ChatHistory.Snapshot completed;
        try (var sessions =
                new ChatSessions(
                        workspace,
                        new DemoChatModel(Duration.ofMillis(2), Duration.ofMillis(40)))) {
            sessions.submit("conversation", "multi-step", "/slow replay everything");
            completed = await(sessions, s -> lastStatus(s).equals("completed"));
            var tools =
                    completed.items().stream().filter(item -> item.kind().equals("tool")).toList();
            assertEquals(3, tools.size());
            assertTrue(tools.stream().allMatch(item -> item.status().equals("success")));
            assertTrue(tools.stream().allMatch(item -> item.progress().size() == 4));
            assertTrue(
                    tools.stream()
                            .allMatch(
                                    item ->
                                            item.content().stream()
                                                            .filter(
                                                                    ToolResultBlock.class
                                                                            ::isInstance)
                                                            .count()
                                                    == 1));
            assertEquals(
                    3,
                    completed.items().stream()
                            .filter(item -> item.role().equals("assistant"))
                            .count());
        }
        try (var rebuilt = new ChatSessions(workspace, new DemoChatModel())) {
            var restored = rebuilt.snapshot("conversation");
            assertEquals(completed.cursor(), restored.cursor());
            assertEquals(
                    JsonUtils.getJsonCodec().toJson(completed.items()),
                    JsonUtils.getJsonCodec().toJson(restored.items()));
        }
    }

    @Test
    void cursorsAreSessionScopedAndCannotSkipBeyondHistory() {
        try (var sessions = new ChatSessions(workspace, new DemoChatModel())) {
            assertThrows(IllegalArgumentException.class, () -> ChatHistory.position("a", "b:3"));
            assertThrows(
                    IllegalArgumentException.class, () -> sessions.events("conversation", 1, 10));
            assertEquals(0, ChatHistory.position("a", "a:0"));
        }
    }

    private String lastStatus(ChatHistory.Snapshot snapshot) {
        return snapshot.turns().isEmpty()
                ? "empty"
                : snapshot.turns().get(snapshot.turns().size() - 1).status();
    }

    private ChatHistory.Snapshot await(ChatSessions sessions, Predicate<ChatHistory.Snapshot> ready)
            throws Exception {
        return await(sessions, ready, Duration.ofSeconds(15));
    }

    private ChatHistory.Snapshot await(
            ChatSessions sessions, Predicate<ChatHistory.Snapshot> ready, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        ChatHistory.Snapshot snapshot;
        do {
            snapshot = sessions.snapshot("conversation");
            if (snapshot.executionError() != null)
                throw new AssertionError(snapshot.executionError());
            if (ready.test(snapshot)) {
                Thread.sleep(50);
                return snapshot;
            }
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out: " + snapshot.turns());
    }
}
