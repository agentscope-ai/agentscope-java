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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec.LocalTaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

/** Subagent task hints preserve terminal status and errors through every message-bus wiring path. */
class SubagentTaskSettledHintTest {

    private static final String PARENT_ID = "parent";
    private static final String USER_ID = "original-user";
    private static final String SESSION_ID = "original-session";
    private static final String TASK_ID = "task_settled";
    private static final String SUBAGENT_ID = "image-reader";
    private static final String ERROR = "boom: rate limited";
    private static final String OUTPUT = "done: 3 findings";

    @TempDir Path tempDir;

    private HarnessAgent agent;
    private WorkspaceManager workspaceManager;
    private WorkspaceTaskRepository repo;
    private RecordingBus recordingBus;

    @AfterEach
    void closeResources() {
        try {
            if (agent != null) {
                agent.close();
            }
        } finally {
            if (repo != null) {
                repo.shutdown();
            }
            if (workspaceManager != null) {
                workspaceManager.close();
            }
        }
    }

    @Test
    void failedTaskIsAnnouncedWithErrorNotCompletion() {
        String hint =
                SubagentsMiddleware.taskSettledHintContent(
                        "task_1", "image-reader", TaskStatus.FAILED, null, "boom: rate limited");

        assertTrue(hint.contains("FAILED"), hint);
        assertTrue(hint.contains("boom: rate limited"), hint);
        assertTrue(hint.contains("execution failure, not a business conclusion"), hint);
        assertFalse(hint.contains("has completed"), hint);
        assertFalse(hint.contains("Result:"), hint);
    }

    @Test
    void completedTaskKeepsCompletionWording() {
        String hint =
                SubagentsMiddleware.taskSettledHintContent(
                        "task_1", "image-reader", TaskStatus.COMPLETED, "done: 3 findings", null);

        assertTrue(hint.contains("has completed"), hint);
        assertTrue(hint.contains("done: 3 findings"), hint);
        assertFalse(hint.contains("FAILED"), hint);
    }

    @Test
    void failedTaskWithoutMessageStillSaysFailed() {
        String hint =
                SubagentsMiddleware.taskSettledHintContent(
                        "task_1", "worker", TaskStatus.FAILED, null, null);

        assertTrue(hint.contains("FAILED"), hint);
        assertTrue(hint.contains("(no error message)"), hint);
    }

    @ParameterizedTest
    @EnumSource(EntryMode.class)
    void failedTaskWiringPushesErrorAndWakesOriginalSession(EntryMode mode) throws Exception {
        wire(mode);
        RuntimeContext rc = runtimeContext(false);
        BackgroundTask task = submitFailingTask(rc);

        String hint = awaitHintAndWakeup(task);

        assertEquals(TaskStatus.FAILED, task.getTaskStatus());
        assertEquals(ERROR, task.getError().getMessage());
        assertDurableResult(rc, TaskStatus.FAILED, null, ERROR);
        assertTrue(hint.contains("FAILED"), hint);
        assertTrue(hint.contains(ERROR), hint);
        assertFalse(hint.contains("has completed"), hint);
        assertFalse(hint.contains("Result:"), hint);
    }

    @ParameterizedTest
    @EnumSource(EntryMode.class)
    void completedTaskWiringPushesOutputAndWakesOriginalSession(EntryMode mode) throws Exception {
        wire(mode);
        RuntimeContext rc = runtimeContext(false);
        BackgroundTask task = submitTask(rc, () -> OUTPUT);

        String hint = awaitHintAndWakeup(task);

        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
        assertDurableResult(rc, TaskStatus.COMPLETED, OUTPUT, null);
        assertTrue(hint.contains("has completed"), hint);
        assertTrue(hint.contains(OUTPUT), hint);
        assertFalse(hint.contains("FAILED"), hint);
    }

    @ParameterizedTest
    @EnumSource(EntryMode.class)
    void nullResultTaskWiringStillAnnouncesCompletion(EntryMode mode) throws Exception {
        wire(mode);
        RuntimeContext rc = runtimeContext(false);
        BackgroundTask task = submitTask(rc, () -> null);

        String hint = awaitHintAndWakeup(task);

        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
        assertDurableResult(rc, TaskStatus.COMPLETED, null, null);
        assertTrue(hint.contains("has completed"), hint);
        assertTrue(hint.contains("(no output)"), hint);
        assertFalse(hint.contains("FAILED"), hint);
    }

    @ParameterizedTest
    @EnumSource(EntryMode.class)
    void suppressedFailedTaskPreservesDurableErrorWithoutNotifications(EntryMode mode)
            throws Exception {
        wire(mode);
        RuntimeContext rc = runtimeContext(true);
        BackgroundTask task = submitFailingTask(rc);

        assertTrue(task.waitForCompletion(5_000), "task did not settle within 5 seconds");

        assertEquals(TaskStatus.FAILED, task.getTaskStatus());
        assertEquals(ERROR, task.getError().getMessage());
        assertDurableResult(rc, TaskStatus.FAILED, null, ERROR);
        assertEquals(List.of(), recordingBus.queued);
        assertEquals(List.of(), recordingBus.published);
    }

