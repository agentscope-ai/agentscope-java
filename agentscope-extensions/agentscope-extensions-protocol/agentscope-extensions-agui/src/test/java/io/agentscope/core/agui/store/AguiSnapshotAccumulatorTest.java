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
package io.agentscope.core.agui.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-visible {@link AguiSnapshotAccumulator}, which absorbs the former
 * CopilotKit replay workarounds.
 */
class AguiSnapshotAccumulatorTest {

    private static final String THREAD = "thread-1";
    private static final String RUN = "run-1";

    private static AguiSnapshotAccumulator newAccumulator(AguiThreadSnapshot seed) {
        return new AguiSnapshotAccumulator(THREAD, RUN, seed);
    }

    private static AguiThreadSnapshot materialize(
            AguiSnapshotAccumulator acc, AguiEvent... events) {
        for (AguiEvent event : events) {
            acc.consume(event);
        }
        return acc.materialize();
    }

    @Test
    void textFoldingProducesAssistantMessage() {
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.TextMessageStart(THREAD, RUN, "m1", "assistant"),
                        new AguiEvent.TextMessageContent(THREAD, RUN, "m1", "Hello "),
                        new AguiEvent.TextMessageContent(THREAD, RUN, "m1", "world"),
                        new AguiEvent.TextMessageEnd(THREAD, RUN, "m1"),
                        new AguiEvent.RunFinished(THREAD, RUN));

