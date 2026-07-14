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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTaskCancellationTest {

    @TempDir Path tempDir;

    private WorkspaceTaskRepository repository;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        repository =
                WorkspaceTaskRepository.forTests(
                        new WorkspaceManager(tempDir), "cancellation-test-agent");
    }

    @AfterEach
    void tearDown() {
        repository.shutdown();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cancelledRecordDoesNotAcknowledgeSupplierThatIsStillRunning() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean exited = new AtomicBoolean();

        repository.putTask(
                RuntimeContext.empty(),
                "task-ignores-interrupt",
                "local-agent",
                "session",
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            running.countDown();
                            try {
                                while (release.getCount() > 0) {
                                    try {
                                        release.await();
                                    } catch (InterruptedException e) {
                                        interrupted.set(true);
                                    }
                                }
                                return "done";
                            } finally {
                                exited.set(true);
                            }
                        }));

        assertTrue(running.await(5, TimeUnit.SECONDS));
        TaskCancellation cancellation =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "task-ignores-interrupt");

        assertEquals(TaskCancellation.RequestStatus.ACCEPTED, cancellation.requestStatus());
        assertEquals(TaskCancellation.ExecutionKind.LOCAL, cancellation.identity().executionKind());
        assertEquals(
                TaskCancellation.TerminationStatus.TIMED_OUT,
                cancellation.awaitTermination(Duration.ofMillis(100)).status());
        assertTrue(interrupted.get(), "repository should request cooperative interruption");
        assertFalse(exited.get(), "supplier still owns execution until it actually exits");

        release.countDown();
        TaskCancellation.Termination termination =
                cancellation.awaitTermination(Duration.ofSeconds(5));
        assertEquals(
                TaskCancellation.TerminationStatus.COOPERATIVE_STOP_CONFIRMED,
                termination.status());
        assertTrue(termination.isStopConfirmed());
    }

    @Test
    void repeatedCancellationIsIdempotentAndSharesStopAcknowledgement() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.putTask(
                RuntimeContext.empty(),
                "task-repeat",
                "local-agent",
                "session",
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            running.countDown();
                            while (release.getCount() > 0) {
                                try {
                                    release.await();
                                } catch (InterruptedException ignored) {
                                    // Keep running until the test explicitly releases execution.
                                }
                            }
                            return "done";
                        }));

        assertTrue(running.await(5, TimeUnit.SECONDS));
        TaskCancellation first =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "task-repeat");
        TaskCancellation second =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "task-repeat");

        assertEquals(TaskCancellation.RequestStatus.ACCEPTED, first.requestStatus());
        assertEquals(TaskCancellation.RequestStatus.ALREADY_REQUESTED, second.requestStatus());
        release.countDown();
        assertEquals(
                TaskCancellation.TerminationStatus.COOPERATIVE_STOP_CONFIRMED,
                first.awaitTermination(Duration.ofSeconds(5)).status());
        assertEquals(
                TaskCancellation.TerminationStatus.COOPERATIVE_STOP_CONFIRMED,
                second.awaitTermination(Duration.ofSeconds(5)).status());
    }

    @Test
    void adoptedExecutionIsReportedAsDetached() throws Exception {
        CompletableFuture<String> adopted = new CompletableFuture<>();
        repository.putTask(
                RuntimeContext.empty(),
                "task-adopted",
                "adopted-agent",
                "session",
                new TaskRunSpec.AdoptedTaskRunSpec(adopted));

        TaskCancellation cancellation =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "task-adopted");

        assertEquals(
                TaskCancellation.ExecutionKind.ADOPTED, cancellation.identity().executionKind());
        assertEquals(
                TaskCancellation.TerminationStatus.DETACHED,
                cancellation.awaitTermination(Duration.ofSeconds(1)).status());
    }

    @Test
    void missingTaskReturnsTypedNotFoundResult() throws Exception {
        TaskCancellation cancellation =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "missing");

        assertEquals(TaskCancellation.RequestStatus.NOT_FOUND, cancellation.requestStatus());
        assertEquals(
                TaskCancellation.TerminationStatus.STOP_UNAVAILABLE,
                cancellation.awaitTermination(Duration.ZERO).status());
    }

    @Test
    void remoteCancellationWaitsForProtocolTerminalStatus() throws Exception {
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicReference<String> remoteStatus = new AtomicReference<>("running");
        startTaskServer(submitted, remoteStatus, false);

        repository.putTask(
                RuntimeContext.empty(),
                "remote-task",
                "remote-agent",
                "session",
                new TaskRunSpec.RemoteTaskRunSpec(
                        serverBaseUrl(), Map.of(), "remote-agent", "input"));
        assertTrue(submitted.await(5, TimeUnit.SECONDS));

        TaskCancellation cancellation =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "remote-task");
        TaskCancellation.Termination termination =
                cancellation.awaitTermination(Duration.ofSeconds(5));

        assertEquals(
                TaskCancellation.ExecutionKind.REMOTE, cancellation.identity().executionKind());
        assertEquals("remote-task", cancellation.identity().remoteTaskId());
        assertEquals(
                TaskCancellation.TerminationStatus.COOPERATIVE_STOP_CONFIRMED,
                termination.status());
    }

    @Test
    void remoteProtocolFailureIsAnAuthoritativeCancellationResult() throws Exception {
        CountDownLatch submitted = new CountDownLatch(1);
        startTaskServer(submitted, new AtomicReference<>("running"), true);

        repository.putTask(
                RuntimeContext.empty(),
                "remote-failure",
                "remote-agent",
                "session",
                new TaskRunSpec.RemoteTaskRunSpec(
                        serverBaseUrl(), Map.of(), "remote-agent", "input"));
        assertTrue(submitted.await(5, TimeUnit.SECONDS));

        TaskCancellation cancellation =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "remote-failure");

        assertEquals(
                TaskCancellation.TerminationStatus.CANCELLATION_FAILED,
                cancellation.awaitTermination(Duration.ofSeconds(5)).status());
    }

    @Test
    void remoteCancellationDoesNotCompleteWhileRemoteStillReportsRunning() throws Exception {
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch cancelReceived = new CountDownLatch(1);
        AtomicReference<String> remoteStatus = new AtomicReference<>("running");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/tasks",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if ("/tasks".equals(path)) {
                        submitted.countDown();
                    } else if (path.endsWith("/cancel")) {
                        cancelReceived.countDown();
                    }
                    String body =
                            path.endsWith("/cancel")
                                    ? "{}"
                                    : "{\"status\":\"" + remoteStatus.get() + "\"}";
                    respond(exchange, 200, body);
                });
        server.start();

        repository.putTask(
                RuntimeContext.empty(),
                "remote-delayed",
                "remote-agent",
                "session",
                new TaskRunSpec.RemoteTaskRunSpec(
                        serverBaseUrl(), Map.of(), "remote-agent", "input"));
        assertTrue(submitted.await(5, TimeUnit.SECONDS));

        TaskCancellation cancellation =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "remote-delayed");
        assertTrue(cancelReceived.await(5, TimeUnit.SECONDS));
        assertEquals(
                TaskCancellation.TerminationStatus.TIMED_OUT,
                cancellation.awaitTermination(Duration.ofMillis(100)).status());

        remoteStatus.set("cancelled");
        assertEquals(
                TaskCancellation.TerminationStatus.COOPERATIVE_STOP_CONFIRMED,
                cancellation.awaitTermination(Duration.ofSeconds(5)).status());
    }

    @Test
    void completedTaskReturnsAlreadyTerminalAcknowledgement() throws Exception {
        repository.putTask(
                RuntimeContext.empty(),
                "already-complete",
                "local-agent",
                "session",
                new TaskRunSpec.LocalTaskRunSpec(() -> "done"));
        awaitTaskStatus("session", "already-complete", TaskStatus.COMPLETED);

        TaskCancellation cancellation =
                repository.cancelTaskWithAcknowledgement(
                        RuntimeContext.empty(), "session", "already-complete");

        assertEquals(TaskCancellation.RequestStatus.ALREADY_TERMINAL, cancellation.requestStatus());
        assertEquals(
                TaskCancellation.TerminationStatus.ALREADY_STOPPED,
                cancellation.awaitTermination(Duration.ZERO).status());
    }

    private void awaitTaskStatus(String sessionId, String taskId, TaskStatus expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            BackgroundTask task = repository.getTask(RuntimeContext.empty(), sessionId, taskId);
            if (task != null && task.getTaskStatus() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Task did not reach status " + expected);
    }

    private void startTaskServer(
            CountDownLatch submitted,
            AtomicReference<String> remoteStatus,
            boolean failCancellation)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/tasks",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if ("/tasks".equals(path)) {
                        submitted.countDown();
                        respond(exchange, 202, "{}");
                    } else if (path.endsWith("/cancel")) {
                        if (failCancellation) {
                            respond(exchange, 500, "cancel failed");
                        } else {
                            remoteStatus.set("cancelled");
                            respond(exchange, 200, "{}");
                        }
                    } else {
                        respond(exchange, 200, "{\"status\":\"" + remoteStatus.get() + "\"}");
                    }
                });
        server.start();
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
