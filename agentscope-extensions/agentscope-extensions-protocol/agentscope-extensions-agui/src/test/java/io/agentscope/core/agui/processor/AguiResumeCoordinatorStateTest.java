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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class AguiResumeCoordinatorStateTest {

    @Test
    void shouldReportIdleWhenNothingHappened() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();

        assertFalse(coordinator.hasActiveRun("t1"));
        assertFalse(coordinator.hasUnresolvedInterrupts("t1"));
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
