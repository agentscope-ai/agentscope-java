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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void addResumeInterruptsAddsKnownInterruptsToRuntimeContext() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        AguiEvent.Interrupt interrupt = interrupt("interrupt-1");
        track(coordinator, "run-1", interruptedFinished("run-1", interrupt), false);

        RuntimeContext context =
                coordinator.addResumeInterrupts(
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
                Map.of("interrupt-1", interrupt),
                context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY));
    }

    @Test
    void addResumeInterruptsAddsConfirmationInterruptToRuntimeContext() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        AguiEvent.Interrupt confirmation =
                new AguiEvent.Interrupt(
                        "interrupt-1",
                        "tool_call",
                        "confirm echo",
                        "tool-call-1",
                        null,
                        null,
                        Map.of("toolName", "echo"));
        track(coordinator, "run-1", interruptedFinished("run-1", confirmation), false);

        RuntimeContext context =
                coordinator.addResumeInterrupts(
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
                        null);

        Object interrupts = context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY);
        assertEquals(Map.of("interrupt-1", confirmation), interrupts);
    }

    @Test
    void addResumeInterruptsIgnoresInterruptsWithoutToolCallId() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        AguiEvent.Interrupt custom =
                new AguiEvent.Interrupt(
                        "interrupt-1", "custom_reason", "no tool", null, null, null, null);
        track(coordinator, "run-1", interruptedFinished("run-1", custom), false);

        RuntimeContext context =
                coordinator.addResumeInterrupts(
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
        assertNull(context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY));
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
    void pendingInterruptCanBeResumedByAnotherCoordinator() {
        AguiResumeStateStore sharedStore = new InMemoryAguiResumeStateStore();
        AguiResumeCoordinator firstCoordinator = new AguiResumeCoordinator(sharedStore);
        track(
                firstCoordinator,
                "run-1",
                interruptedFinished("run-1", interrupt("interrupt-1")),
                false);
        AguiResumeCoordinator secondCoordinator = new AguiResumeCoordinator(sharedStore);

        AguiResumeCoordinator.ResumeContractResult result =
                secondCoordinator.validate(
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

        assertFalse(result.isError());
    }

    @Test
    void defaultStoresRemainCoordinatorLocal() {
        AguiResumeCoordinator firstCoordinator = new AguiResumeCoordinator();
        AguiResumeCoordinator secondCoordinator = new AguiResumeCoordinator();
        track(
                firstCoordinator,
                "run-1",
                interruptedFinished("run-1", interrupt("interrupt-1")),
                false);

        AguiResumeCoordinator.ResumeContractResult result =
                secondCoordinator.validate(resumeInput("run-2", "interrupt-1"));

        assertTrue(result.isError());
    }

    @Test
    void sharedStoreSerializesRunsAcrossCoordinatorsAndAllowsClaimAfterRelease() {
        AguiResumeStateStore sharedStore = new InMemoryAguiResumeStateStore();
        AguiResumeCoordinator firstCoordinator = new AguiResumeCoordinator(sharedStore);
        AguiResumeCoordinator secondCoordinator = new AguiResumeCoordinator(sharedStore);

        assertFalse(firstCoordinator.beginRun(input("run-1")).isError());
        assertTrue(secondCoordinator.beginRun(input("run-2")).isError());

        firstCoordinator.finishRun("thread-1", "run-1");

        assertFalse(secondCoordinator.beginRun(input("run-2")).isError());
    }

    @Test
    void staleResumeValidationCannotClaimAfterAnotherRunConsumesInterrupt() throws Exception {
        BlockingClaimStore sharedStore = new BlockingClaimStore("run-a");
        assertTrue(sharedStore.claimRun("thread-1", "seed-run").claimed());
        assertTrue(
                sharedStore.replacePendingInterrupts(
                        "thread-1", "seed-run", Map.of("interrupt-1", interrupt("interrupt-1"))));
        sharedStore.releaseRun("thread-1", "seed-run");
        AguiResumeCoordinator firstCoordinator = new AguiResumeCoordinator(sharedStore);
        AguiResumeCoordinator secondCoordinator = new AguiResumeCoordinator(sharedStore);

        CompletableFuture<AguiResumeCoordinator.ResumeContractResult> firstResult =
                CompletableFuture.supplyAsync(
                        () -> firstCoordinator.beginRun(resumeInput("run-a", "interrupt-1")));
        sharedStore.awaitBlockedClaim();
        try {
            assertFalse(secondCoordinator.beginRun(resumeInput("run-b", "interrupt-1")).isError());
            secondCoordinator.trackPendingInterrupts(
                    "thread-1", "run-b", new AguiEvent.RunFinished("thread-1", "run-b"), false);
            secondCoordinator.finishRun("thread-1", "run-b");
        } finally {
            sharedStore.allowBlockedClaim();
        }

        assertTrue(firstResult.get(5, TimeUnit.SECONDS).isError());
    }

    @Test
    void nonResumeRunCannotStartAfterAnotherRunPublishesInterrupt() throws Exception {
        BlockingClaimStore sharedStore = new BlockingClaimStore("run-b");
        AguiResumeCoordinator firstCoordinator = new AguiResumeCoordinator(sharedStore);
        AguiResumeCoordinator secondCoordinator = new AguiResumeCoordinator(sharedStore);

        CompletableFuture<AguiResumeCoordinator.ResumeContractResult> secondResult =
                CompletableFuture.supplyAsync(() -> secondCoordinator.beginRun(input("run-b")));
        sharedStore.awaitBlockedClaim();
        try {
            assertFalse(firstCoordinator.beginRun(input("run-a")).isError());
            firstCoordinator.trackPendingInterrupts(
                    "thread-1",
                    "run-a",
                    interruptedFinished("run-a", interrupt("interrupt-1")),
                    false);
            firstCoordinator.finishRun("thread-1", "run-a");
        } finally {
            sharedStore.allowBlockedClaim();
        }

        assertTrue(secondResult.get(5, TimeUnit.SECONDS).isError());
    }

    @Test
    void staleCoordinatorCannotReleaseAnotherCoordinatorsRun() {
        AguiResumeStateStore sharedStore = new InMemoryAguiResumeStateStore();
        AguiResumeCoordinator firstCoordinator = new AguiResumeCoordinator(sharedStore);
        AguiResumeCoordinator secondCoordinator = new AguiResumeCoordinator(sharedStore);

        firstCoordinator.beginRun(input("run-1"));
        firstCoordinator.finishRun("thread-1", "run-1");
        secondCoordinator.beginRun(input("run-2"));

        firstCoordinator.finishRun("thread-1", "run-1");

        assertTrue(firstCoordinator.beginRun(input("run-3")).isError());
    }

    @Test
    void storeFailureIsNotTreatedAsMissingResumeState() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator(new FailingStore());

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> coordinator.validate(resumeInput("run-2", "interrupt-1")));

        assertEquals("shared store unavailable", error.getMessage());
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
    void beginRunReleasesOwnershipWhenValidationFails() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        track(coordinator, "run-1", interruptedFinished("run-1", interrupt("interrupt-1")), false);

        assertTrue(coordinator.beginRun(input("run-2")).isError());

        assertFalse(coordinator.beginRun(resumeInput("run-3", "interrupt-1")).isError());
    }

    @Test
    void beginRunReleasesOwnershipWhenValidationReadThrows() {
        FailingReadStore store = new FailingReadStore();
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator(store);

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> coordinator.beginRun(input("run-1")));
        store.allowReads();

        assertEquals("pending read failed", error.getMessage());
        assertFalse(coordinator.beginRun(input("run-2")).isError());
    }

    @Test
    void beginRunPreservesValidationFailureWhenCleanupAlsoFails() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator(new CleanupFailingStore());

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> coordinator.beginRun(input("run-1")));

        assertEquals("pending read failed", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("claim cleanup failed", error.getSuppressed()[0].getMessage());
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
    void staleRunFinishedCannotOverwriteCurrentRunsPendingInterrupts() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();
        coordinator.beginRun(input("run-2"));
        coordinator.trackPendingInterrupts(
                "thread-1", "run-2", interruptedFinished("run-2", interrupt("interrupt-2")), false);

        coordinator.trackPendingInterrupts(
                "thread-1", "run-1", interruptedFinished("run-1", interrupt("interrupt-1")), false);
        coordinator.finishRun("thread-1", "run-2");

        AguiResumeCoordinator.ResumeContractResult result =
                coordinator.validate(resumeInput("run-3", "interrupt-2"));

        assertFalse(result.isError());
    }

    @Test
    void contractErrorEventsUseAguiResumeErrorLifecycleCodeAndTimestamp() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();

        List<AguiEvent> events =
                coordinator.contractErrorEvents(input("run-1"), "resume contract failed", false);

        assertEquals(
                List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_ERROR),
                events.stream().map(AguiEvent::getType).toList());
        AguiEvent.RunError error = (AguiEvent.RunError) events.get(1);
        assertEquals(AguiResumeCoordinator.CONTRACT_ERROR_CODE, error.code());
        assertEquals("resume contract failed", error.message());
        assertNotNull(error.timestamp());
    }

    @Test
    void contractErrorEventsEmitRunFinishedWhenEnabled() {
        AguiResumeCoordinator coordinator = new AguiResumeCoordinator();

        List<AguiEvent> events =
                coordinator.contractErrorEvents(input("run-1"), "resume contract failed", true);

        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.RUN_ERROR,
                        AguiEventType.RUN_FINISHED),
                events.stream().map(AguiEvent::getType).toList());
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

    private static RunAgentInput resumeInput(String runId, String interruptId) {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId(runId)
                .resume(
                        List.of(
                                new AguiResume(
                                        interruptId,
                                        AguiResume.STATUS_RESOLVED,
                                        Map.of("approved", true))))
                .build();
    }

    private static final class FailingStore implements AguiResumeStateStore {

        @Override
        public Map<String, AguiEvent.Interrupt> getPendingInterrupts(String threadId) {
            throw failure();
        }

        @Override
        public RunClaim claimRun(String threadId, String runId) {
            throw failure();
        }

        @Override
        public void releaseRun(String threadId, String runId) {
            throw failure();
        }

        @Override
        public boolean replacePendingInterrupts(
                String threadId, String runId, Map<String, AguiEvent.Interrupt> pendingInterrupts) {
            throw failure();
        }

        private IllegalStateException failure() {
            return new IllegalStateException("shared store unavailable");
        }
    }

    private static final class BlockingClaimStore implements AguiResumeStateStore {

        private final AguiResumeStateStore delegate = new InMemoryAguiResumeStateStore();
        private final String blockedRunId;
        private final CountDownLatch claimBlocked = new CountDownLatch(1);
        private final CountDownLatch claimAllowed = new CountDownLatch(1);

        private BlockingClaimStore(String blockedRunId) {
            this.blockedRunId = blockedRunId;
        }

        @Override
        public Map<String, AguiEvent.Interrupt> getPendingInterrupts(String threadId) {
            return delegate.getPendingInterrupts(threadId);
        }

        @Override
        public RunClaim claimRun(String threadId, String runId) {
            if (blockedRunId.equals(runId)) {
                claimBlocked.countDown();
                await(claimAllowed);
            }
            return delegate.claimRun(threadId, runId);
        }

        @Override
        public void releaseRun(String threadId, String runId) {
            delegate.releaseRun(threadId, runId);
        }

        @Override
        public boolean replacePendingInterrupts(
                String threadId, String runId, Map<String, AguiEvent.Interrupt> pendingInterrupts) {
            return delegate.replacePendingInterrupts(threadId, runId, pendingInterrupts);
        }

        private void awaitBlockedClaim() {
            await(claimBlocked);
        }

        private void allowBlockedClaim() {
            claimAllowed.countDown();
        }

        private static void await(CountDownLatch latch) {
            try {
                assertTrue(
                        latch.await(5, TimeUnit.SECONDS), "timed out waiting for test interleave");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for test interleave", error);
            }
        }
    }

    private static final class FailingReadStore implements AguiResumeStateStore {

        private final AguiResumeStateStore delegate = new InMemoryAguiResumeStateStore();
        private boolean failReads = true;

        @Override
        public Map<String, AguiEvent.Interrupt> getPendingInterrupts(String threadId) {
            if (failReads) {
                throw new IllegalStateException("pending read failed");
            }
            return delegate.getPendingInterrupts(threadId);
        }

        @Override
        public RunClaim claimRun(String threadId, String runId) {
            return delegate.claimRun(threadId, runId);
        }

        @Override
        public void releaseRun(String threadId, String runId) {
            delegate.releaseRun(threadId, runId);
        }

        @Override
        public boolean replacePendingInterrupts(
                String threadId, String runId, Map<String, AguiEvent.Interrupt> pendingInterrupts) {
            return delegate.replacePendingInterrupts(threadId, runId, pendingInterrupts);
        }

        private void allowReads() {
            failReads = false;
        }
    }

    private static final class CleanupFailingStore implements AguiResumeStateStore {

        @Override
        public Map<String, AguiEvent.Interrupt> getPendingInterrupts(String threadId) {
            throw new IllegalStateException("pending read failed");
        }

        @Override
        public RunClaim claimRun(String threadId, String runId) {
            return RunClaim.acquired();
        }

        @Override
        public void releaseRun(String threadId, String runId) {
            throw new IllegalStateException("claim cleanup failed");
        }

        @Override
        public boolean replacePendingInterrupts(
                String threadId, String runId, Map<String, AguiEvent.Interrupt> pendingInterrupts) {
            throw new UnsupportedOperationException();
        }
    }
}
