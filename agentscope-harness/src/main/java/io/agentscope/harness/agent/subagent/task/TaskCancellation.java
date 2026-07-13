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
package io.agentscope.harness.agent.subagent.task;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Typed acknowledgement for a background-task cancellation request.
 *
 * <p>{@link #requestStatus()} describes whether cancellation intent was accepted. It deliberately
 * does not imply that the child execution has stopped. Callers that must wait before cleaning up
 * parent resources can observe {@link #terminationSignal()} or call {@link
 * #awaitTermination(Duration)} with their own time bound.
 */
public final class TaskCancellation {

    /** Outcome of recording and dispatching a cancellation request. */
    public enum RequestStatus {
        ACCEPTED,
        ALREADY_REQUESTED,
        ALREADY_TERMINAL,
        NOT_FOUND,
        REJECTED
    }

    /** Execution mechanism owned (or observed) by the task repository. */
    public enum ExecutionKind {
        LOCAL,
        REMOTE,
        ADOPTED,
        UNKNOWN
    }

    /** Authoritative outcome of attempting to stop the underlying execution. */
    public enum TerminationStatus {
        COOPERATIVE_STOP_CONFIRMED,
        FORCED_STOP_CONFIRMED,
        ALREADY_STOPPED,
        DETACHED,
        TIMED_OUT,
        STOP_UNAVAILABLE,
        CANCELLATION_FAILED
    }

    /** Stable identity of the parent task and, where applicable, the remote child execution. */
    public record ExecutionIdentity(
            String parentSessionId,
            String taskId,
            String subAgentId,
            String childSessionId,
            ExecutionKind executionKind,
            String remoteTaskId) {

        public ExecutionIdentity {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(executionKind, "executionKind");
        }
    }

    /** Completion value emitted only when execution stop is known, unavailable, or has failed. */
    public record Termination(
            ExecutionIdentity identity, TerminationStatus status, String message) {

        public Termination {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(status, "status");
        }

        public boolean isStopConfirmed() {
            return status == TerminationStatus.COOPERATIVE_STOP_CONFIRMED
                    || status == TerminationStatus.FORCED_STOP_CONFIRMED
                    || status == TerminationStatus.ALREADY_STOPPED;
        }
    }

    private final ExecutionIdentity identity;
    private final RequestStatus requestStatus;
    private final CompletableFuture<Termination> termination;

    public TaskCancellation(
            ExecutionIdentity identity,
            RequestStatus requestStatus,
            CompletableFuture<Termination> termination) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.requestStatus = Objects.requireNonNull(requestStatus, "requestStatus");
        this.termination = Objects.requireNonNull(termination, "termination");
    }

    public ExecutionIdentity identity() {
        return identity;
    }

    public RequestStatus requestStatus() {
        return requestStatus;
    }

    /** Whether the repository found the task and accepted this or an earlier cancel request. */
    public boolean isCancellationAccepted() {
        return requestStatus == RequestStatus.ACCEPTED
                || requestStatus == RequestStatus.ALREADY_REQUESTED;
    }

    public CompletionStage<Termination> terminationSignal() {
        return termination;
    }

    /**
     * Wait for authoritative execution termination without imposing a repository-wide timeout.
     *
     * <p>A timeout is returned as a value rather than completing the shared signal: another caller
     * may choose a longer bound and still receive the eventual authoritative result.
     */
    public Termination awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        try {
            return termination.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Termination(
                    identity,
                    TerminationStatus.TIMED_OUT,
                    "Execution did not stop within " + timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Termination(
                    identity,
                    TerminationStatus.CANCELLATION_FAILED,
                    cause.getMessage() != null
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName());
        }
    }
}
