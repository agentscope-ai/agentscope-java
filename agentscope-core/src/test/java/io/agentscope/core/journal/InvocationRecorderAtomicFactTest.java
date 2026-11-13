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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.agentscope.core.agent.AgentExecutionPosition;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvocationRecorderAtomicFactTest {
    @ParameterizedTest
    @ValueSource(strings = {"completed", "cancelled", "MAX_ITERATIONS", "ALL_TOOLS_DENIED"})
    void terminalNativeCallsNeverLendTheirBudgetToTheNextCall(String terminal) {
        var log = new InMemoryThreadJournalStore().open(new ThreadKey("u", "a", "s", "main"), null);
        var state = AgentState.builder().sessionId("s").build();
        var first = new InvocationRecorder(log, null, "first", null);
        first.start(List.of(), state).block();
        first.step(9);
        first.reasoningCompleted(9);
        if (terminal.equals("MAX_ITERATIONS") || terminal.equals("ALL_TOOLS_DENIED")) {
            first.result(
                    new AssistantMessage("stopped")
                            .withGenerateReason(GenerateReason.valueOf(terminal)));
            first.finish(state, "completed").block();
        } else first.finish(state, terminal).block();
        var next = new InvocationRecorder(log, null, "next", null);
        try {
            assertEquals(new AgentExecutionPosition(0, 0), next.executionPosition());
        } finally {
            next.close();
        }
    }

    @Test
    void nativeReconciliationPreservesSourceAndPositionWithoutInventingAnInvocation() {
        var log = new InMemoryThreadJournalStore().open(new ThreadKey("u", "a", "s", "main"), null);
        var state = AgentState.builder().sessionId("s").build();
        var first = new InvocationRecorder(log, null, "first", null);
        first.start(List.of(), state).block();
        first.step(1);
        first.reasoningCompleted(1);
        first.checkpointNow(state, "decision");
        first.recordNow("tool/dispatch", Map.of("toolCallId", "tool"));
        first.close();
        assertThrows(
                JournalException.class, () -> new InvocationRecorder(log, null, "blocked", null));
        ThreadRecovery.reconcile(
                log,
                Map.of("tool", ToolResultBlock.text("verified").withIdAndName("tool", "external")),
                "operator verified");
        assertEquals("first", JournalProjection.read(log).positionRunId());
        var resumed = new InvocationRecorder(log, null, "resumed", null);
        try {
            assertEquals(new AgentExecutionPosition(2, 1), resumed.executionPosition());
            assertEquals(
                    1,
                    log.readAfter(0, 100).stream()
                            .map(JournalEvent::executionRunId)
                            .distinct()
                            .count());
        } finally {
            resumed.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspended", "interrupted", "failed", "crash"})
    void nativeContinuationUsesCommittedInvocationAndFreshCallsResetProgress(String stop) {
        var log = new InMemoryThreadJournalStore().open(new ThreadKey("u", "a", "s", "main"), null);
        var state = AgentState.builder().sessionId("s").build();
        var first = new InvocationRecorder(log, null, "first", null);
        first.start(List.of(), state).block();
        first.step(1);
        String sourceStep = first.stepId();
        first.reasoningCompleted(1);
        first.checkpointNow(state, "decision_applied");
        if ("crash".equals(stop)) first.close();
        else first.finish(state, stop).block();

        var resumed = new InvocationRecorder(log, null, "second", null);
        assertNull(resumed.turnId());
        assertEquals(new AgentExecutionPosition(2, 1), resumed.executionPosition());
        resumed.start(List.of(), state).block();
        resumed.resumeStep(1);
        String nextStep = resumed.stepId();
        assertNotEquals(sourceStep, nextStep);
        resumed.finish(state, "completed").block();
        var fact =
                log.readAfter(0, 100).stream()
                        .filter(
                                e ->
                                        "step/start".equals(e.type())
                                                && nextStep.equals(e.data().get("stepId")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(sourceStep, fact.data().get("resumedFromStepId"));

        var next = new InvocationRecorder(log, null, "third", null);
        assertEquals(new AgentExecutionPosition(0, 0), next.executionPosition());
        next.start(List.of(), state).block();
        next.close(); // Crash before the first model boundary of a fresh invocation.
        var recovered = new InvocationRecorder(log, null, "fourth", null);
        assertEquals(new AgentExecutionPosition(0, 0), recovered.executionPosition());
        recovered.start(List.of(), state).block();
        recovered.step(0);
        String freshStep = recovered.stepId();
        recovered.finish(state, "completed").block();
        var fresh =
                log.readAfter(0, 100).stream()
                        .filter(
                                e ->
                                        "step/start".equals(e.type())
                                                && freshStep.equals(e.data().get("stepId")))
                        .findFirst()
                        .orElseThrow();
        assertNull(fresh.data().get("resumedFromStepId"));
    }

    @Test
    void progressIsCheckpointedEvenWithoutStateChangesAndBelongsToOneTurn() {
        var log = new InMemoryThreadJournalStore().open(new ThreadKey("u", "a", "s", "main"), null);
        var state = AgentState.builder().sessionId("s").build();
        var first = new InvocationRecorder(log, "turn", "first", null);
        first.start(List.of(), state).block();
        first.step(0);
        first.reasoningCompleted(0);
        first.checkpointNow(state, "decision_applied");
        assertEquals(
                new AgentExecutionPosition(1, 0), JournalProjection.read(log).executionPosition());
        first.step(1);
        first.reasoningCompleted(1);
        first.checkpointNow(state, "progress_only");
        assertEquals(
                new AgentExecutionPosition(2, 1), JournalProjection.read(log).executionPosition());
        first.finish(state, "suspended").block();
        var resumed = new InvocationRecorder(log, "turn", "second", null);
        assertEquals(new AgentExecutionPosition(2, 1), resumed.executionPosition());
        resumed.start(List.of(), state).block();
        resumed.resumeStep(1);
        resumed.checkpointNow(state, "tool_applied");
        assertEquals(
                new AgentExecutionPosition(2, 1), JournalProjection.read(log).executionPosition());
        resumed.finish(state, "completed").block();
        var next = new InvocationRecorder(log, "next-turn", "third", null);
        try {
            assertEquals(new AgentExecutionPosition(0, 0), next.executionPosition());
        } finally {
            next.close();
        }
    }

    @Test
    void changedAndUnchangedStateEachCommitTheirInputReceiptWithOneCheckpoint() {
        var log =
                spy(
                        new InMemoryThreadJournalStore()
                                .open(new ThreadKey("u", "a", "s", "main"), null));
        var batches = new ArrayList<List<JournalEvent>>();
        doAnswer(
                        call -> {
                            batches.add(List.copyOf(call.getArgument(3)));
                            return call.callRealMethod();
                        })
                .when(log)
                .commit(any(), anyString(), anyLong(), anyList());
        var state = AgentState.builder().sessionId("s").build();
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        try {
            recorder.checkpointNow(state, "initial");
            batches.clear();
            recorder.checkpointedRecordNow(
                    state, "unchanged", "input/applied", Map.of("commandIds", List.of("first")));
            assertAtomic(batches, state);
            batches.clear();
            state.contextMutable().add(new UserMessage("added"));
            recorder.checkpointedRecordNow(
                    state, "changed", "input/applied", Map.of("commandIds", List.of("second")));
            assertAtomic(batches, state);
        } finally {
            recorder.close();
        }
        assertEquals(1, JournalProjection.read(log).restore().getContext().size());
        assertEquals(
                List.of("first", "second"),
                log.readAfter(0, 100).stream()
                        .filter(event -> "input/applied".equals(event.type()))
                        .flatMap(event -> ((List<?>) event.data().get("commandIds")).stream())
                        .toList());
    }

    private void assertAtomic(List<List<JournalEvent>> batches, AgentState state) {
        assertEquals(1, batches.size());
        assertEquals(
                1,
                batches.get(0).stream()
                        .filter(event -> "input/applied".equals(event.type()))
                        .count());
        var checkpoints =
                batches.get(0).stream()
                        .filter(event -> "state/checkpoint".equals(event.type()))
                        .toList();
        assertEquals(1, checkpoints.size());
        assertEquals(state.toJson(), checkpoints.get(0).data().get("stateJson"));
    }
}
