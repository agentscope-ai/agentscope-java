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

import io.agentscope.core.agui.event.AguiEvent;
import java.util.Map;

/**
 * Storage contract for AG-UI interrupt resume coordination state.
 *
 * <p>Implementations used by multiple application replicas must make each mutation atomic in the
 * backing store. In particular, {@link #claimRun} is a create-if-absent operation, {@link
 * #releaseRun} is a conditional delete, and {@link #replacePendingInterrupts} must check run
 * ownership and replace the pending interrupt set as one operation.
 *
 * <p>Storage failures should be propagated to the caller. Treating a failure as missing state can
 * incorrectly reject a valid resume or allow concurrent runs for one thread.
 *
 * <p>Thread and run identifiers must be non-null and non-blank, as defined by {@link
 * String#isBlank()}. Implementations must validate identifiers before accessing or changing stored
 * state. Valid identifiers are used unchanged, including their case and leading or trailing
 * whitespace; they must not be trimmed or otherwise normalized.
 *
 * <p>This is a synchronous SPI. The request processor schedules calls on blocking-capable workers;
 * implementations must support concurrent requests and use finite I/O timeouts. A run ID is also
 * its ownership token and must not be reused for a later execution while stale operations are
 * possible.
 */
public interface AguiResumeStateStore {

    /**
     * Return the pending interrupts for a thread.
     *
     * @param threadId the AG-UI thread ID
     * @return an immutable snapshot, or an empty map when the thread has no pending interrupts
     * @throws NullPointerException if {@code threadId} is null
     * @throws IllegalArgumentException if {@code threadId} is blank
     */
    Map<String, AguiEvent.Interrupt> getPendingInterrupts(String threadId);

    /**
     * Atomically claim a thread for a run if it has no active owner.
     *
     * @param threadId the AG-UI thread ID
     * @param runId the run attempting to claim the thread
     * @return the claim result, including the current owner when the claim is rejected
     * @throws NullPointerException if {@code threadId} or {@code runId} is null
     * @throws IllegalArgumentException if {@code threadId} or {@code runId} is blank
     */
    RunClaim claimRun(String threadId, String runId);

    /**
     * Atomically release a thread only when {@code runId} is its current owner.
     *
     * <p>This operation must be idempotent and preserve pending interrupts. The request processor
     * can retry it after a storage failure, including a lost reply after successful release.
     *
     * @param threadId the AG-UI thread ID
     * @param runId the run expected to own the thread
     * @throws NullPointerException if {@code threadId} or {@code runId} is null
     * @throws IllegalArgumentException if {@code threadId} or {@code runId} is blank
     */
    void releaseRun(String threadId, String runId);

    /**
     * Atomically replace a thread's complete pending interrupt set only when {@code runId} is its
     * current owner. Passing an empty map clears the pending interrupt state.
     *
     * @param threadId the AG-UI thread ID
     * @param runId the run expected to own the thread
     * @param pendingInterrupts the complete new pending interrupt set
     * @return {@code true} when the state was replaced, or {@code false} when the run was not the
     *     current owner
     * @throws NullPointerException if {@code threadId}, {@code runId}, or {@code pendingInterrupts}
     *     is null
     * @throws IllegalArgumentException if {@code threadId} or {@code runId} is blank
     */
    boolean replacePendingInterrupts(
            String threadId, String runId, Map<String, AguiEvent.Interrupt> pendingInterrupts);

    /** Result of an atomic active-run claim. */
    record RunClaim(boolean claimed, String activeRunId) {

        /** Create a successful claim result. */
        public static RunClaim acquired() {
            return new RunClaim(true, null);
        }

        /** Create a rejected claim result with the current owner. */
        public static RunClaim rejected(String activeRunId) {
            return new RunClaim(false, activeRunId);
        }
    }
}
