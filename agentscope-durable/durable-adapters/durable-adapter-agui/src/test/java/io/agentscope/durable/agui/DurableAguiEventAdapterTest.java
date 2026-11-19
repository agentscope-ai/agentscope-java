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
package io.agentscope.durable.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionStreamEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurableAguiEventAdapterTest {
    private final SessionKey session = new SessionKey("tenant", "session");
    private final DurableAguiEventAdapter adapter = new DurableAguiEventAdapter();

    @Test
    void liveTextAndCommittedMessageFormOneAguiMessageLifecycle() {
        var start = adapter.map(started("message-1"));
        var delta = adapter.map(delta("message-1", "hello"));
        var completed =
                adapter.map(
                        new SessionStreamEvent.Committed(completed("message-1", "hello world")));

        assertInstanceOf(AguiEvent.TextMessageStart.class, start.get(0));
        assertEquals("hello", ((AguiEvent.TextMessageContent) delta.get(0)).delta());
        assertEquals(2, completed.size());
        assertEquals(" world", ((AguiEvent.TextMessageContent) completed.get(0)).delta());
        assertInstanceOf(AguiEvent.TextMessageEnd.class, completed.get(1));
    }

    @Test
    void committedMessageWithoutLivePreviewStillFormsACompleteLifecycle() {
        var events = adapter.committed(completed("message-2", "complete"));
        assertInstanceOf(AguiEvent.TextMessageStart.class, events.get(0));
        assertEquals("complete", ((AguiEvent.TextMessageContent) events.get(1)).delta());
        assertInstanceOf(AguiEvent.TextMessageEnd.class, events.get(2));
    }

    @Test
    void committedTurnWaitingFinishesTheRunWithEveryPendingInteraction() {
        String confirmId = DurableJson.hash(List.of("turn", "call-1"));
        String toolId = DurableJson.hash(List.of("turn", "call-2"));
        var events =
                adapter.map(
                        waiting(
                                "run",
                                List.of(
                                        interaction(confirmId, "call-1", "confirmation", false),
                                        interaction(
                                                toolId, "call-2", "external_execution", true))));

        assertEquals(1, events.size());
        var finished = assertInstanceOf(AguiEvent.RunFinished.class, events.get(0));
        assertEquals("run", finished.runId());
        var outcome =
                assertInstanceOf(AguiEvent.RunFinishedInterruptOutcome.class, finished.outcome());
        assertEquals(2, outcome.interrupts().size());

        var confirmation = outcome.interrupts().get(0);
        assertEquals(confirmId, confirmation.id());
        assertEquals(AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON, confirmation.reason());
        assertEquals("call-1", confirmation.toolCallId());
        assertEquals(
                "Tool 'send_email' requires user confirmation before execution",
                confirmation.message());
        assertNotNull(confirmation.responseSchema());
        assertEquals(
                "send_email",
                confirmation.metadata().get(AguiInterruptConstants.METADATA_TOOL_NAME));
        assertEquals(
                Map.of("to", "person@example.com"),
                confirmation.metadata().get(AguiInterruptConstants.METADATA_TOOL_INPUT));
        assertEquals(false, confirmation.metadata().get("automatic"));

        var external = outcome.interrupts().get(1);
        assertEquals(toolId, external.id());
        assertEquals("call-2", external.toolCallId());
        assertEquals("Tool 'search' requires external execution", external.message());
        assertEquals(true, external.metadata().get("automatic"));
    }

    @Test
    void turnWaitingClosesOpenStepsMessagesAndToolCallsBeforeTheRunFinishes() {
        adapter.map(started("message-1"));
        adapter.committed(committed("step.started", "run", "step-1", Map.of()));
        adapter.map(toolDelta("call-9", "search", "{\"q\":"));

        var events =
                adapter.map(
                        waiting(
                                "run",
                                List.of(
                                        interaction(
                                                "interrupt-1", "call-1", "confirmation", false))));

        assertEquals(4, events.size());
        assertEquals("step-1", ((AguiEvent.StepFinished) events.get(0)).stepName());
        assertEquals("message-1", ((AguiEvent.TextMessageEnd) events.get(1)).messageId());
        assertEquals("call-9", ((AguiEvent.ToolCallEnd) events.get(2)).toolCallId());
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(3));
    }

    @Test
    void turnWaitingWithoutPendingInteractionsDoesNotFinishTheRun() {
        assertTrue(adapter.map(waiting("run", List.of())).isEmpty());
        assertTrue(adapter.map(waiting(null, List.of(Map.of("id", "interrupt-1")))).isEmpty());
    }

    @Test
    void repeatedTurnWaitingForTheSameRunFinishesItOnce() {
        var waiting =
                waiting(
                        "run",
                        List.of(interaction("interrupt-1", "call-1", "confirmation", false)));

        assertEquals(1, adapter.map(waiting).size());
        assertTrue(adapter.map(waiting).isEmpty());
    }

    @Test
    void suspendedRunEndWaitsForTheTurnWaitingEvent() {
        var ended =
                adapter.committed(
                        committed(
                                "run.ended",
                                "run",
                                null,
                                Map.of("status", "STOPPED", "reason", "suspended")));
        assertTrue(ended.isEmpty());

        var events =
                adapter.map(
                        waiting(
                                "run",
                                List.of(
                                        interaction(
                                                "interrupt-1", "call-1", "confirmation", false))));
        assertEquals(1, events.size());
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(0));
    }

    @Test
    void failureAfterAnInterruptedRunIsReportedAsALateRunError() {
        adapter.map(
                waiting(
                        "run",
                        List.of(interaction("interrupt-1", "call-1", "confirmation", false))));

        var events =
                adapter.committed(
                        committed(
                                "run.ended",
                                "run",
                                null,
                                Map.of("status", "FAILED", "reason", "boom")));

        assertEquals(1, events.size());
        assertInstanceOf(AguiEvent.RunError.class, events.get(0));
    }

    @Test
    void turnWaitingAfterAFailedRunDoesNotFinishItAgain() {
        adapter.committed(
                committed("run.ended", "run", null, Map.of("status", "FAILED", "reason", "boom")));

        assertTrue(
                adapter.map(
                                waiting(
                                        "run",
                                        List.of(
                                                interaction(
                                                        "interrupt-1",
                                                        "call-1",
                                                        "confirmation",
                                                        false))))
                        .isEmpty());
    }

    @Test
    void failedRunClosesOpenStepsBeforeTheRunError() {
        adapter.committed(committed("step.started", "run", "step-1", Map.of()));

        var events =
                adapter.committed(
                        committed(
                                "run.ended",
                                "run",
                                null,
                                Map.of("status", "FAILED", "reason", "boom")));

        assertEquals(2, events.size());
        assertEquals("step-1", ((AguiEvent.StepFinished) events.get(0)).stepName());
        assertInstanceOf(AguiEvent.RunError.class, events.get(1));
    }

    @Test
    void permissionAskingRunEndWaitsForTheTurnWaitingEvent() {
        var ended =
                adapter.committed(
                        committed(
                                "run.ended",
                                "run",
                                null,
                                Map.of("status", "STOPPED", "reason", "PERMISSION_ASKING")));
        assertTrue(ended.isEmpty());

        var events =
                adapter.map(
                        waiting(
                                "run",
                                List.of(
                                        interaction(
                                                "interrupt-1", "call-1", "confirmation", false))));

        assertEquals(1, events.size());
        var finished = assertInstanceOf(AguiEvent.RunFinished.class, events.get(0));
        var outcome =
                assertInstanceOf(AguiEvent.RunFinishedInterruptOutcome.class, finished.outcome());
        assertEquals(1, outcome.interrupts().size());
        assertEquals("interrupt-1", outcome.interrupts().get(0).id());
    }

    @Test
    void runStoppedWithoutWaitingFinishesAsCancelled() {
        var events =
                adapter.committed(
                        committed(
                                "run.ended",
                                "run",
                                null,
                                Map.of("status", "STOPPED", "reason", "host_released")));

        assertEquals(1, events.size());
        var finished = assertInstanceOf(AguiEvent.RunFinished.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinishedCancelledOutcome.class, finished.outcome());
    }

    @Test
    void completedRunFinishesWithTheSuccessOutcome() {
        var events =
                adapter.committed(
                        committed("run.ended", "run", null, Map.of("status", "COMPLETED")));

        var finished = assertInstanceOf(AguiEvent.RunFinished.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinishedSuccessOutcome.class, finished.outcome());
    }

    @Test
    void toolResultCarriesItsOwnMessageId() {
        var events =
                adapter.committed(
                        committed(
                                "tool.call.updated",
                                "run",
                                "step-1",
                                Map.of(
                                        "callId",
                                        "call-1",
                                        "status",
                                        "SUCCEEDED",
                                        "content",
                                        "done")));

        var result = assertInstanceOf(AguiEvent.ToolCallResult.class, events.get(0));
        assertEquals("call-1:result", result.messageId());
    }

    private SessionStreamEvent waiting(String runId, List<Map<String, Object>> interactions) {
        return new SessionStreamEvent.Committed(
                committed(
                        "turn.waiting",
                        runId,
                        null,
                        Map.of("revision", 1, "attempt", 1, "interactions", interactions)));
    }

    private Map<String, Object> interaction(
            String id, String requestId, String kind, boolean automatic) {
        return Map.of(
                "id",
                id,
                "requestId",
                requestId,
                "turnId",
                "turn",
                "key",
                "interaction:" + requestId,
                "kind",
                kind,
                "status",
                "pending",
                "call",
                Map.of(
                        "name",
                        "confirmation".equals(kind) ? "send_email" : "search",
                        "arguments",
                        Map.of("to", "person@example.com")),
                "automatic",
                automatic);
    }

    private PublicEvent committed(
            String type, String runId, String stepId, Map<String, Object> data) {
        return new PublicEvent(
                "1",
                "event",
                session,
                "main",
                "turn",
                runId,
                stepId,
                "2",
                "cursor",
                type,
                DurableJson.value(data));
    }

    private SessionStreamEvent.PreviewStarted started(String id) {
        return new SessionStreamEvent.PreviewStarted(
                id,
                EventDeltaType.MESSAGE,
                "main",
                "turn",
                "run",
                "step",
                DurableJson.value(Map.of("messageId", id)));
    }

    private SessionStreamEvent.EventDelta delta(String id, String text) {
        return new SessionStreamEvent.EventDelta(
                id,
                EventDeltaType.MESSAGE,
                "text-1",
                "main",
                "turn",
                "run",
                "step",
                DurableJson.value(Map.of("messageId", id, "text", text)));
    }

    private SessionStreamEvent.EventDelta toolDelta(String id, String name, String text) {
        return new SessionStreamEvent.EventDelta(
                id,
                EventDeltaType.TOOL_ARGUMENTS,
                id,
                "main",
                "turn",
                "run",
                "step",
                DurableJson.value(Map.of("toolCallId", id, "name", name, "text", text)));
    }

    private PublicEvent completed(String id, String text) {
        return new PublicEvent(
                "1",
                "event",
                session,
                "main",
                "turn",
                "run",
                "step",
                "1",
                "cursor",
                "message.completed",
                DurableJson.value(
                        Map.of(
                                "messageId",
                                id,
                                "attempt",
                                1,
                                "revision",
                                0,
                                "text",
                                text,
                                "message",
                                Map.of(
                                        "schemaVersion",
                                        "1",
                                        "messageId",
                                        id,
                                        "role",
                                        "assistant",
                                        "content",
                                        List.of(Map.of("type", "text", "text", text))))));
    }
}
