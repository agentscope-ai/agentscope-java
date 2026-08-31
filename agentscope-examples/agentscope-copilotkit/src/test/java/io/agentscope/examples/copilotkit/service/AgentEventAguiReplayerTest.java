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
package io.agentscope.examples.copilotkit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for historical interrupt suppression in {@link AgentEventAguiReplayer}. */
class AgentEventAguiReplayerTest {

    @Test
    void suppressResolvedInterrupts_dropsInterruptFollowedByAnotherRun() {
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));
        AguiEvent.Custom custom = new AguiEvent.Custom("thread-1", "run-1", "custom", null);
        AguiEvent.RunFinished run2 = runFinished("run-2", null);

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(run1, custom, run2));

        assertEquals(3, result.size());
        assertNull(((AguiEvent.RunFinished) result.get(0)).outcome());
        assertSame(custom, result.get(1));
        assertNull(((AguiEvent.RunFinished) result.get(2)).outcome());
    }

    @Test
    void suppressResolvedInterrupts_keepsInterruptOfOnlyRun() {
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));

        List<AguiEvent> result = AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(run1));

        assertNotNull(((AguiEvent.RunFinished) result.get(0)).outcome());
        assertEquals(
                "int-1",
                ((AguiEvent.RunFinishedInterruptOutcome)
                                ((AguiEvent.RunFinished) result.get(0)).outcome())
                        .interrupts()
                        .get(0)
                        .id());
    }

    @Test
    void suppressResolvedInterrupts_keepsInterruptOfLastRunOnly() {
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));
        AguiEvent.RunFinished run2 = runFinished("run-2", interruptOutcome("int-2"));

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(run1, run2));

        assertNull(((AguiEvent.RunFinished) result.get(0)).outcome());
        AguiEvent.RunFinished last = (AguiEvent.RunFinished) result.get(1);
        assertNotNull(last.outcome());
        assertEquals(
                "int-2",
                ((AguiEvent.RunFinishedInterruptOutcome) last.outcome()).interrupts().get(0).id());
    }

    @Test
    void suppressResolvedInterrupts_leavesEventsWithoutRunFinishedUnchanged() {
        AguiEvent.Custom custom = new AguiEvent.Custom("thread-1", "run-1", "custom", null);
        List<AguiEvent> events = List.of(custom);

        List<AguiEvent> result = AgentEventAguiReplayer.suppressResolvedInterrupts(events);

        assertSame(events, result);
    }

    @Test
    void suppressResolvedInterrupts_keepsSuccessOutcomeOfLastRun() {
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));
        AguiEvent.RunFinished run2 =
                runFinished("run-2", new AguiEvent.RunFinishedSuccessOutcome());

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(run1, run2));

        assertNull(((AguiEvent.RunFinished) result.get(0)).outcome());
        assertEquals(
                new AguiEvent.RunFinishedSuccessOutcome(),
                ((AguiEvent.RunFinished) result.get(1)).outcome());
    }

    @Test
    void suppressResolvedInterrupts_dropsInterruptWhenLastRunIsStillRunning() {
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));
        AguiEvent.Raw run2InProgress = new AguiEvent.Raw("thread-1", "run-2", "event", "source");

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(run1, run2InProgress));

        assertEquals(2, result.size());
        assertNull(((AguiEvent.RunFinished) result.get(0)).outcome());
        assertSame(run2InProgress, result.get(1));
    }

    @Test
    void suppressResolvedInterrupts_serializesWithoutInterruptForResolvedHistory() {
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));
        AguiEvent.RunFinished run2 = runFinished("run-2", null);

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(run1, run2));

        String json = JsonUtils.getJsonCodec().toJson(result);
        assertFalse(json.contains("interrupt"));
    }

    @Test
    void suppressResolvedInterrupts_serializesInterruptOfLastRun() {
        AguiEvent.RunFinished run1 = runFinished("run-1", null);
        AguiEvent.RunFinished run2 = runFinished("run-2", interruptOutcome("int-2"));

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(run1, run2));

        String json = JsonUtils.getJsonCodec().toJson(result);
        assertTrue(json.contains("interrupt"));
    }

    @Test
    void suppressResolvedInterrupts_closesDanglingToolOfSuppressedRun() {
        AguiEvent.ToolCallStart start =
                new AguiEvent.ToolCallStart("thread-1", "run-1", "call-1", "deploy_release");
        AguiEvent.ToolCallEnd end = new AguiEvent.ToolCallEnd("thread-1", "run-1", "call-1");
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));
        AguiEvent.RunFinished run2 = runFinished("run-2", null);

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(start, end, run1, run2));

        assertEquals(5, result.size());
        assertSame(start, result.get(0));
        assertSame(end, result.get(1));
        AguiEvent.ToolCallResult closed = (AguiEvent.ToolCallResult) result.get(2);
        assertEquals("call-1", closed.toolCallId());
        assertEquals("run-1", closed.getRunId());
        assertNull(((AguiEvent.RunFinished) result.get(3)).outcome());
        assertSame(run2, result.get(4));
    }

    @Test
    void suppressResolvedInterrupts_keepsDanglingToolOfLastInterruptedRun() {
        AguiEvent.ToolCallStart start =
                new AguiEvent.ToolCallStart("thread-1", "run-1", "call-1", "deploy_release");
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(List.of(start, run1));

        assertEquals(2, result.size());
        assertSame(start, result.get(0));
        assertEquals(
                "int-1",
                ((AguiEvent.RunFinishedInterruptOutcome)
                                ((AguiEvent.RunFinished) result.get(1)).outcome())
                        .interrupts()
                        .get(0)
                        .id());
    }

    @Test
    void suppressResolvedInterrupts_doesNotDuplicateToolResultWhenPresent() {
        AguiEvent.ToolCallStart start =
                new AguiEvent.ToolCallStart("thread-1", "run-1", "call-1", "deploy_release");
        AguiEvent.ToolCallResult realResult =
                new AguiEvent.ToolCallResult("thread-1", "run-1", "call-1", "ok", "tool", "m-1");
        AguiEvent.RunFinished run1 = runFinished("run-1", interruptOutcome("int-1"));
        AguiEvent.RunFinished run2 = runFinished("run-2", null);

        List<AguiEvent> result =
                AgentEventAguiReplayer.suppressResolvedInterrupts(
                        List.of(start, realResult, run1, run2));

        long results = result.stream().filter(AguiEvent.ToolCallResult.class::isInstance).count();
        assertEquals(1, results);
        assertTrue(result.contains(realResult));
    }

    private static AguiEvent.RunFinished runFinished(
            String runId, AguiEvent.RunFinishedOutcome outcome) {
        return new AguiEvent.RunFinished("thread-1", runId, null, outcome);
    }

    private static AguiEvent.RunFinishedInterruptOutcome interruptOutcome(String id) {
        return new AguiEvent.RunFinishedInterruptOutcome(
                List.of(new AguiEvent.Interrupt(id, "permission", null, null, null, null, null)));
    }
}
