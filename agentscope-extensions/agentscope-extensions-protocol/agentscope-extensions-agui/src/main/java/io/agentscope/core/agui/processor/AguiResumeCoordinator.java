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

import static io.agentscope.core.agui.AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates AG-UI interrupt resume contract state for request processing.
 *
 * <p>The coordinator is intentionally scoped to the request processor layer. The adapter and
 * converters stay stateless with respect to open interrupts, while the processor can remember the
 * latest interrupt outcome per AG-UI thread and validate the next request against the official
 * resume contract.
 */
final class AguiResumeCoordinator {

    static final String CONTRACT_ERROR_CODE = "AGUI_INTERRUPT_CONTRACT_ERROR";

    private static final int THREAD_STATE_LOCK_STRIPES = 64;

    private final ConcurrentMap<String, Map<String, AguiEvent.Interrupt>>
            pendingInterruptsByThread = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeRunsByThread = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> lastRunIdByThread = new ConcurrentHashMap<>();
    private final ReentrantLock[] threadStateLocks = new ReentrantLock[THREAD_STATE_LOCK_STRIPES];
    private final boolean recordLastRunId;

    AguiResumeCoordinator() {
        this(true);
    }

    /**
     * Creates a coordinator that optionally remembers the run id each thread last started.
     *
     * <p>That memory backs the message-queue hand-off guard and costs one map entry per thread
     * that ever started a run, so callers that never use the queue can disable the recording.
     *
     * @param recordLastRunId whether a successfully started run is remembered for its thread
     */
    AguiResumeCoordinator(boolean recordLastRunId) {
        this.recordLastRunId = recordLastRunId;
        for (int i = 0; i < threadStateLocks.length; i++) {
            threadStateLocks[i] = new ReentrantLock();
        }
    }

    /**
     * Returns the lock that serialises a run start with the queue claim-time idle check.
     *
     * <p>Both {@link #beginRun(RunAgentInput)} and the claiming side take this lock, so a run
     * cannot become active between the claim's idle check and the moment it removes the head of
     * the queue. Locks are striped by thread id instead of allocated per thread: a collision only
     * makes two unrelated threads wait briefly, because they never share run state.
     *
     * @param threadId The AG-UI thread ID, may be {@code null}
     * @return the lock for that thread's stripe
     */
    ReentrantLock threadStateLock(String threadId) {
        int hash = threadId == null ? 0 : threadId.hashCode();
        return threadStateLocks[Math.floorMod(hash, THREAD_STATE_LOCK_STRIPES)];
    }

    /**
     * Validate whether the input satisfies the currently supported AG-UI resume contract.
     *
     * @param input The run input to validate
     * @return A validation result describing whether processing can continue
     */
    ResumeContractResult validate(RunAgentInput input) {
        Map<String, AguiEvent.Interrupt> pending =
                pendingInterruptsByThread.get(input.getThreadId());
        if (pending == null || pending.isEmpty()) {
            if (!input.hasResume()) {
                return ResumeContractResult.proceed();
            }
            return ResumeContractResult.error(
                    "RunAgentInput.resume does not match any open interrupt");
        }

        if (!input.hasResume()) {
            return ResumeContractResult.error(
                    "Thread has unresolved interrupts; RunAgentInput.resume must address all of"
                            + " them");
        }

        ResumeContractResult statusResult = validateResumeStatuses(input.getResume());
        if (statusResult.isError()) {
            return statusResult;
        }

        Set<String> resumeIds = new LinkedHashSet<>();
        for (AguiResume resume : input.getResume()) {
            if (!resumeIds.add(resume.getInterruptId())) {
                return ResumeContractResult.error(
                        "RunAgentInput.resume contains duplicate interruptId: "
                                + resume.getInterruptId());
            }
        }

        Set<String> pendingIds = pending.keySet();
        if (!resumeIds.equals(pendingIds)) {
            Set<String> missingIds = new LinkedHashSet<>(pendingIds);
            missingIds.removeAll(resumeIds);
            Set<String> unknownIds = new LinkedHashSet<>(resumeIds);
            unknownIds.removeAll(pendingIds);
            return ResumeContractResult.error(
                    "RunAgentInput.resume must cover all open interrupts. missing="
                            + missingIds
                            + ", unknown="
                            + unknownIds);
        }

        return ResumeContractResult.proceed();
    }