    private void wire(EntryMode mode) {
        workspaceManager = new WorkspaceManager(tempDir);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, PARENT_ID);
        recordingBus = new RecordingBus();
        if (mode == EntryMode.DIRECT) {
            new SubagentsMiddleware(List.of(), repo, workspaceManager)
                    .wireMessageBus(recordingBus.bus, PARENT_ID);
            return;
        }
        Model model = mock(Model.class);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(PARENT_ID)
                        .agentId(PARENT_ID)
                        .model(model)
                        .workspace(tempDir)
                        .abstractFilesystem(new LocalFilesystem(tempDir))
                        .taskRepository(repo)
                        .messageBus(recordingBus.bus)
                        .disableMemoryTools()
                        .disableMemoryHooks();
        if (mode == EntryMode.BUILDER_STATIC) {
            builder.disableDynamicSubagents();
        }
        agent = builder.build();
    }

    private static RuntimeContext runtimeContext(boolean suppress) {
        return RuntimeContext.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID)
                .put(TaskRepository.SUPPRESS_COMPLETION_CALLBACK, suppress)
                .build();
    }

    private BackgroundTask submitTask(RuntimeContext rc, Supplier<String> execution) {
        return repo.putTask(rc, TASK_ID, SUBAGENT_ID, SESSION_ID, new LocalTaskRunSpec(execution));
    }

    private BackgroundTask submitFailingTask(RuntimeContext rc) {
        return submitTask(
                rc,
                () -> {
                    throw new IllegalStateException(ERROR);
                });
    }

    private String awaitHintAndWakeup(BackgroundTask task) throws Exception {
        recordingBus.notified.get(5, TimeUnit.SECONDS);
        assertTrue(task.waitForCompletion(5_000), "task did not settle within 5 seconds");
        assertEquals(
                List.of("agentscope:inbox:" + SESSION_ID, "agentscope:wakeups"),
                recordingBus.queued.stream().map(BusCall::key).toList());
        Map<String, Object> payload = recordingBus.queued.get(0).payload();
        assertEquals("hint", payload.get("type"));
        assertEquals("subagent_task", payload.get("source"));
        String hint = (String) payload.get("hint");
        assertTrue(hint.contains(TASK_ID), hint);
        assertTrue(hint.contains(SUBAGENT_ID), hint);
        assertEquals(
                Map.of("userId", USER_ID, "sessionId", SESSION_ID, "agentId", PARENT_ID),
                recordingBus.queued.get(1).payload());
        assertEquals(
                List.of(new BusCall("agentscope:wakeup_signal", Map.of())), recordingBus.published);
        return hint;
    }

    private void assertDurableResult(
            RuntimeContext rc, TaskStatus status, String result, String errorMessage) {
        var record =
                workspaceManager.readTaskRecord(rc, PARENT_ID, SESSION_ID, TASK_ID).orElseThrow();
        assertEquals(TASK_ID, record.getTaskId());
        assertEquals(SUBAGENT_ID, record.getSubAgentId());
        assertEquals(PARENT_ID, record.getParentAgentId());
        assertEquals(SESSION_ID, record.getParentSessionId());
        assertEquals(status, record.getStatus());
        assertEquals(result, record.getResult());
        assertEquals(errorMessage, record.getErrorMessage());
    }

    enum EntryMode {
        DIRECT,
        BUILDER_DYNAMIC,
        BUILDER_STATIC
    }

    private record BusCall(String key, Map<String, Object> payload) {}

    private static final class RecordingBus {
        private final MessageBus bus = mock(MessageBus.class, CALLS_REAL_METHODS);
        private final List<BusCall> queued = new CopyOnWriteArrayList<>();
        private final List<BusCall> published = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> notified = new CompletableFuture<>();

        private RecordingBus() {
            doAnswer(
                            invocation -> {
                                String key = invocation.getArgument(0);
                                Map<String, Object> payload = invocation.getArgument(1);
                                return Mono.fromSupplier(
                                        () -> {
                                            queued.add(new BusCall(key, Map.copyOf(payload)));
                                            completeIfNotified();
                                            return "entry-" + queued.size();
                                        });
                            })
                    .when(bus)
                    .queuePush(anyString(), anyMap());
            doAnswer(
                            invocation -> {
                                String key = invocation.getArgument(0);
                                Map<String, Object> payload = invocation.getArgument(1);
                                return Mono.defer(
                                        () -> {
                                            published.add(new BusCall(key, Map.copyOf(payload)));
                                            completeIfNotified();
                                            return Mono.empty();
                                        });
                            })
                    .when(bus)
                    .publish(anyString(), anyMap());
        }

        private void completeIfNotified() {
            if (queued.stream().anyMatch(call -> call.key().startsWith("agentscope:inbox:"))
                    && queued.stream().anyMatch(call -> call.key().equals("agentscope:wakeups"))
                    && published.stream()
                            .anyMatch(call -> call.key().equals("agentscope:wakeup_signal"))) {
                notified.complete(null);
            }
        }
    }
}
