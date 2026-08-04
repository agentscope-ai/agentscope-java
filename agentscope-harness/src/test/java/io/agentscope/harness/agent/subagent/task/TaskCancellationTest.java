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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TaskCancellationTest {

    private static final TaskCancellation.ExecutionIdentity IDENTITY =
            new TaskCancellation.ExecutionIdentity(
                    "parent-session",
                    "task-1",
                    "subagent",
                    "child-session",
                    TaskCancellation.ExecutionKind.LOCAL,
                    null);

    @Test
    void exposesIdentityRequestStatusAndTerminationSignal() throws Exception {
        TaskCancellation.Termination expected =
                new TaskCancellation.Termination(
                        IDENTITY,
                        TaskCancellation.TerminationStatus.COOPERATIVE_STOP_CONFIRMED,
                        "stopped");
        CompletableFuture<TaskCancellation.Termination> signal =
                CompletableFuture.completedFuture(expected);
        TaskCancellation cancellation =
                new TaskCancellation(IDENTITY, TaskCancellation.RequestStatus.ACCEPTED, signal);

        assertSame(IDENTITY, cancellation.identity());
        assertEquals(TaskCancellation.RequestStatus.ACCEPTED, cancellation.requestStatus());
        assertTrue(cancellation.isCancellationAccepted());
        assertSame(signal, cancellation.terminationSignal());
        assertSame(expected, cancellation.awaitTermination(Duration.ZERO));
    }

    @Test
    void acceptanceAndStopConfirmationCoverEveryOutcome() {
        TaskCancellation repeated = cancellation(TaskCancellation.RequestStatus.ALREADY_REQUESTED);
        TaskCancellation rejected = cancellation(TaskCancellation.RequestStatus.REJECTED);

        assertTrue(repeated.isCancellationAccepted());
        assertFalse(rejected.isCancellationAccepted());
        assertTrue(
                termination(TaskCancellation.TerminationStatus.FORCED_STOP_CONFIRMED)
                        .isStopConfirmed());
        assertTrue(
                termination(TaskCancellation.TerminationStatus.ALREADY_STOPPED).isStopConfirmed());
        assertFalse(termination(TaskCancellation.TerminationStatus.DETACHED).isStopConfirmed());
    }

    @Test
    void validatesTimeoutAndMapsExceptionalSignal() throws Exception {
        TaskCancellation pending =
                new TaskCancellation(
                        IDENTITY,
                        TaskCancellation.RequestStatus.ACCEPTED,
                        new CompletableFuture<>());
        assertEquals(
                TaskCancellation.TerminationStatus.TIMED_OUT,
                pending.awaitTermination(Duration.ofMillis(1)).status());
        assertThrows(
                IllegalArgumentException.class,
                () -> pending.awaitTermination(Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> pending.awaitTermination(null));

        CompletableFuture<TaskCancellation.Termination> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("remote cancel failed"));
        TaskCancellation exceptional =
                new TaskCancellation(IDENTITY, TaskCancellation.RequestStatus.ACCEPTED, failed);
        TaskCancellation.Termination result = exceptional.awaitTermination(Duration.ofSeconds(1));
        assertEquals(TaskCancellation.TerminationStatus.CANCELLATION_FAILED, result.status());
        assertEquals("remote cancel failed", result.message());

        CompletableFuture<TaskCancellation.Termination> messageLessFailure =
                new CompletableFuture<>();
        messageLessFailure.completeExceptionally(new RuntimeException());
        TaskCancellation noMessage =
                new TaskCancellation(
                        IDENTITY, TaskCancellation.RequestStatus.ACCEPTED, messageLessFailure);
        assertEquals(
                "RuntimeException", noMessage.awaitTermination(Duration.ofSeconds(1)).message());
    }

    @Test
    void legacyRepositoryDefaultAcknowledgementPreservesBooleanSemantics() throws Exception {
        LegacyRepository found = new LegacyRepository(true);
        LegacyRepository missing = new LegacyRepository(false);

        TaskCancellation accepted =
                found.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "task-found");
        TaskCancellation notFound =
                missing.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "task-missing");

        assertEquals(TaskCancellation.RequestStatus.ACCEPTED, accepted.requestStatus());
        assertEquals("task-found", accepted.identity().taskId());
        assertEquals(
                TaskCancellation.TerminationStatus.STOP_UNAVAILABLE,
                accepted.awaitTermination(Duration.ZERO).status());
        assertEquals(TaskCancellation.RequestStatus.NOT_FOUND, notFound.requestStatus());
        assertEquals(
                TaskCancellation.TerminationStatus.STOP_UNAVAILABLE,
                notFound.awaitTermination(Duration.ZERO).status());
    }

    private static TaskCancellation cancellation(TaskCancellation.RequestStatus status) {
        return new TaskCancellation(
                IDENTITY,
                status,
                CompletableFuture.completedFuture(
                        termination(TaskCancellation.TerminationStatus.STOP_UNAVAILABLE)));
    }

    private static TaskCancellation.Termination termination(
            TaskCancellation.TerminationStatus status) {
        return new TaskCancellation.Termination(IDENTITY, status, status.name());
    }

    private static final class LegacyRepository implements TaskRepository {

        private final boolean found;

        private LegacyRepository(boolean found) {
            this.found = found;
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return null;
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            return null;
        }

        @Override
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {}

        @Override
        public void clear() {}

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return java.util.List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return found;
        }
    }
}
