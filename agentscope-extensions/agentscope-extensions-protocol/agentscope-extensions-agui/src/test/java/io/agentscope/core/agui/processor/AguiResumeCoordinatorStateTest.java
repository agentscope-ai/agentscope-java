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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class AguiResumeCoordinatorStateTest {

    @Test
    void shouldReportIdleWhenNothingHappened() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();

        assertFalse(coordinator.hasActiveRun("t1"));
        assertFalse(coordinator.hasUnresolvedInterrupts("t1"));
        assertNull(coordinator.lastStartedRunId("t1"));
    }

    @Test
    void shouldNotRecordARejectedRunAsLastStarted() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        RunAgentInput first = RunAgentInput.builder().threadId("t1").runId("r1").build();
        RunAgentInput rival = RunAgentInput.builder().threadId("t1").runId("r2").build();

        coordinator.beginRun(first);
        // A run the coordinator rejects (thread already active) never started, so it must not
        // overwrite the id: the queue would mistake the rejected run for a completed replay.
        assertTrue(coordinator.beginRun(rival).isError());
        assertEquals("r1", coordinator.lastStartedRunId("t1"));
    }

    @Test
    void shouldNotRememberRunIdsWhenRecordingIsDisabled() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator(false);

        assertFalse(
                coordinator
                        .beginRun(RunAgentInput.builder().threadId("t1").runId("r1").build())
                        .isError());
        // The memory backs the queue hand-off guard only; with recording off the queue falls
        // back to its busy-observation and grace-period release rules.
        assertNull(coordinator.lastStartedRunId("t1"));
    }

    @Test
    void shouldRememberTheRunIdTheThreadLastStartedEvenAfterTheRunFinishes() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        RunAgentInput first = RunAgentInput.builder().threadId("t1").runId("r1").build();
        RunAgentInput second = RunAgentInput.builder().threadId("t1").runId("r2").build();

        coordinator.beginRun(first);
        // The identity of the most recent run, not a timestamp: exact equality, no clock.
        assertEquals("r1", coordinator.lastStartedRunId("t1"));

        // A finished run still identifies what the thread last started, so the id must survive
        // the finish; a later run replaces it with its own id.
        coordinator.finishRun("t1", "r1");
        assertEquals("r1", coordinator.lastStartedRunId("t1"));
        coordinator.beginRun(second);
        assertEquals("r2", coordinator.lastStartedRunId("t1"));
        coordinator.finishRun("t1", "r2");
    }

    @Test
    void shouldReportActiveWhileRunInFlight() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        RunAgentInput input = RunAgentInput.builder().threadId("t1").runId("r1").build();

        assertFalse(coordinator.beginRun(input).isError());
        assertTrue(coordinator.hasActiveRun("t1"));

        coordinator.finishRun("t1", "r1");

        assertFalse(coordinator.hasActiveRun("t1"));
    }

    @Test
    void shouldReportPendingInterruptsUntilResolved() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        RunAgentInput input = RunAgentInput.builder().threadId("t1").runId("r1").build();
        coordinator.beginRun(input);

        coordinator.trackPendingInterrupts(
                "t1", "r1", interruptFinished("t1", "r1", "i1", "call-1"), false);
        coordinator.finishRun("t1", "r1");

        assertTrue(coordinator.hasUnresolvedInterrupts("t1"));
        assertFalse(coordinator.hasActiveRun("t1"));
    }

    @Test
    void shouldKeepBlockingAfterAnErroredResumeUntilResolvedAgain() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        coordinator.beginRun(RunAgentInput.builder().threadId("t1").runId("r1").build());
        coordinator.trackPendingInterrupts(
                "t1", "r1", interruptFinished("t1", "r1", "i1", "call-1"), false);
        coordinator.finishRun("t1", "r1");
        assertTrue(coordinator.hasUnresolvedInterrupts("t1"));

        // The client answers the confirmation, but that run fails before finishing cleanly.
        assertFalse(coordinator.beginRun(resumeInput("r2")).isError());
        // A finished event that arrives after an error must not clear the open interrupt.
        coordinator.trackPendingInterrupts("t1", "r2", new AguiEvent.RunFinished("t1", "r2"), true);
        coordinator.finishRun("t1", "r2");

        // The interrupt stays open, so the thread stays blocked for the queue and for a plain run.
        assertFalse(coordinator.hasActiveRun("t1"));
        assertTrue(coordinator.hasUnresolvedInterrupts("t1"));
        assertTrue(
                coordinator
                        .beginRun(RunAgentInput.builder().threadId("t1").runId("r3").build())
                        .isError());

        // Answering the confirmation again is the way out.
        assertFalse(coordinator.beginRun(resumeInput("r4")).isError());
        coordinator.trackPendingInterrupts(
                "t1", "r4", new AguiEvent.RunFinished("t1", "r4"), false);
        coordinator.finishRun("t1", "r4");
        assertFalse(coordinator.hasUnresolvedInterrupts("t1"));
    }

    @Test
    void shouldExposeIdleStateOnProcessor() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(
                                new AgentResolver() {
                                    @Override
                                    public io.agentscope.core.agent.Agent resolveAgent(
                                            String agentId, String threadId) {
                                        return null;
                                    }

                                    @Override
                                    public boolean hasMemory(
                                            io.agentscope.core.agent.RuntimeContext
                                                    runtimeContext) {
                                        return false;
                                    }
                                })
                        .build();

        assertTrue(processor.isThreadIdle("t1"));
    }

    private static RunAgentInput resumeInput(String runId) {
        return RunAgentInput.builder()
                .threadId("t1")
                .runId(runId)
                .resume(List.of(new AguiResume("i1", AguiResume.STATUS_RESOLVED, "ok")))
                .build();
    }

    private static AguiEvent.RunFinished interruptFinished(
            String threadId, String runId, String interruptId, String toolCallId) {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        interruptId,
                        AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON,
                        "confirm?",
                        toolCallId,
                        null,
                        null,
                        null);
        return new AguiEvent.RunFinished(
                threadId,
                runId,
                null,
                new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt)));
    }
}
