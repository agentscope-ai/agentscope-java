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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class RepeatedFailureGuardTest {

    private static final String TOOL = "read_file";
    private static final RuntimeContext SESSION_1 =
            RuntimeContext.builder().userId("u").sessionId("s1").threadId("main").build();
    private static final RuntimeContext SESSION_2 =
            RuntimeContext.builder().userId("u").sessionId("s2").threadId("main").build();

    private final RepeatedFailureGuard guard = new RepeatedFailureGuard();

    private void fail(RuntimeContext ctx, String tool, ToolErrorKind kind, String scope) {
        fail(ctx, tool, kind, scope, false);
    }

    private void fail(
            RuntimeContext ctx, String tool, ToolErrorKind kind, String scope, boolean retriable) {
        emit(ctx, eventFor(tool, ToolResultState.ERROR, kind, scope, retriable));
    }

    private static ToolResultEndEvent eventFor(
            String tool,
            ToolResultState state,
            ToolErrorKind kind,
            String scope,
            Boolean retriable) {
        ToolResultEndEvent event = new ToolResultEndEvent("reply", "call", tool, state);
        if (kind != null) {
            event.withMetadata(ToolErrorMetadata.of(kind, scope, retriable));
        }
        return event;
    }

    private void emit(RuntimeContext ctx, ToolResultEndEvent event) {
        guard.onActing(null, ctx, new ActingInput(List.of()), input -> Flux.just(event))
                .blockLast();
    }

    private List<Msg> nextReasoning(RuntimeContext ctx) {
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        guard.onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(List.of(), List.of(), null),
                        input -> {
                            seen.set(input);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        assertNotNull(seen.get());
        return seen.get().messages();
    }

    private void newTurn(RuntimeContext ctx) {
        guard.onAgent(null, ctx, new AgentInput(List.of()), input -> Flux.empty()).blockLast();
    }

    @Test
    void twoFailuresDoNotTriggerAndTheThirdAddsOneReminderOnce() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());

        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        String text = reminder.get(0).getTextContent();
        assertTrue(text.contains(TOOL), text);
        assertTrue(text.contains("not_found"), text);
        assertTrue(text.contains("a.txt"), text);
        assertEquals(Boolean.TRUE, reminder.get(0).getMetadata().get("synthetic"));
        assertEquals("repeated_failure", reminder.get(0).getMetadata().get("reminderKind"));
        assertTrue(text.length() <= RepeatedFailureGuard.MAX_REMINDER_CHARS);

        assertTrue(nextReasoning(SESSION_1).isEmpty(), "reminder is one-shot");
    }

    @Test
    void differentScopesOrKindsDoNotMerge() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "b.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.INVALID_ARGUMENT, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void successOnTheMatchingScopeClearsItsFailures() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        ToolResultEndEvent success = eventFor(TOOL, ToolResultState.SUCCESS, null, null, null);
        success.withMetadata(Map.of(ToolErrorMetadata.METADATA_ERROR_SCOPE, "a.txt"));
        emit(SESSION_1, success);
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());

        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void successOnAnotherToolDoesNotClearFailures() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        emit(SESSION_1, eventFor("grep", ToolResultState.SUCCESS, null, null, null));
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void retriableFailuresUseTheHigherThreshold() {
        for (int i = 0; i < RepeatedFailureGuard.RETRIABLE_FAILURE_THRESHOLD - 1; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.TIMEOUT, "a.txt", true);
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        fail(SESSION_1, TOOL, ToolErrorKind.TIMEOUT, "a.txt", true);
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void unclassifiedErrorsAreNeverCounted() {
        for (int i = 0; i < 10; i++) {
            emit(SESSION_1, eventFor(TOOL, ToolResultState.ERROR, null, null, null));
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void deniedWithoutKindIsTreatedAsPermissionDenied() {
        for (int i = 0; i < RepeatedFailureGuard.FAILURE_THRESHOLD; i++) {
            emit(SESSION_1, eventFor(TOOL, ToolResultState.DENIED, null, null, null));
        }
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        assertTrue(reminder.get(0).getTextContent().contains("permission_denied"));
    }

    @Test
    void sessionsAreIsolated() {
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_2, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_2, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        assertTrue(nextReasoning(SESSION_2).isEmpty());

        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
        assertTrue(nextReasoning(SESSION_2).isEmpty());
    }

    @Test
    void atMostTwoRemindersPerTurn() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty(), "third reminder is suppressed");
    }

    @Test
    void aNewTurnResetsTheCounters() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());

        newTurn(SESSION_1);
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void finishingTheCallResetsTheCounters() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        guard.onAgentFinish(null, SESSION_1, null).block();
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void eachSignatureUsesItsOwnInterventionProgress() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());

        // A different loop reaches its own first threshold instead of inheriting the first
        // signature's progress.
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, "grep", ToolErrorKind.INVALID_ARGUMENT, "a.txt");
        }
        List<Msg> second = nextReasoning(SESSION_1);
        assertEquals(1, second.size());
        assertTrue(second.get(0).getTextContent().contains("grep"));
    }

    @Test
    void aSuccessLetsTheSameSignatureStartOver() {
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());

        ToolResultEndEvent success = eventFor(TOOL, ToolResultState.SUCCESS, null, null, null);
        success.withMetadata(Map.of(ToolErrorMetadata.METADATA_ERROR_SCOPE, "a.txt"));
        emit(SESSION_1, success);
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, "a.txt");
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void unknownToolNameFallsBackToAStableDomain() {
        for (int i = 0; i < 3; i++) {
            emit(
                    SESSION_1,
                    eventFor(
                            null, ToolResultState.ERROR, ToolErrorKind.INTERNAL_ERROR, null, null));
        }
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        assertTrue(reminder.get(0).getTextContent().contains("unknown"));
    }

    @Test
    void reminderTextIsBoundedEvenForDeepScopes() {
        String scope = "x".repeat(5_000);
        for (int i = 0; i < 3; i++) {
            fail(SESSION_1, TOOL, ToolErrorKind.NOT_FOUND, scope);
        }
        List<Msg> reminder = nextReasoning(SESSION_1);
        assertEquals(1, reminder.size());
        String text = reminder.get(0).getTextContent();
        assertTrue(text.length() <= RepeatedFailureGuard.MAX_REMINDER_CHARS, text);
        assertFalse(text.isEmpty());
    }

    private void completedCall(
            Map<String, Object> arguments, ToolResultState state, String... chunks) {
        List<AgentEvent> events = new ArrayList<>();
        for (String chunk : chunks) {
            events.add(new ToolResultTextDeltaEvent("reply", "call", "execute", chunk));
        }
        events.add(eventFor("execute", state, null, null, null));
        guard.onActing(
                        null,
                        SESSION_1,
                        new ActingInput(List.of(new ToolUseBlock("call", "execute", arguments))),
                        input -> Flux.fromIterable(events))
                .blockLast();
    }

    @Test
    void identicalSuccessfulCallsGetANeutralReminderAndKeepTheirState() {
        for (int i = 0; i < 3; i++) {
            completedCall(
                    Map.of("command", "business unknown"),
                    ToolResultState.SUCCESS,
                    "Unknown public tool: unknown");
        }
        String text = nextReasoning(SESSION_1).get(0).getTextContent();
        assertTrue(text.contains("identical arguments"), text);
        assertTrue(text.contains("does not imply failure"), text);
        assertFalse(text.contains("has failed"), text);
        assertFalse(text.contains("business unknown"), "raw arguments must not be echoed");
        assertFalse(text.contains("Unknown public tool"), "raw output must not be echoed");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void fingerprintsIgnoreMapOrderingCallIdsAndTextChunkBoundaries() {
        completedCall(
                Map.of("a", 1, "nested", Map.of("x", 2, "y", 3)),
                ToolResultState.SUCCESS,
                "same",
                " result");
        completedCall(
                Map.of("nested", Map.of("y", 3, "x", 2), "a", 1),
                ToolResultState.SUCCESS,
                "same result");
        completedCall(
                Map.of("a", 1, "nested", Map.of("x", 2, "y", 3)),
                ToolResultState.SUCCESS,
                "sa",
                "me result");
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void changedArgumentsOrResultsDoNotAccumulateARepetition() {
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "one");
        completedCall(Map.of("command", "b"), ToolResultState.SUCCESS, "one");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "two");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "one");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "one");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void anUnclassifiedErrorCanRepeatWithoutBeingClassified() {
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "a"), ToolResultState.ERROR, "opaque result");
        }
        assertTrue(
                nextReasoning(SESSION_1)
                        .get(0)
                        .getTextContent()
                        .contains("does not imply failure"));
    }

    @Test
    void successForAnotherScopeDoesNotClearTheFailureWindow() {
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        completedCall(Map.of("command", "ls"), ToolResultState.SUCCESS, "listing");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        String text = nextReasoning(SESSION_1).get(0).getTextContent();
        assertTrue(text.contains("command_failed"), text);
        assertTrue(text.contains("diagnostic output"), text);
    }

    @Test
    void successForTheExactCallClearsOnlyThatCallsFailures() {
        for (int i = 0; i < 2; i++) {
            ToolResultEndEvent error =
                    eventFor(
                            "execute",
                            ToolResultState.ERROR,
                            ToolErrorKind.COMMAND_FAILED,
                            "python",
                            null);
            guard.onActing(
                            null,
                            SESSION_1,
                            new ActingInput(
                                    List.of(
                                            new ToolUseBlock(
                                                    "call",
                                                    "execute",
                                                    Map.of("command", "python a")))),
                            input -> Flux.just(error))
                    .blockLast();
        }
        completedCall(Map.of("command", "ls"), ToolResultState.SUCCESS, "listing");
        completedCall(Map.of("command", "python a"), ToolResultState.SUCCESS, "fixed");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        fail(SESSION_1, "execute", ToolErrorKind.COMMAND_FAILED, "python");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void dataResultsAreFingerprintedAndInterleavedCallsStayIsolated() {
        for (int i = 0; i < 3; i++) {
            String id = "data-" + i;
            guard.onActing(
                            null,
                            SESSION_1,
                            new ActingInput(
                                    List.of(
                                            new ToolUseBlock(id, TOOL, Map.of("path", "a")),
                                            new ToolUseBlock("other", TOOL, Map.of("path", "b")))),
                            input ->
                                    Flux.just(
                                            new ToolResultTextDeltaEvent(
                                                    "reply", "other", TOOL, "unrelated-" + id),
                                            new ToolResultDataDeltaEvent(
                                                    "reply",
                                                    id,
                                                    TOOL,
                                                    TextBlock.builder().text("same").build()),
                                            new ToolResultEndEvent(
                                                    "reply",
                                                    "other",
                                                    TOOL,
                                                    ToolResultState.SUCCESS),
                                            new ToolResultEndEvent(
                                                    "reply", id, TOOL, ToolResultState.SUCCESS)))
                    .blockLast();
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void repetitionRespectsTheSharedCapAndResetsOnNewTurns() {
        for (int n = 0; n < 3; n++) {
            for (int i = 0; i < 3; i++) {
                completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "same");
            }
            assertEquals(n < 2 ? 1 : 0, nextReasoning(SESSION_1).size());
        }
        newTurn(SESSION_1);
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "same");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "same");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void completedEmptyOutputIsEquivalentAcrossDeltaShapesAndResetsChangedResults() {
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS);
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS);
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "");
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS);
        assertEquals(1, nextReasoning(SESSION_1).size());
    }

    @Test
    void aChangedResultRetractsPendingRepetitionWithoutSpendingAnIntervention() {
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "x");
        }
        completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "changed");
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        for (int n = 0; n < 2; n++) {
            for (int i = 0; i < 3; i++) {
                completedCall(Map.of("command", "a"), ToolResultState.SUCCESS, "changed");
            }
            assertEquals(1, nextReasoning(SESSION_1).size());
        }
    }

    @Test
    void multipleThresholdsInOneBatchSpendOnlyTheDeliveredReminder() {
        List<ToolUseBlock> calls = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        for (String command : List.of("a", "b")) {
            for (int i = 0; i < 3; i++) {
                String id = command + i;
                calls.add(new ToolUseBlock(id, "execute", Map.of("command", command)));
                events.add(new ToolResultTextDeltaEvent("reply", id, "execute", "same"));
                events.add(new ToolResultEndEvent("reply", id, "execute", ToolResultState.SUCCESS));
            }
        }
        guard.onActing(null, SESSION_1, new ActingInput(calls), input -> Flux.fromIterable(events))
                .blockLast();
        assertEquals(1, nextReasoning(SESSION_1).size());
        completedCall(Map.of("command", "b"), ToolResultState.SUCCESS, "same");
        assertEquals(1, nextReasoning(SESSION_1).size());
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "b"), ToolResultState.SUCCESS, "same");
        }
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    @Test
    void classifiedAndFingerprintPathsDoNotDoubleRemindForOneFailureLoop() {
        for (int n = 0; n < 3; n++) {
            ToolResultEndEvent error =
                    eventFor(
                            "execute",
                            ToolResultState.ERROR,
                            ToolErrorKind.COMMAND_FAILED,
                            "python",
                            null);
            guard.onActing(
                            null,
                            SESSION_1,
                            new ActingInput(
                                    List.of(
                                            new ToolUseBlock(
                                                    "call",
                                                    "execute",
                                                    Map.of("command", "python a")))),
                            input ->
                                    Flux.just(
                                            new ToolResultTextDeltaEvent(
                                                    "reply", "call", "execute", "same"),
                                            error))
                    .blockLast();
        }
        assertEquals(1, nextReasoning(SESSION_1).size());
        ToolResultEndEvent error =
                eventFor(
                        "execute",
                        ToolResultState.ERROR,
                        ToolErrorKind.COMMAND_FAILED,
                        "python",
                        null);
        guard.onActing(
                        null,
                        SESSION_1,
                        new ActingInput(
                                List.of(
                                        new ToolUseBlock(
                                                "call", "execute", Map.of("command", "python a")))),
                        input ->
                                Flux.just(
                                        new ToolResultTextDeltaEvent(
                                                "reply", "call", "execute", "same"),
                                        error))
                .blockLast();
        assertTrue(nextReasoning(SESSION_1).isEmpty());
    }

    private void sameCallBatch(int count, boolean classified) {
        List<ToolUseBlock> calls = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "batch-" + i;
            calls.add(new ToolUseBlock(id, "execute", Map.of("command", "python a")));
            events.add(new ToolResultTextDeltaEvent("reply", id, "execute", "same"));
            ToolResultEndEvent end =
                    new ToolResultEndEvent(
                            "reply",
                            id,
                            "execute",
                            classified ? ToolResultState.ERROR : ToolResultState.SUCCESS);
            if (classified) {
                end.withMetadata(ToolErrorMetadata.of(ToolErrorKind.COMMAND_FAILED, "python"));
            }
            events.add(end);
        }
        guard.onActing(null, SESSION_1, new ActingInput(calls), input -> Flux.fromIterable(events))
                .blockLast();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void secondReminderRequiresThreeOccurrencesAfterActualInjection(boolean classified) {
        sameCallBatch(6, classified);
        String first = nextReasoning(SESSION_1).get(0).getTextContent();
        assertTrue(first.contains("6 times"), first);
        sameCallBatch(1, classified);
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        sameCallBatch(1, classified);
        assertTrue(nextReasoning(SESSION_1).isEmpty());
        sameCallBatch(1, classified);
        assertEquals(
                1,
                nextReasoning(SESSION_1).size(),
                "three further occurrences must work even after the eight-entry window saturates");
    }

    @Test
    void classificationChangesRetractAndResetUnclassifiedRepetitionsWithTheSameOutput() {
        for (int i = 0; i < 3; i++) {
            completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        }
        sameCallBatch(1, true);
        assertTrue(
                nextReasoning(SESSION_1).isEmpty(),
                "classified result retracts old neutral pending");
        completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        assertTrue(
                nextReasoning(SESSION_1).isEmpty(),
                "switching back starts a new repetition window");
        completedCall(Map.of("command", "python a"), ToolResultState.ERROR, "same");
        assertTrue(
                nextReasoning(SESSION_1)
                        .get(0)
                        .getTextContent()
                        .contains("does not imply failure"));
    }

    @Test
    void missingContextStillCountsUnderOneSlot() {
        assertNull(
                guard.onReasoning(
                                null,
                                null,
                                new ReasoningInput(List.of(), List.of(), null),
                                input -> Flux.<AgentEvent>empty())
                        .blockLast());
        for (int i = 0; i < 3; i++) {
            emit(null, eventFor(TOOL, ToolResultState.ERROR, ToolErrorKind.NOT_FOUND, "a", null));
        }
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        guard.onReasoning(
                        null,
                        null,
                        new ReasoningInput(List.of(), List.of(), null),
                        input -> {
                            seen.set(input);
                            return Flux.empty();
                        })
                .blockLast();
        assertNotNull(seen.get());
        assertEquals(1, seen.get().messages().size());
    }
}
