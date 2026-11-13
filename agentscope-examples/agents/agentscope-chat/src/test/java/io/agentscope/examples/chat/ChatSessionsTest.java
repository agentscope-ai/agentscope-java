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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.TurnView;
import io.agentscope.durable.EventProjection;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ChatSessionsTest {
    @TempDir Path workspace;
    @TempDir Path stateHome;
    private String previousStateHome;

    @BeforeEach
    void isolateLegacyState() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreLegacyStateHome() {
        if (previousStateHome == null) System.clearProperty("agentscope.state.home");
        else System.setProperty("agentscope.state.home", previousStateHome);
    }

    private ChatSessions open() {
        return new ChatSessions(
                workspace, new DemoChatModel(Duration.ofMillis(8), Duration.ofMillis(20)));
    }

    @Test
    void queriesNeverCreateSessionsAndExplicitCreationIsIdempotent() {
        try (var sessions = open()) {
            assertEquals(
                    DurableException.Code.NOT_FOUND,
                    assertThrows(DurableException.class, () -> sessions.snapshot("missing"))
                            .code());
            assertTrue(sessions.list().isEmpty());
            assertEquals(sessions.create("conversation"), sessions.create("conversation"));
            assertEquals(List.of("conversation"), sessions.list());
        }
    }

    @Test
    void queuedTasksAreDistinctAndSupplementalInputsStayInTheirTurn() throws Exception {
        try (var sessions = open()) {
            sessions.create("conversation");
            var first = sessions.submit("conversation", "first", "/slow investigate");
            await(sessions, s -> status(s, first.turnId()) == TurnStatus.RUNNING);
            var next = sessions.submit("conversation", "second", "follow-up task");
            assertNotEquals(first.turnId(), next.turnId());
            assertEquals(TurnStatus.QUEUED, next.status());
            sessions.steer("conversation", first.turnId(), "guidance", "focus on operating cost");
            sessions.inject("conversation", first.turnId(), "material", "supporting material");
            var done =
                    await(
                            sessions,
                            s ->
                                    s.turns().size() == 2
                                            && s.turns().stream()
                                                    .allMatch(
                                                            t ->
                                                                    t.status()
                                                                            == TurnStatus
                                                                                    .SUCCEEDED));
            assertEquals(
                    1,
                    messages(done).stream()
                            .filter(m -> m.path("text").asText().equals("focus on operating cost"))
                            .count());
            assertEquals(
                    1,
                    messages(done).stream()
                            .filter(m -> m.path("text").asText().equals("supporting material"))
                            .count());
            assertEquals(3, done.projection().path("tools").size());
            assertThrows(
                    DurableException.class,
                    () -> sessions.steer("conversation", first.turnId(), "late", "too late"));
            assertTrue(
                    sessions.runs("conversation", first.turnId(), null, 100).items().stream()
                            .allMatch(run -> run.turnId().equals(first.turnId())));
        }
    }

    @Test
    void pauseKeepsQueuedWorkAcrossRestartAndResumeUsesNewRun() throws Exception {
        String first, second, oldRun;
        long revision;
        try (var sessions = open()) {
            sessions.create("conversation");
            first = sessions.submit("conversation", "first", "/slow pause").turnId();
            await(
                    sessions,
                    s ->
                            status(s, first) == TurnStatus.RUNNING
                                    && sessions
                                            .runs("conversation", first, null, 100)
                                            .items()
                                            .stream()
                                            .anyMatch(run -> run.startedAt() != null));
            second = sessions.submit("conversation", "second", "queued work").turnId();
            sessions.pause("conversation", first, "pause");
            var paused = await(sessions, s -> status(s, first) == TurnStatus.PAUSED);
            assertEquals(TurnStatus.QUEUED, status(paused, second));
            revision = paused.turns().get(0).revision();
            oldRun = sessions.runs("conversation", first, null, 100).items().get(0).runId();
        }
        try (var sessions = open()) {
            assertEquals(TurnStatus.PAUSED, status(sessions.snapshot("conversation"), first));
            assertThrows(
                    DurableException.class,
                    () -> sessions.resume("conversation", first, "stale", revision - 1));
            sessions.resume("conversation", first, "continue", revision);
            var done = await(sessions, s -> status(s, second) == TurnStatus.SUCCEEDED);
            assertEquals(
                    List.of(first, second), done.turns().stream().map(TurnView::turnId).toList());
            assertEquals(TurnStatus.SUCCEEDED, status(done, first));
            var runs = sessions.runs("conversation", first, null, 100).items();
            assertTrue(runs.size() > 1);
            assertNotEquals(oldRun, runs.get(runs.size() - 1).runId());
        }
    }

    @Test
    void inputsOnPausedTaskPersistWithoutStartingAndApplyAfterResume() throws Exception {
        String turn;
        try (var sessions = open()) {
            sessions.create("conversation");
            turn = sessions.submit("conversation", "ask", "/ask preference").turnId();
            await(sessions, s -> status(s, turn) == TurnStatus.WAITING);
            sessions.pause("conversation", turn, "pause");
            await(sessions, s -> status(s, turn) == TurnStatus.PAUSED);
            sessions.inject("conversation", turn, "reference", "durable reference material");
            assertEquals(TurnStatus.PAUSED, sessions.turn("conversation", turn).status().status());
        }
        try (var sessions = open()) {
            var before = sessions.snapshot("conversation");
            var interaction = pending(before).get(0);
            sessions.answer(
                    "conversation",
                    turn,
                    "answer",
                    interaction.path("id").asText(),
                    "keep it short");
            assertEquals(TurnStatus.PAUSED, sessions.turn("conversation", turn).status().status());
            assertEquals(1, sessions.runs("conversation", turn, null, 100).items().size());
            sessions.resume(
                    "conversation",
                    turn,
                    "resume",
                    sessions.turn("conversation", turn).status().revision());
            var done = await(sessions, s -> status(s, turn) == TurnStatus.SUCCEEDED);
            assertEquals(
                    1,
                    messages(done).stream()
                            .filter(
                                    m ->
                                            m.path("text")
                                                    .asText()
                                                    .equals("durable reference material"))
                            .count());
            assertTrue(pending(done).isEmpty());
        }
    }

    @Test
    void committedHistoryAndSubmissionReceiptsSurviveRecreation() throws Exception {
        String turn, cursor;
        try (var sessions = open()) {
            sessions.create("conversation");
            turn = sessions.submit("conversation", "turn-one", "remember this").turnId();
            assertEquals(
                    turn, sessions.submit("conversation", "turn-one", "remember this").turnId());
            var done = await(sessions, s -> status(s, turn) == TurnStatus.SUCCEEDED);
            assertEquals(2, messages(done).size());
            cursor = done.cursor();
            assertThrows(
                    DurableException.class,
                    () -> sessions.submit("conversation", "turn-one", "changed"));
        }
        try (var sessions = open()) {
            assertEquals(cursor, sessions.snapshot("conversation").cursor());
            assertEquals(
                    turn, sessions.submit("conversation", "turn-one", "remember this").turnId());
            var next = sessions.submit("conversation", "turn-two", "continue");
            var done = await(sessions, s -> status(s, next.turnId()) == TurnStatus.SUCCEEDED);
            assertEquals(4, messages(done).size());
            assertTrue(messages(done).get(3).path("text").asText().contains("remember this"));
        }
    }

    @Test
    void pendingRequestSurvivesRestartAndAnswerContinuesSameTurn() throws Exception {
        String turn, interactionId, firstRun;
        try (var sessions = open()) {
            sessions.create("conversation");
            turn = sessions.submit("conversation", "turn-one", "/ask preference").turnId();
            var waiting = await(sessions, s -> status(s, turn) == TurnStatus.WAITING);
            interactionId = pending(waiting).get(0).path("id").asText();
            firstRun = sessions.runs("conversation", turn, null, 100).items().get(0).runId();
            assertThrows(
                    DurableException.class,
                    () ->
                            sessions.resume(
                                    "conversation",
                                    turn,
                                    "bypass",
                                    waiting.turns().get(0).revision()));
        }
        try (var sessions = open()) {
            sessions.answer("conversation", turn, "answer", interactionId, "keep it short");
            var done = await(sessions, s -> status(s, turn) == TurnStatus.SUCCEEDED);
            assertTrue(pending(done).isEmpty());
            assertTrue(
                    messages(done).stream()
                            .anyMatch(
                                    m -> m.path("text").asText().contains("收到你的补充：keep it short")));
            var runs = sessions.runs("conversation", turn, null, 100).items();
            assertEquals(2, runs.size());
            assertNotEquals(firstRun, runs.get(1).runId());
            assertThrows(
                    DurableException.class,
                    () ->
                            sessions.answer(
                                    "conversation",
                                    turn,
                                    "another-answer",
                                    interactionId,
                                    "changed"));
        }
    }

    @Test
    void cancelTargetsTheQueuedTurnWithoutCancellingTheWaitingTask() throws Exception {
        try (var sessions = open()) {
            sessions.create("conversation");
            String first = sessions.submit("conversation", "first", "/ask preference").turnId();
            var waiting = await(sessions, s -> status(s, first) == TurnStatus.WAITING);
            String second = sessions.submit("conversation", "second", "queued").turnId();
            sessions.cancel("conversation", second, "cancel-second");
            assertEquals(
                    TurnStatus.CANCELLED, sessions.turn("conversation", second).status().status());
            assertEquals(
                    TurnStatus.WAITING, sessions.turn("conversation", first).status().status());
            assertTrue(sessions.runs("conversation", second, null, 100).items().isEmpty());
            sessions.answer(
                    "conversation",
                    first,
                    "answer",
                    pending(waiting).get(0).path("id").asText(),
                    "yes");
            await(sessions, s -> status(s, first) == TurnStatus.SUCCEEDED);
        }
    }

    @Test
    void multiStepMessagesToolsProgressAndReplaySurviveRecreation() throws Exception {
        Snapshot completed;
        try (var sessions = open()) {
            sessions.create("conversation");
            String turn =
                    sessions.submit("conversation", "multi-step", "/slow replay everything")
                            .turnId();
            completed = await(sessions, s -> status(s, turn) == TurnStatus.SUCCEEDED);
            assertEquals(3, completed.projection().path("tools").size());
            for (var tool : completed.projection().path("tools")) {
                assertEquals("SUCCEEDED", tool.path("data").path("status").asText());
                assertEquals(4, tool.path("data").path("progress").size());
                assertFalse(tool.path("data").path("content").isEmpty());
            }
            assertEquals(
                    3,
                    messages(completed).stream()
                            .filter(
                                    m ->
                                            m.path("message")
                                                    .path("role")
                                                    .asText()
                                                    .equals("assistant"))
                            .count());
            var projection = new EventProjection(completed.session());
            for (var event : events(sessions)) {
                projection.accept(event);
                projection.accept(event);
            }
            // Compare the complete wire representation: JSON integer widths are not protocol state.
            assertEquals(
                    DurableJson.parse(completed.projection().toString()),
                    DurableJson.parse(projection.value().toString()));
            assertEquals(completed.cursor(), projection.cursor());
            var runs = sessions.runs("conversation", turn, null, 100).items();
            assertEquals(1, runs.size());
            assertEquals(
                    3,
                    sessions.turn("conversation", turn)
                            .listSteps(runs.get(0).runId(), null, 100)
                            .items()
                            .size());
        }
        try (var sessions = open()) {
            var restored = sessions.snapshot("conversation");
            assertEquals(completed, restored);
        }
    }

    @Test
    void cursorsAreOpaqueAndSessionScoped() {
        try (var sessions = open()) {
            sessions.create("conversation");
            sessions.create("other");
            var cursor = sessions.snapshot("conversation").cursor();
            assertThrows(DurableException.class, () -> sessions.events("other", cursor, 10));
            assertThrows(
                    DurableException.class,
                    () -> sessions.events("conversation", "conversation:1", 10));
            assertTrue(sessions.events("conversation", cursor, 10).events().isEmpty());
        }
    }

    static List<JsonNode> messages(Snapshot snapshot) {
        return values(snapshot.projection().path("messages")).stream()
                .sorted(
                        Comparator.comparing(
                                message -> new BigInteger(message.path("sequence").asText())))
                .toList();
    }

    static List<JsonNode> pending(Snapshot snapshot) {
        return values(snapshot.projection().path("interactions")).stream()
                .filter(
                        i ->
                                "pending".equals(i.path("status").asText())
                                        && !i.path("automatic").asBoolean())
                .toList();
    }

    static List<JsonNode> values(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).toList();
    }

    static TurnStatus status(Snapshot snapshot, String id) {
        return snapshot.turns().stream()
                .filter(t -> t.turnId().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
    }

    static List<PublicEvent> events(ChatSessions sessions) {
        var events = new ArrayList<PublicEvent>();
        String cursor = null;
        while (true) {
            var page = sessions.events("conversation", cursor, 100);
            events.addAll(page.events());
            cursor = page.cursor();
            if (page.events().isEmpty()) return events;
        }
    }

    static Snapshot await(ChatSessions sessions, Predicate<Snapshot> ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Snapshot snapshot;
        do {
            snapshot = sessions.snapshot("conversation");
            if (ready.test(snapshot)) return snapshot;
            if (snapshot.turns().stream().anyMatch(t -> t.status() == TurnStatus.FAILED))
                throw new AssertionError(DurableJson.value(snapshot.turns()).toString());
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out: " + snapshot.turns());
    }
}
