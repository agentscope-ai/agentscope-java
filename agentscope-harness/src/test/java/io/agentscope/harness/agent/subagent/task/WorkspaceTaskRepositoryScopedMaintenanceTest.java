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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepositoryNamespaceTest.TaskRoute;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class WorkspaceTaskRepositoryScopedMaintenanceTest {
    @TempDir Path workspace;
    private final HookedStore store = new HookedStore();

    private static class HookedStore extends InMemoryStore {
        boolean failRegistration;
        final List<Map<String, Object>> registrations = new CopyOnWriteArrayList<>();

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            if (failRegistration && namespace.contains("task-scopes")) {
                throw new IllegalStateException("synthetic registry outage");
            }
            super.put(namespace, key, value);
            if (namespace.contains("task-scopes")) {
                registrations.add(Map.copyOf(value));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "COMPOSITE, USER", "COMPOSITE, SESSION",
        "OUTER_ROUTED, USER", "OUTER_ROUTED, SESSION",
        "INNER_ROUTED, USER", "INNER_ROUTED, SESSION"
    })
    void nestedRoutesRegisterAndFailRestartedOrphanOnlyInOriginalScope(
            TaskRoute route, IsolationScope scope) {
        assertNestedRestart(route, scope, true);
    }

    @ParameterizedTest
    @CsvSource({
        "COMPOSITE, USER", "COMPOSITE, SESSION",
        "OUTER_ROUTED, USER", "OUTER_ROUTED, SESSION",
        "INNER_ROUTED, USER", "INNER_ROUTED, SESSION"
    })
    void slashlessNestedRoutesRegisterAndFailRestartedOrphanOnlyInOriginalScope(
            TaskRoute route, IsolationScope scope) {
        assertNestedRestart(route, scope, false);
    }

    private void assertNestedRestart(
            TaskRoute route, IsolationScope scope, boolean canonicalPrefixes) {
        var namespaces = WorkspaceTaskRepositoryNamespaceTest.nestedNamespaces(scope);
        var remote = new RemoteFilesystem(store, namespaces);
        var fs =
                WorkspaceTaskRepositoryNamespaceTest.nestedFilesystem(
                        new LocalFilesystem(workspace, true, 10), remote, route, canonicalPrefixes);
        var owner = context("owner-user", "owner-session");
        var foreign = context("foreign-user", "foreign-session");
        var expected = List.of(scope == IsolationScope.USER ? "owner-user" : "owner-session");
        try (var original = new WorkspaceManager(workspace, fs, null, namespaces)) {
            var orphan = new TaskRecord("orphan", "child", "parent", "same-session", null);
            orphan.setStatus(TaskStatus.RUNNING);
            original.writeTaskRecord(owner, "parent", "same-session", orphan);
        }
        assertTrue(
                store.get(expected, "/same-session.json")
                        .value()
                        .get("content")
                        .toString()
                        .contains("RUNNING"));
        var registrations = List.copyOf(store.registrations);
        // Recreate wrappers and manager, not just their context; only the real store survives.
        var restoredFs =
                WorkspaceTaskRepositoryNamespaceTest.nestedFilesystem(
                        new LocalFilesystem(workspace, true, 10),
                        new RemoteFilesystem(store, namespaces),
                        route,
                        canonicalPrefixes);
        try (var restored = new WorkspaceManager(workspace, restoredFs, null, namespaces)) {
            var listed = fs.glob(owner, "*.json", "agents/parent/tasks");
            assertTrue(listed.isSuccess());
            assertEquals(1, listed.matches().size());
            assertTrue(fs.read(owner, listed.matches().get(0).path(), 0, 0).isSuccess());
            var scanned = List.copyOf(restored.listTaskRecordsForMaintenance("parent"));
            repository(restored)
                    .sweepOrphanedTasks(
                            Duration.ofMinutes(10),
                            Duration.ofHours(1),
                            Instant.now().plus(Duration.ofMinutes(11)));
            var originalRecord =
                    restored.readTaskRecord(owner, "parent", "same-session", "orphan")
                            .orElseThrow();
            assertAll(
                    () -> assertFalse(registrations.isEmpty(), "scope registry must be written"),
                    () ->
                            assertTrue(
                                    registrations.stream()
                                            .allMatch(
                                                    value ->
                                                            expected.equals(value.get("namespace"))
                                                                    && "owner-user"
                                                                            .equals(
                                                                                    value.get(
                                                                                            "userId"))
                                                                    && "owner-session"
                                                                            .equals(
                                                                                    value.get(
                                                                                            "sessionId")))),
                    () ->
                            assertEquals(
                                    1,
                                    scanned.size(),
                                    "restart must discover the original RUNNING orphan"),
                    () ->
                            assertTrue(
                                    scanned.stream()
                                            .allMatch(
                                                    record ->
                                                            record.record().getStatus()
                                                                            == TaskStatus.RUNNING
                                                                    && record.record()
                                                                            .getTaskId()
                                                                            .equals("orphan")
                                                                    && expected.equals(
                                                                            namespaces.getNamespace(
                                                                                    record
                                                                                            .context())))),
                    () -> assertEquals(TaskStatus.FAILED, originalRecord.getStatus()),
                    () ->
                            assertTrue(
                                    originalRecord.getErrorMessage() != null
                                            && originalRecord
                                                    .getErrorMessage()
                                                    .contains("executor lost")),
                    () ->
                            assertTrue(
                                    restored.readTaskRecord(
                                                    foreign, "parent", "same-session", "orphan")
                                            .isEmpty()),
                    () ->
                            assertTrue(
                                    restored.readTaskRecord(
                                                    RuntimeContext.empty(),
                                                    "parent",
                                                    "same-session",
                                                    "orphan")
                                            .isEmpty()),
                    () ->
                            assertNull(
                                    store.get(
                                            namespaces.getNamespace(foreign),
                                            "/same-session.json")),
                    () -> assertNull(store.get(List.of("_default"), "/same-session.json")));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"USER", "SESSION"})
    void nestedBoundRestartMaintenanceUsesInnerDispatchIdentity(IsolationScope scope) {
        assertNestedBoundRestart(scope, true);
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"USER", "SESSION"})
    void slashlessNestedBoundRestartMaintenanceUsesInnerDispatchIdentity(IsolationScope scope) {
        assertNestedBoundRestart(scope, false);
    }

    private void assertNestedBoundRestart(IsolationScope scope, boolean canonicalPrefixes) {
        var namespaces = WorkspaceTaskRepositoryNamespaceTest.nestedNamespaces(scope);
        var inner = context("inner-user", "inner-session");
        var outer = context("outer-user", "outer-session");
        var expected = List.of(scope == IsolationScope.USER ? "inner-user" : "inner-session");
        var shared = new LocalFilesystem(workspace, true, 10);
        var fs =
                new BakedContextFilesystem(
                        WorkspaceTaskRepositoryNamespaceTest.nestedFilesystem(
                                shared,
                                new BakedContextFilesystem(
                                        new RemoteFilesystem(store, namespaces), inner),
                                TaskRoute.OUTER_ROUTED,
                                canonicalPrefixes),
                        outer);
        try (var original = new WorkspaceManager(workspace, fs, null, namespaces)) {
            var orphan = new TaskRecord("orphan", "child", "parent", "same-session", null);
            orphan.setStatus(TaskStatus.RUNNING);
            original.writeTaskRecord(
                    context("caller-user", "caller-session"), "parent", "same-session", orphan);
        }
        assertTrue(
                store.get(expected, "/same-session.json")
                        .value()
                        .get("content")
                        .toString()
                        .contains("RUNNING"));
        var registrations = List.copyOf(store.registrations);
        var restoredFs =
                new BakedContextFilesystem(
                        WorkspaceTaskRepositoryNamespaceTest.nestedFilesystem(
                                new LocalFilesystem(workspace, true, 10),
                                new BakedContextFilesystem(
                                        new RemoteFilesystem(store, namespaces),
                                        context("inner-user", "inner-session")),
                                TaskRoute.OUTER_ROUTED,
                                canonicalPrefixes),
                        context("outer-user", "outer-session"));
        try (var restored = new WorkspaceManager(workspace, restoredFs, null, namespaces)) {
            var scanned = List.copyOf(restored.listTaskRecordsForMaintenance("parent"));
            repository(restored)
                    .sweepOrphanedTasks(
                            Duration.ofMinutes(10),
                            Duration.ofHours(1),
                            Instant.now().plus(Duration.ofMinutes(11)));
            assertAll(
                    () -> assertFalse(registrations.isEmpty()),
                    () ->
                            assertTrue(
                                    registrations.stream()
                                            .allMatch(
                                                    value ->
                                                            expected.equals(value.get("namespace"))
                                                                    && "inner-user"
                                                                            .equals(
                                                                                    value.get(
                                                                                            "userId"))
                                                                    && "inner-session"
                                                                            .equals(
                                                                                    value.get(
                                                                                            "sessionId")))),
                    () -> assertEquals(1, scanned.size()),
                    () ->
                            assertTrue(
                                    scanned.stream()
                                            .allMatch(
                                                    record ->
                                                            "inner-user"
                                                                            .equals(
                                                                                    record.context()
                                                                                            .getUserId())
                                                                    && "inner-session"
                                                                            .equals(
                                                                                    record.context()
                                                                                            .getSessionId())
                                                                    && record.record().getStatus()
                                                                            == TaskStatus.RUNNING)),
                    () ->
                            assertEquals(
                                    TaskStatus.FAILED,
                                    restored.readTaskRecord(
                                                    null, "parent", "same-session", "orphan")
                                            .orElseThrow()
                                            .getStatus()),
                    () ->
                            assertNull(
                                    store.get(
                                            namespaces.getNamespace(outer), "/same-session.json")),
                    () -> assertNull(store.get(List.of("_default"), "/same-session.json")));
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
                new TaskTool(repository)
                        .taskOutput(context, "task-a", true, 10L)
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
    void orphanWriteRereadsHeartbeatAfterStaleScan() throws Exception {
        var context = context("user-a", "session-a");
        var owner = manager(IsolationScope.SESSION);
        write(owner, context, "task-a");
        Instant heartbeat =
                owner.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .getLastUpdatedAt();
        var restored = manager(IsolationScope.SESSION);
        var scanned = restored.listTaskRecordsForMaintenance("parent").iterator().next();
        assertEquals(heartbeat, scanned.record().getLastUpdatedAt());
        // Simulate a heartbeat arriving after the scan, before the locked orphan update.
        var namespace = List.of("agents", "parent", "sessions", "session-a", "tasks");
        var file = store.get(namespace, "/session-a.json");
        var value = new HashMap<>(file.value());
        var tasks = new ObjectMapper().readTree((String) value.get("content"));
        ((ObjectNode) tasks.get("task-a"))
                .put("lastUpdatedAt", heartbeat.plusSeconds(1).toString());
        value.put("content", tasks.toString());
        store.put(namespace, file.key(), value);
        assertFalse(
                restored.failOrphanedTask(
                        scanned.context(),
                        "parent",
                        "session-a",
                        "task-a",
                        heartbeat,
                        "executor lost"));
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
        assertTrue(
                sweeper.failOrphanedTask(
                        context,
                        "parent",
                        "session-a",
                        "task-a",
                        Instant.now().plusSeconds(1),
                        "executor lost: synthetic test"));
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
        var record =
                canceller.readTaskRecord(context, "parent", "session-a", "task-a").orElseThrow();
        record.setCancelRequested(true);
        canceller.writeTaskRecord(context, "parent", "session-a", record);
        owner.writeTaskRecord(context, "parent", "session-a", delayed);
        assertTrue(
                owner.readTaskRecord(context, "parent", "session-a", "task-a")
                        .orElseThrow()
                        .isCancelRequested());
    }

    private HarnessAgent harness(IsolationScope scope) {
        return HarnessAgent.builder()
                .name("parent")
                .model(org.mockito.Mockito.mock(Model.class))
                .workspace(workspace)
                .filesystem(new RemoteFilesystemSpec(store).isolationScope(scope))
                .stateStore(
                        org.mockito.Mockito.mock(
                                AgentStateStore.class,
                                org.mockito.AdditionalAnswers.delegatesTo(
                                        new InMemoryAgentStateStore())))
                .disableMemoryHooks()
                .disableSubagents()
                .build();
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"USER", "SESSION"})
    void workspaceForRegistersOriginalScopeAndKeepsMaintenanceViewBounded(IsolationScope scope) {
        var first = context("用户 one", "session one");
        var second = context("other", "other-session");
        try (var agent = harness(scope)) {
            var view = agent.workspaceFor(first.getUserId(), first.getSessionId());
            var other = agent.workspaceFor(second.getUserId(), second.getSessionId());
            var record = new TaskRecord("task-a", "child", "parent", first.getSessionId(), null);
            record.setStatus(TaskStatus.RUNNING);
            view.writeTaskRecord(second, "parent", first.getSessionId(), record);
            var otherRecord =
                    new TaskRecord("task-b", "child", "parent", second.getSessionId(), null);
            otherRecord.setStatus(TaskStatus.RUNNING);
            other.writeTaskRecord(first, "parent", second.getSessionId(), otherRecord);
            assertTrue(
                    other.readTaskRecord(first, "parent", first.getSessionId(), "task-a")
                            .isEmpty());
            var scoped = view.listTaskRecordsForMaintenance("parent");
            assertEquals(
                    1, scoped.size(), "a bound view must not duplicate or expose other scopes");
            assertEquals(first.getUserId(), scoped.iterator().next().context().getUserId());
            assertEquals(first.getSessionId(), scoped.iterator().next().context().getSessionId());
        }
        try (var restored = harness(scope)) {
            var manager = restored.getWorkspaceManager();
            assertEquals(2, manager.listTaskRecordsForMaintenance("parent").size());
            repository(manager)
                    .sweepOrphanedTasks(
                            Duration.ofMinutes(10),
                            Duration.ofHours(1),
                            Instant.now().plus(Duration.ofMinutes(11)));
            assertEquals(
                    TaskStatus.FAILED,
                    manager.readTaskRecord(first, "parent", first.getSessionId(), "task-a")
                            .orElseThrow()
                            .getStatus());
            assertEquals(
                    TaskStatus.FAILED,
                    manager.readTaskRecord(second, "parent", second.getSessionId(), "task-b")
                            .orElseThrow()
                            .getStatus());
        }
    }

    @Test
    void oldHostFileStillParticipatesInMaintenance() throws Exception {
        var context = context("user-a", "session-a");
        var manager = new WorkspaceManager(workspace);
        write(manager, context, "task-a");
        java.nio.file.Files.setLastModifiedTime(
                workspace.resolve("agents/parent/tasks/session-a.json"),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        assertTrue(manager.listAllTaskRecords(context, "parent", Duration.ofMinutes(25)).isEmpty());
        var restored = new WorkspaceManager(workspace);
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
}
