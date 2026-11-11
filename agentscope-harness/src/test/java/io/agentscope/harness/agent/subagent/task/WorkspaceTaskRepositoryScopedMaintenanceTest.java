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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkspaceTaskRepositoryScopedMaintenanceTest {
    @TempDir Path workspace;
    private final HookedStore store = new HookedStore();

    private static class HookedStore extends InMemoryStore {
        Runnable beforeCas;
        boolean failRegistration;

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            if (failRegistration && namespace.contains("task-scopes")) {
                throw new IllegalStateException("synthetic registry outage");
            }
            super.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(
                List<String> namespace,
                String key,
                Map<String, Object> value,
                long expectedVersion) {
            Runnable hook = beforeCas;
            beforeCas = null;
            if (hook != null) {
                hook.run();
            }
            return super.putIfVersion(namespace, key, value, expectedVersion);
        }
    }

    private final List<WorkspaceTaskRepository> repositories = new ArrayList<>();

    @AfterEach
    void shutdown() {
        repositories.forEach(WorkspaceTaskRepository::shutdown);
    }

    private WorkspaceManager manager(IsolationScope scope) {
        var namespaces = scope.toNamespaceFactory();
        var filesystem =
                new RemoteFilesystemSpec(store)
                        .isolationScope(scope)
                        .toFilesystem(workspace, "parent", namespaces);
        return new WorkspaceManager(workspace, filesystem, null, namespaces);
    }

    private WorkspaceTaskRepository repository(WorkspaceManager manager) {
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        repositories.add(repository);
        return repository;
    }

    private RuntimeContext context(String user, String session) {
        return RuntimeContext.builder().userId(user).sessionId(session).build();
    }

    private void write(WorkspaceManager manager, RuntimeContext context, String taskId) {
        var record = new TaskRecord(taskId, "child", "parent", context.getSessionId(), null);
        record.setStatus(TaskStatus.RUNNING);
        manager.writeTaskRecord(context, "parent", context.getSessionId(), record);
    }

    @ParameterizedTest
    @EnumSource(IsolationScope.class)
    void failsLostExecutorInOriginalScopeAfterManagerAndRepositoryRecreation(IsolationScope scope) {
        var context = context("user-a", "session-a");
        write(manager(scope), context, "task-a");

        // Neither the recreated manager nor repository retains the originating context in memory.
        var restored = manager(scope);
        var repository = repository(restored);
        repository.sweepOrphanedTasks(
                Duration.ofMinutes(10),
                Duration.ofHours(1),
                Instant.now().plus(Duration.ofMinutes(11)));

        var record =
                restored.readTaskRecord(context, "parent", "session-a", "task-a").orElseThrow();
        assertEquals(TaskStatus.FAILED, record.getStatus());
        assertTrue(record.getErrorMessage().contains("executor lost"));
        assertTrue(
                assertText(
                                new TaskTool(repository).taskOutput(context, "task-a", true, 10L),
                                ToolResultState.ERROR)
                        .contains("executor lost"));
    }

    @ParameterizedTest
    @EnumSource(IsolationScope.class)
    void doesNotFailFreshTaskOnAnotherNode(IsolationScope scope) {
        var context = context("user-a", "session-a");
        write(manager(scope), context, "task-a");
        var restored = manager(scope);
        repository(restored)
                .sweepOrphanedTasks(
                        Duration.ofMinutes(10),
                        Duration.ofHours(1),
                        Instant.now().plus(Duration.ofMinutes(5)));
        assertEquals(
                TaskStatus.RUNNING,
                restored.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void maintenanceDoesNotExpandUserToolVisibility() {
        var first = context("user-a", "same-session");
        var second = context("user-b", "same-session");
        var manager = manager(IsolationScope.USER);
        write(manager, first, "task-a");
        write(manager, second, "task-b");

        var restored = manager(IsolationScope.USER);
        assertNull(repository(restored).getTask(first, "same-session", "task-b"));
        repository(restored)
                .sweepOrphanedTasks(
                        Duration.ofMinutes(10),
                        Duration.ofHours(1),
                        Instant.now().plus(Duration.ofMinutes(11)));
        assertEquals(
                TaskStatus.FAILED,
                restored.readTaskRecord(first, "parent", "same-session", "task-a")
                        .orElseThrow()
                        .getStatus());
        assertEquals(
                TaskStatus.FAILED,
                restored.readTaskRecord(second, "parent", "same-session", "task-b")
                        .orElseThrow()
                        .getStatus());
        assertNull(repository(restored).getTask(first, "same-session", "task-b"));
    }

    @Test
    void concurrentRegistrationsSurviveRecreationAndPagination() throws Exception {
        var manager = manager(IsolationScope.SESSION);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> registrations = new ArrayList<>();
            for (int i = 0; i < 130; i++) {
                String session = "session-" + i;
                registrations.add(
                        () -> {
                            write(manager, context("user-a", session), "task-a");
                            return null;
                        });
            }
            for (var result : executor.invokeAll(registrations)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
        var restored = manager(IsolationScope.SESSION);
        assertEquals(130, restored.listTaskRecordsForMaintenance("parent").size());
        repository(restored)
                .sweepOrphanedTasks(
                        Duration.ofMinutes(10),
                        Duration.ofHours(1),
                        Instant.now().plus(Duration.ofMinutes(11)));
        for (int i = 0; i < 130; i++) {
            String session = "session-" + i;
            assertEquals(
                    TaskStatus.FAILED,
                    restored.readTaskRecord(context("user-a", session), "parent", session, "task-a")
                            .orElseThrow()
                            .getStatus());
        }
    }

    @Test
    void accessingLegacyRecordBackfillsScopeAndRegistrationFailureDoesNotLoseRecord() {
        var context = context("user-a", "session-a");
        store.failRegistration = true;
        write(manager(IsolationScope.SESSION), context, "task-a");
        store.failRegistration = false;
        var restored = manager(IsolationScope.SESSION);
        assertTrue(restored.listTaskRecordsForMaintenance("parent").isEmpty());
        assertEquals(
                TaskStatus.RUNNING,
                restored.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .getStatus());
        assertEquals(
                1, manager(IsolationScope.SESSION).listTaskRecordsForMaintenance("parent").size());
    }

    @Test
    void downtimeBeyondRecentWindowDoesNotHideOrphan() {
        var context = context("user-a", "session-a");
        write(manager(IsolationScope.SESSION), context, "task-a");
        var restored = manager(IsolationScope.SESSION);
        var namespace = List.of("agents", "parent", "sessions", "session-a", "tasks");
        var file = store.get(namespace, "/session-a.json");
        var value = new HashMap<>(file.value());
        value.put("modified_at", Instant.now().minus(Duration.ofHours(1)).toString());
        store.put(namespace, file.key(), value);
        assertTrue(
                restored.listAllTaskRecords(context, "parent", Duration.ofMinutes(25)).isEmpty());
        repository(restored)
                .sweepOrphanedTasks(
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(25),
                        Instant.now().plus(Duration.ofDays(1)));
        assertEquals(
                TaskStatus.FAILED,
                restored.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void heartbeatRacingOrphanWriteKeepsTaskRunning() {
        var context = context("user-a", "session-a");
        var owner = manager(IsolationScope.SESSION);
        write(owner, context, "task-a");
        Instant heartbeat =
                owner.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .getLastUpdatedAt();
        var restored = manager(IsolationScope.SESSION);
        // Advance the persisted heartbeat explicitly, independent of platform clock resolution.
        store.beforeCas =
                () ->
                        assertDoesNotThrow(
                                () -> {
                                    var namespace =
                                            List.of(
                                                    "agents",
                                                    "parent",
                                                    "sessions",
                                                    "session-a",
                                                    "tasks");
                                    var file = store.get(namespace, "/session-a.json");
                                    var value = new HashMap<>(file.value());
                                    var tasks =
                                            new ObjectMapper()
                                                    .readTree((String) value.get("content"));
                                    ((ObjectNode) tasks.get("task-a"))
                                            .put(
                                                    "lastUpdatedAt",
                                                    heartbeat.plusSeconds(1).toString());
                                    value.put("content", tasks.toString());
                                    store.put(namespace, file.key(), value);
                                });
        repository(restored)
                .sweepOrphanedTasks(
                        Duration.ofMinutes(10),
                        Duration.ofHours(1),
                        heartbeat.plus(Duration.ofMinutes(10)));
        assertEquals(
                TaskStatus.RUNNING,
                restored.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void delayedHeartbeatCannotReviveTaskAfterOrphanTransition() {
        var context = context("user-a", "session-a");
        var owner = manager(IsolationScope.SESSION);
        write(owner, context, "task-a");
        var delayed = owner.readTaskRecord(context, "parent", "session-a", "task-a").orElseThrow();
        var sweeper = manager(IsolationScope.SESSION);
        store.beforeCas =
                () ->
                        sweeper.failOrphanedTask(
                                context,
                                "parent",
                                "session-a",
                                "task-a",
                                Instant.now().plusSeconds(1),
                                "executor lost: synthetic test");
        owner.writeTaskRecord(context, "parent", "session-a", delayed);
        assertEquals(
                TaskStatus.FAILED,
                owner.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void delayedHeartbeatCannotEraseConcurrentCancellation() {
        var context = context("user-a", "session-a");
        var owner = manager(IsolationScope.SESSION);
        write(owner, context, "task-a");
        var delayed = owner.readTaskRecord(context, "parent", "session-a", "task-a").orElseThrow();
        var canceller = manager(IsolationScope.SESSION);
        store.beforeCas =
                () -> {
                    var record =
                            canceller
                                    .readTaskRecord(context, "parent", "session-a", "task-a")
                                    .orElseThrow();
                    record.setCancelRequested(true);
                    canceller.writeTaskRecord(context, "parent", "session-a", record);
                };
        owner.writeTaskRecord(context, "parent", "session-a", delayed);
        assertTrue(
                owner.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .isCancelRequested());
    }
}
