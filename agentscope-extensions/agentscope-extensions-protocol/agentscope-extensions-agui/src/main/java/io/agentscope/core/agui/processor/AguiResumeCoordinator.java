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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    private final ConcurrentMap<String, Map<String, AguiEvent.Interrupt>>
            pendingInterruptsByThread = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeRunsByThread = new ConcurrentHashMap<>();

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
        ResumeContractResult resumeContract = validate(input);
        if (resumeContract.isError()) {
            return resumeContract;
        }
        String previousRunId =
                activeRunsByThread.putIfAbsent(input.getThreadId(), input.getRunId());
        if (previousRunId == null) {
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
        activeRunsByThread.remove(threadId, runId);
    }

    /**
     * Add known interrupt-to-tool-call mappings to the runtime context for resume conversion.
     *
     * @param input The run input containing resume entries
     * @param runtimeContext The caller-provided runtime context, if any
     * @return A runtime context with AG-UI resume tool-call mappings when available
     */
    RuntimeContext addResumeToolCallIds(RunAgentInput input, RuntimeContext runtimeContext) {
        if (!input.hasResume()) {
            return runtimeContext;
        }
        Map<String, AguiEvent.Interrupt> pending =
                pendingInterruptsByThread.get(input.getThreadId());
        if (pending == null || pending.isEmpty()) {
            return runtimeContext;
        }
        Map<String, String> toolCallIds = new LinkedHashMap<>();
        for (AguiResume resume : input.getResume()) {
            AguiEvent.Interrupt interrupt = pending.get(resume.getInterruptId());
            if (interrupt != null
                    && "tool_call".equals(interrupt.reason())
                    && interrupt.toolCallId() != null
                    && !interrupt.toolCallId().isBlank()) {
                toolCallIds.put(resume.getInterruptId(), interrupt.toolCallId());
            }
        }
        if (toolCallIds.isEmpty()) {
            return runtimeContext;
        }
        return RuntimeContext.builder(runtimeContext)
                .put(
                        AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_TOOL_CALL_IDS_KEY,
                        Map.copyOf(toolCallIds))
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
        if (!runId.equals(activeRunsByThread.get(threadId))) {
            return;
        }
        if (runFinished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome interruptOutcome
                && !interruptOutcome.interrupts().isEmpty()) {
            Map<String, AguiEvent.Interrupt> interrupts = new ConcurrentHashMap<>();
            for (AguiEvent.Interrupt interrupt : interruptOutcome.interrupts()) {
                interrupts.put(interrupt.id(), interrupt);
            }
            pendingInterruptsByThread.put(threadId, Map.copyOf(interrupts));
        } else if (!runErrorSeen) {
            pendingInterruptsByThread.remove(threadId);
        }
    }

    /**
     * Build the AG-UI error event used when the resume contract is violated.
     *
     * @param input The invalid run input
     * @param message The validation error message
     * @return The protocol error event
     */
    AguiEvent.RunError contractError(RunAgentInput input, String message) {
        return new AguiEvent.RunError(
                input.getThreadId(),
                input.getRunId(),
                message,
                CONTRACT_ERROR_CODE,
                System.currentTimeMillis(),
                null);
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