        assertEquals(1, snapshot.messages().size());
        AguiMessage assistant = snapshot.messages().get(0);
        assertEquals("m1", assistant.getId());
        assertEquals("assistant", assistant.getRole());
        assertEquals("Hello world", assistant.getTextContent());
        assertNull(snapshot.pendingOutcome());
    }

    @Test
    void toolCallWithArgsAndResultAttachesToAssistant() {
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.TextMessageStart(THREAD, RUN, "m1", "assistant"),
                        new AguiEvent.TextMessageEnd(THREAD, RUN, "m1"),
                        new AguiEvent.ToolCallStart(THREAD, RUN, "tc1", "search"),
                        new AguiEvent.ToolCallArgs(THREAD, RUN, "tc1", "{\"q\":\"hi\"}"),
                        new AguiEvent.ToolCallEnd(THREAD, RUN, "tc1"),
                        new AguiEvent.ToolCallResult(
                                THREAD, RUN, "tc1", "result-body", "tool", "r1"),
                        new AguiEvent.RunFinished(THREAD, RUN));

        // assistant message + tool result message
        assertEquals(2, snapshot.messages().size());
        AguiMessage assistant = snapshot.messages().get(0);
        assertEquals(1, assistant.getToolCalls().size());
        AguiToolCall call = assistant.getToolCalls().get(0);
        assertEquals("tc1", call.getId());
        assertEquals("search", call.getFunction().getName());
        assertEquals("{\"q\":\"hi\"}", call.getFunction().getArguments());

        AguiMessage tool = snapshot.messages().get(1);
        assertEquals("tool", tool.getRole());
        assertEquals("tc1", tool.getToolCallId());
        assertEquals("result-body", tool.getTextContent());
    }

    @Test
    void danglingToolCallGetsSyntheticResult() {
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.ToolCallStart(THREAD, RUN, "tc1", "search"),
                        new AguiEvent.ToolCallArgs(THREAD, RUN, "tc1", "{}"),
                        new AguiEvent.RunFinished(THREAD, RUN));

        // One synthesized assistant message (holding the tool call) + one synthetic tool result.
        assertEquals(2, snapshot.messages().size());
        AguiMessage tool = snapshot.messages().get(1);
        assertEquals("tool", tool.getRole());
        assertEquals("tc1", tool.getToolCallId());
        assertEquals("tc1:result", tool.getId());
        assertNull(snapshot.pendingOutcome());
    }

    @Test
    void danglingToolOfOpenInterruptIsLeftOpen() {
        AguiEvent.RunFinishedOutcome interrupt =
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "approve?", "tc1", null, null, null)));
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.ToolCallStart(THREAD, RUN, "tc1", "search"),
                        new AguiEvent.ToolCallArgs(THREAD, RUN, "tc1", "{}"),
                        new AguiEvent.RunFinished(THREAD, RUN, null, interrupt));

        // The open interrupt is retained, and its tool is NOT given a synthetic result.
        assertNotNull(snapshot.pendingOutcome());
        assertEquals(1, snapshot.messages().size());
        AguiMessage assistant = snapshot.messages().get(0);
        assertEquals(1, assistant.getToolCalls().size());
        assertEquals("tc1", assistant.getToolCalls().get(0).getId());
        for (AguiMessage message : snapshot.messages()) {
            assertFalse("tc1:result".equals(message.getId()));
        }
    }

    @Test
    void stateSnapshotThenDeltaFoldsToFinalState() {
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.StateSnapshot(
                                THREAD, RUN, Map.of("count", 1, "name", "alice")),
                        new AguiEvent.StateDelta(
                                THREAD,
                                RUN,
                                List.of(
                                        JsonPatchOperation.replace("/count", 2),
                                        JsonPatchOperation.add("active", true))),
                        new AguiEvent.RunFinished(THREAD, RUN));

        assertEquals(2, snapshot.state().get("count"));
        assertEquals("alice", snapshot.state().get("name"));
        assertEquals(true, snapshot.state().get("active"));
    }

    @Test
    void activitySnapshotAndDeltaAreFolded() {
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.ActivitySnapshot(
                                THREAD, RUN, "m1", "progress", Map.of("step", 1), true),
                        new AguiEvent.ActivityDelta(
                                THREAD,
                                RUN,
                                "m1",
                                "progress",
                                List.of(JsonPatchOperation.replace("/step", 2))),
                        new AguiEvent.RunFinished(THREAD, RUN));

        assertEquals(1, snapshot.activities().size());
        AguiThreadSnapshot.ActivityFrame frame = snapshot.activities().get(0);
        assertEquals("m1", frame.messageId());
        assertEquals("progress", frame.activityType());
        assertEquals(2, frame.content().get("step"));
    }

    @Test
    void trailingInterruptIsKept() {
        AguiEvent.RunFinishedOutcome interrupt =
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "msg", "tc1", null, null, null)));
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.RunFinished(THREAD, RUN, null, interrupt));

        assertTrue(snapshot.pendingOutcome() instanceof AguiEvent.RunFinishedInterruptOutcome);
    }

    @Test
    void danglingToolsOfMultiInterruptOutcomeAreAllLeftOpen() {
        AguiEvent.RunFinishedOutcome interrupt =
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "msg", "tc1", null, null, null),
                                new AguiEvent.Interrupt(
                                        "i2", "tool_call", "msg", "tc2", null, null, null)));
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.ToolCallStart(THREAD, RUN, "tc1", "search"),
                        new AguiEvent.ToolCallArgs(THREAD, RUN, "tc1", "{}"),
                        new AguiEvent.ToolCallStart(THREAD, RUN, "tc2", "write"),
                        new AguiEvent.ToolCallArgs(THREAD, RUN, "tc2", "{}"),
                        new AguiEvent.RunFinished(THREAD, RUN, null, interrupt));

        assertNotNull(snapshot.pendingOutcome());
        // No synthetic results for either open-interrupt tool — both genuinely await the user.
        for (AguiMessage message : snapshot.messages()) {
            assertFalse("tc1:result".equals(message.getId()));
            assertFalse("tc2:result".equals(message.getId()));
        }
    }

    @Test
    void successRunClearsPriorInterruptInSameAccumulator() {
        AguiEvent.RunFinishedOutcome interrupt =
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "msg", "tc1", null, null, null)));
        AguiSnapshotAccumulator acc = newAccumulator(null);
        acc.consume(new AguiEvent.RunFinished(THREAD, RUN, null, interrupt));
        assertNotNull(acc.materialize().pendingOutcome());

        acc.consume(new AguiEvent.RunFinished(THREAD, RUN));
        assertNull(acc.materialize().pendingOutcome());
    }

    @Test
    void seedsFromExistingSnapshotButDoesNotCarryInterrupt() {
        AguiEvent.RunFinishedOutcome interrupt =
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "msg", "tc1", null, null, null)));
        AguiThreadSnapshot seed =
                new AguiThreadSnapshot(
                        THREAD,
                        List.of(AguiMessage.userMessage("u1", "hi")),
                        Map.of("k", "v"),
                        List.of(
                                new AguiThreadSnapshot.ActivityFrame(
                                        "m0", "progress", Map.of("step", 1))),
                        interrupt,
                        "run-0",
                        1L);

        AguiThreadSnapshot snapshot =
                materialize(newAccumulator(seed), new AguiEvent.RunFinished(THREAD, RUN));

        // Prior messages / state / activities survive, but the prior interrupt does not.
        assertEquals("hi", snapshot.messages().get(0).getTextContent());
        assertEquals("v", snapshot.state().get("k"));
        assertEquals(1, snapshot.activities().size());
        assertNull(snapshot.pendingOutcome());
    }

    @Test
    void runStartedCapturesInputMessagesDeDuped() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId(THREAD)
                        .runId(RUN)
                        .messages(List.of(AguiMessage.userMessage("u1", "hi")))
                        .build();
        AguiThreadSnapshot snapshot =
                materialize(
                        newAccumulator(null),
                        new AguiEvent.RunStarted(THREAD, RUN, null, input),
                        new AguiEvent.TextMessageStart(THREAD, RUN, "m1", "assistant"),
                        new AguiEvent.TextMessageEnd(THREAD, RUN, "m1"),
                        new AguiEvent.RunFinished(THREAD, RUN));

        // u1 from input + m1 assistant
        assertEquals(2, snapshot.messages().size());
        assertEquals("u1", snapshot.messages().get(0).getId());
    }

    @Test
    void runErrorClearsPendingOutcome() {
        AguiSnapshotAccumulator acc = newAccumulator(null);
        AguiEvent.RunFinishedOutcome interrupt =
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "msg", "tc1", null, null, null)));
        acc.consume(new AguiEvent.RunFinished(THREAD, RUN, null, interrupt));
        assertNotNull(acc.materialize().pendingOutcome());

        acc.consume(new AguiEvent.RunError(THREAD, RUN, "boom", "ERR"));
        assertNull(acc.materialize().pendingOutcome());
    }
}