    /**
     * Mark a run as active for its thread.
     *
     * <p>AG-UI thread execution is intentionally serialized because unresolved interrupt state and
     * AgentScope session state are both scoped by {@code threadId}. Allowing multiple active runs
     * for one thread would make the final interrupt state depend on completion order.
     *
     * @param input The run input to begin
     * @return A validation result describing whether the run can start
     */
    ResumeContractResult beginRun(RunAgentInput input) {
        ReentrantLock lock = threadStateLock(input.getThreadId());
        lock.lock();
        try {
            return beginRunLocked(input);
        } finally {
            lock.unlock();
        }
    }

    private ResumeContractResult beginRunLocked(RunAgentInput input) {
        ResumeContractResult resumeContract = validate(input);
        if (resumeContract.isError()) {
            return resumeContract;
        }
        String previousRunId =
                activeRunsByThread.putIfAbsent(input.getThreadId(), input.getRunId());
        if (previousRunId == null) {
            if (recordLastRunId) {
                lastRunIdByThread.put(input.getThreadId(), input.getRunId());
            }
            return ResumeContractResult.proceed();
        }
        return ResumeContractResult.error(
                "Thread already has an active run; wait for run "
                        + previousRunId
                        + " to finish before starting another run on the same thread");
    }

    /**
     * Clear the active run marker if it still belongs to the finishing run.
     *
     * @param threadId The AG-UI thread ID
     * @param runId The finishing run ID
     */
    void finishRun(String threadId, String runId) {
        ReentrantLock lock = threadStateLock(threadId);
        lock.lock();
        try {
            activeRunsByThread.remove(threadId, runId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the run id the thread most recently started, by identity.
     *
     * <p>Callers compare this against the run id a delivery handed out: equality indicates that
     * the delivered message itself reached the run endpoint, while any other run (another
     * client's direct request, or a resume answer) does not. The id deliberately survives {@link
     * #finishRun(String, String)}, because a finished run is exactly when the next decision needs
     * that evidence.
     *
     * @param threadId The AG-UI thread ID
     * @return the run id of the most recently started run, or {@code null} when the thread never
     *     started a run here or when recording is disabled - the queue then falls back to its
     *     busy-observation and grace-period release rules
     */
    String lastStartedRunId(String threadId) {
        return lastRunIdByThread.get(threadId);
    }

    /**
     * Returns whether the thread currently has an active run.
     *
     * @param threadId The AG-UI thread ID
     * @return true when a run is in flight for this thread
     */
    boolean hasActiveRun(String threadId) {
        return activeRunsByThread.containsKey(threadId);
    }

    /**
     * Returns whether the thread has interrupts that no resume has resolved yet.
     *
     * @param threadId The AG-UI thread ID
     * @return true when the thread is waiting for a client resume
     */
    boolean hasUnresolvedInterrupts(String threadId) {
        Map<String, AguiEvent.Interrupt> pending = pendingInterruptsByThread.get(threadId);
        return pending != null && !pending.isEmpty();
    }

    /**
     * Add known originating interrupts to the runtime context for resume conversion.
     *
     * @param input The run input containing resume entries
     * @param runtimeContext The caller-provided runtime context, if any
     * @return A runtime context with AG-UI resume interrupts when available
     */
    RuntimeContext addResumeInterrupts(RunAgentInput input, RuntimeContext runtimeContext) {
        if (!input.hasResume()) {
            return runtimeContext;
        }
        Map<String, AguiEvent.Interrupt> pending =
                pendingInterruptsByThread.get(input.getThreadId());
        if (pending == null || pending.isEmpty()) {
            return runtimeContext;
        }
        Map<String, AguiEvent.Interrupt> resumeInterrupts = new LinkedHashMap<>();
        for (AguiResume resume : input.getResume()) {
            AguiEvent.Interrupt interrupt = pending.get(resume.getInterruptId());
            if (interrupt == null) {
                continue;
            }
            if (shouldPassResumeInterrupt(interrupt)) {
                resumeInterrupts.put(resume.getInterruptId(), interrupt);
            }
        }
        if (resumeInterrupts.isEmpty()) {
            return runtimeContext;
        }
        return RuntimeContext.builder(runtimeContext)
                .put(
                        AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY,
                        Map.copyOf(resumeInterrupts))
                .build();
    }

    /**
     * Track interrupt outcomes emitted by the adapter for subsequent resume requests.
     *
     * @param threadId The AG-UI thread ID
     * @param runId The AG-UI run ID that emitted the event
     * @param event The outgoing AG-UI event
     * @param runErrorSeen Whether this stream has already emitted {@code RUN_ERROR}
     */
    void trackPendingInterrupts(
            String threadId, String runId, AguiEvent event, boolean runErrorSeen) {
        if (!(event instanceof AguiEvent.RunFinished runFinished)) {
            return;
        }
        ReentrantLock lock = threadStateLock(threadId);
        lock.lock();
        try {
            if (!runId.equals(activeRunsByThread.get(threadId))) {
                return;
            }
            if (runFinished.outcome()
                            instanceof AguiEvent.RunFinishedInterruptOutcome interruptOutcome
                    && !interruptOutcome.interrupts().isEmpty()) {
                Map<String, AguiEvent.Interrupt> interrupts = new ConcurrentHashMap<>();
                for (AguiEvent.Interrupt interrupt : interruptOutcome.interrupts()) {
                    interrupts.put(interrupt.id(), interrupt);
                }
                pendingInterruptsByThread.put(threadId, Map.copyOf(interrupts));
            } else if (!runErrorSeen) {
                pendingInterruptsByThread.remove(threadId);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Build the AG-UI error lifecycle used when the resume contract is violated.
     *
     * @param input The invalid run input
     * @param message The validation error message
     * @param emitRunFinishedAfterError true to append {@code RUN_FINISHED} after {@code RUN_ERROR}
     *     for legacy clients
     * @return The protocol error lifecycle events
     */
    List<AguiEvent> contractErrorEvents(
            RunAgentInput input, String message, boolean emitRunFinishedAfterError) {
        List<AguiEvent> events = new ArrayList<>();
        events.add(new AguiEvent.RunStarted(input.getThreadId(), input.getRunId(), null, input));
        events.add(
                new AguiEvent.RunError(
                        input.getThreadId(),
                        input.getRunId(),
                        message,
                        CONTRACT_ERROR_CODE,
                        System.currentTimeMillis(),
                        null));
        if (emitRunFinishedAfterError) {
            events.add(new AguiEvent.RunFinished(input.getThreadId(), input.getRunId()));
        }
        return List.copyOf(events);
    }

    private ResumeContractResult validateResumeStatuses(List<AguiResume> resumes) {
        for (AguiResume resume : resumes) {
            if (!resume.isResolved() && !resume.isCancelled()) {
                return ResumeContractResult.error(
                        "RunAgentInput.resume contains unsupported status: " + resume.getStatus());
            }
        }
        return ResumeContractResult.proceed();
    }

    private boolean shouldPassResumeInterrupt(AguiEvent.Interrupt interrupt) {
        if (TOOL_CALL_INTERRUPT_REASON.equals(interrupt.reason())) {
            return interrupt.toolCallId() != null && !interrupt.toolCallId().isBlank();
        }
        return false;
    }

    record ResumeContractResult(boolean error, String message) {

        boolean isError() {
            return error;
        }

        static ResumeContractResult proceed() {
            return new ResumeContractResult(false, null);
        }

        static ResumeContractResult error(String message) {
            return new ResumeContractResult(true, message);
        }
    }
}
