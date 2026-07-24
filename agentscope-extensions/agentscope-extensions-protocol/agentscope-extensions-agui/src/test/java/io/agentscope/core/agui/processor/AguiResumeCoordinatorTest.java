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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for AguiResumeCoordinator. */
class AguiResumeCoordinatorTest {

    @Test
    void validateRejectsNewInputWhenThreadHasOpenInterrupts() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        track(coordinator, "run-1", interruptedFinished("run-1", interrupt("interrupt-1")), false);

        AguiResumeCoordinator.ResumeContractResult result =
                coordinator.validate(
                        RunAgentInput.builder()
                                .threadId("thread-1")
                                .runId("run-2")
                                .messages(List.of(AguiMessage.userMessage("msg-1", "hello")))
                                .build());

        assertTrue(result.isError());
    }

    @Test
    void validateRequiresResumeToCoverAllOpenInterrupts() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        track(
                coordinator,
                "run-1",
                interruptedFinished("run-1", interrupt("interrupt-1"), interrupt("interrupt-2")),
                false);

        AguiResumeCoordinator.ResumeContractResult result =
                coordinator.validate(
                        RunAgentInput.builder()
                                .threadId("thread-1")
                                .runId("run-2")
                                .resume(
                                        List.of(
                                                new AguiResume(
                                                        "interrupt-1",
                                                        AguiResume.STATUS_RESOLVED,
                                                        Map.of("approved", true))))
                                .build());

        assertTrue(result.isError());
    }

    @Test
    void addResumeToolCallIdsAddsKnownToolMappingsToRuntimeContext() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        track(coordinator, "run-1", interruptedFinished("run-1", interrupt("interrupt-1")), false);

        RuntimeContext context =
                coordinator.addResumeToolCallIds(
                        RunAgentInput.builder()
                                .threadId("thread-1")
                                .runId("run-2")
                                .resume(
                                        List.of(
                                                new AguiResume(
                                                        "interrupt-1",
                                                        AguiResume.STATUS_RESOLVED,
                                                        Map.of("approved", true))))
                                .build(),
                        RuntimeContext.builder().put("tenant", "tenant-a").build());

        assertEquals("tenant-a", context.get("tenant"));
        assertEquals(
                Map.of("interrupt-1", "tool-call-1"),
                context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_TOOL_CALL_IDS_KEY));
    }

    @Test
    void trackDoesNotClearPendingInterruptsAfterRunError() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        track(coordinator, "run-1", interruptedFinished("run-1", interrupt("interrupt-1")), false);
        track(coordinator, "run-2", new AguiEvent.RunFinished("thread-1", "run-2"), true);

        AguiResumeCoordinator.ResumeContractResult result =
                coordinator.validate(
                        RunAgentInput.builder()
                                .threadId("thread-1")
                                .runId("run-3")
                                .resume(
                                        List.of(
                                                new AguiResume(
                                                        "interrupt-1",
                                                        AguiResume.STATUS_RESOLVED,
                                                        Map.of("approved", true))))
                                .build());

        assertFalse(result.isError());
    }

    @Test
    void beginRunRejectsConcurrentRunOnSameThread() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();

        AguiResumeCoordinator.ResumeContractResult first = coordinator.beginRun(input("run-1"));
        AguiResumeCoordinator.ResumeContractResult second = coordinator.beginRun(input("run-2"));

        assertFalse(first.isError());
        assertTrue(second.isError());
    }

    @Test
    void beginRunRejectsDuplicateActiveRunOnSameThread() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();

        coordinator.beginRun(input("run-1"));
        AguiResumeCoordinator.ResumeContractResult duplicate = coordinator.beginRun(input("run-1"));

        assertTrue(duplicate.isError());
    }

    @Test
    void finishRunDoesNotReleaseDifferentActiveRun() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        coordinator.beginRun(input("run-1"));

        coordinator.finishRun("thread-1", "run-2");
        AguiResumeCoordinator.ResumeContractResult result = coordinator.beginRun(input("run-3"));

        assertTrue(result.isError());
    }

    @Test
    void trackIgnoresEventsFromInactiveRun() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        coordinator.beginRun(input("run-2"));

        coordinator.trackPendingInterrupts(
                "thread-1", "run-1", interruptedFinished("run-1", interrupt("interrupt-1")), false);

        AguiResumeCoordinator.ResumeContractResult result =
                coordinator.validate(
                        RunAgentInput.builder()
                                .threadId("thread-1")
                                .runId("run-3")
                                .messages(List.of(AguiMessage.userMessage("msg-1", "hello")))
                                .build());

        assertFalse(result.isError());
    }

    @Test
    void contractErrorEventsUseAguiResumeErrorLifecycleCodeAndTimestamp() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();

        List<AguiEvent> events =
                coordinator.contractErrorEvents(input("run-1"), "resume contract failed");

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.RUN_ERROR,
                        AguiEventType.RUN_FINISHED),
                events.stream().map(AguiEvent::getType).toList());
        AguiEvent.RunError error = (AguiEvent.RunError) events.get(1);
        assertEquals(AguiResumeCoordinator.CONTRACT_ERROR_CODE, error.code());
        assertEquals("resume contract failed", error.message());
        assertNotNull(error.timestamp());
    }

    private static void track(
            AguiResumeCoordinator coordinator,
            String runId,
            AguiEvent.RunFinished event,
            boolean runErrorSeen) {
        coordinator.beginRun(input(runId));
        coordinator.trackPendingInterrupts("thread-1", runId, event, runErrorSeen);
        coordinator.finishRun("thread-1", runId);
    }

    private static AguiEvent.Interrupt interrupt(String interruptId) {
        return interrupt(interruptId, "tool-call-1");
    }

    private static AguiEvent.RunFinished interruptedFinished(
            String runId, AguiEvent.Interrupt... interrupts) {
        return new AguiEvent.RunFinished(
                "thread-1",
                runId,
                null,
                new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupts)));
    }

    private static AguiEvent.Interrupt interrupt(String interruptId, String toolCallId) {
        return new AguiEvent.Interrupt(
                interruptId, "tool_call", "approve", toolCallId, null, null, null);
    }

    private static RunAgentInput input(String runId) {
        return RunAgentInput.builder().threadId("thread-1").runId(runId).build();
    }
}
