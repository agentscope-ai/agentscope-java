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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class WorkspaceTaskRepositoryNamespaceTest {
    @TempDir Path workspace;

    enum TaskRoute {
        COMPOSITE,
        OUTER_ROUTED,
        INNER_ROUTED,
        EXACT,
        DEFAULT
    }

    static NamespaceFactory nestedNamespaces(IsolationScope scope) {
        var factory = scope.toNamespaceFactory();
        return rc -> {
            var namespace = factory.getNamespace(rc);
            return namespace.isEmpty() ? List.of("_default") : namespace;
        };
    }

    static AbstractFilesystem nestedFilesystem(
            AbstractFilesystem shared, AbstractFilesystem remote, TaskRoute route) {
        return nestedFilesystem(shared, remote, route, false);
    }

    static AbstractFilesystem nestedFilesystem(
            AbstractFilesystem shared,
            AbstractFilesystem remote,
            TaskRoute route,
            boolean canonicalPrefixes) {
        String agentsPrefix = canonicalPrefixes ? "/agents/" : "agents/";
        String tasksPrefix = canonicalPrefixes ? "/parent/tasks/" : "parent/tasks/";
        var inner = new CompositeFilesystem(shared, Map.of(tasksPrefix, remote));
        return switch (route) {
            case COMPOSITE -> new CompositeFilesystem(shared, Map.of(agentsPrefix, inner));
            case OUTER_ROUTED ->
                    new RoutedSandboxFilesystem(
                            new SandboxBackedFilesystem(), Map.of(agentsPrefix, inner));
            case INNER_ROUTED ->
                    new CompositeFilesystem(
                            shared,
                            Map.of(
                                    agentsPrefix,
                                    new RoutedSandboxFilesystem(
                                            new SandboxBackedFilesystem(),
                                            Map.of(tasksPrefix, remote))));
            case EXACT ->
                    new CompositeFilesystem(
                            shared, Map.of("agents/parent/tasks/same-session.json", remote));
            case DEFAULT ->
                    new CompositeFilesystem(
                            new CompositeFilesystem(remote, Map.of("unused/", shared)),
                            Map.of("other/", shared));
        };
    }

    static String remoteTaskKey(TaskRoute route) {
        return switch (route) {
            case EXACT -> "/agents/parent/tasks/same-session.json";
            case DEFAULT -> "agents/parent/tasks/same-session.json";
            default -> "/same-session.json";
        };
    }

    @ParameterizedTest
    @CsvSource({
        "COMPOSITE, USER", "COMPOSITE, SESSION",
        "OUTER_ROUTED, USER", "OUTER_ROUTED, SESSION",
        "INNER_ROUTED, USER", "INNER_ROUTED, SESSION",
        "EXACT, USER", "EXACT, SESSION",
        "DEFAULT, USER", "DEFAULT, SESSION"
    })
    void nestedRoutesKeepForeignHandlesAndCancellationIsolated(
            TaskRoute route, IsolationScope scope) {
        var namespaces = nestedNamespaces(scope);
        var store = new InMemoryStore();
        var remote = new RemoteFilesystem(store, namespaces);
        var shared = new LocalFilesystem(workspace, true, 10);
        var fs = nestedFilesystem(shared, remote, route);
        var owner =
                RuntimeContext.builder().userId("owner-user").sessionId("owner-session").build();
        var foreign =
                RuntimeContext.builder()
                        .userId("foreign-user")
                        .sessionId(
                                scope == IsolationScope.USER ? "owner-session" : "foreign-session")
                        .build();
        var expected = List.of(scope == IsolationScope.USER ? "owner-user" : "owner-session");
        var future = new CompletableFuture<String>();
        try (var manager = new WorkspaceManager(workspace, fs, null, namespaces)) {
            var repository = WorkspaceTaskRepository.forTests(manager, "parent");
            try {
                var handle =
                        repository.putTask(
                                owner,
                                "task",
                                "child",
                                "same-session",
                                new TaskRunSpec.AdoptedTaskRunSpec(future));
                var key = remoteTaskKey(route);
                assertTrue(remote.read(owner, key, 0, 0).fileData().content().contains("task"));
                assertTrue(
                        store.get(expected, key)
                                .value()
                                .get("content")
                                .toString()
                                .contains("task"));
                assertTrue(
                        manager.readTaskRecord(foreign, "parent", "same-session", "task")
                                .isEmpty());
                var foreignHandle = repository.getTask(foreign, "same-session", "task");
                boolean cancelled = repository.cancelTask(foreign, "same-session", "task");
                assertAll(
                        () -> assertEquals(expected, remote.storageNamespace(owner)),
                        () ->
                                assertEquals(
                                        expected,
                                        manager.taskStorageNamespace(
                                                owner, "parent", "same-session")),
                        () ->
                                assertNull(
                                        foreignHandle,
                                        "isolated persisted storage must not be bypassed"),
                        () -> assertFalse(cancelled, "foreign caller has no task"),
                        () -> assertFalse(future.isDone(), "owner future must remain active"),
                        () ->
                                assertSame(
                                        handle, repository.getTask(owner, "same-session", "task")));
            } finally {
                future.complete("done");
                repository.shutdown();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"USER", "SESSION"})
    void nestedRoutesUseInnermostBindingRatherThanOuterCaller(IsolationScope scope) {
        var namespaces = nestedNamespaces(scope);
        var store = new InMemoryStore();
        var remote = new RemoteFilesystem(store, namespaces);
        var shared = new LocalFilesystem(workspace, true, 10);
        var inner =
                RuntimeContext.builder().userId("inner-user").sessionId("inner-session").build();
        var outer =
                RuntimeContext.builder().userId("outer-user").sessionId("outer-session").build();
        var fs =
                new BakedContextFilesystem(
                        nestedFilesystem(
                                shared,
                                new BakedContextFilesystem(remote, inner),
                                TaskRoute.COMPOSITE),
                        outer);
        var expected = List.of(scope == IsolationScope.USER ? "inner-user" : "inner-session");
        var future = new CompletableFuture<String>();
        try (var manager = new WorkspaceManager(workspace, fs, null, namespaces)) {
            var repository = WorkspaceTaskRepository.forTests(manager, "parent");
            try {
                var handle =
                        repository.putTask(
                                context("caller"),
                                "task",
                                "child",
                                "same-session",
                                new TaskRunSpec.AdoptedTaskRunSpec(future));
                assertTrue(
                        store.get(expected, "/same-session.json")
                                .value()
                                .get("content")
                                .toString()
                                .contains("task"));
                assertEquals(
                        expected,
                        manager.taskStorageNamespace(context("caller"), "parent", "same-session"));
                // Different callers of the same bound view intentionally share the inner identity.
                assertSame(
                        handle,
                        repository.getTask(context("other-caller"), "same-session", "task"));
                assertSame(handle, repository.getTask(null, "same-session", "task"));
                assertTrue(repository.cancelTask(context("other-caller"), "same-session", "task"));
                assertTrue(future.isCancelled());
            } finally {
                future.complete("done");
                repository.shutdown();
            }
        }
    }

    @Test
    void nestedUserRoutesKeepEmptyContextInLegalAnonymousNamespace() {
        var namespaces = nestedNamespaces(IsolationScope.USER);
        var store = new InMemoryStore();
        var remote = new RemoteFilesystem(store, namespaces);
        var fs =
                nestedFilesystem(
                        new LocalFilesystem(workspace, true, 10), remote, TaskRoute.COMPOSITE);
        try (var manager = new WorkspaceManager(workspace, fs, null, namespaces)) {
            var repository = WorkspaceTaskRepository.forTests(manager, "parent");
            try {
                var handle =
                        repository.putTask(
                                RuntimeContext.empty(),
                                "task",
                                "child",
                                "same-session",
                                new TaskRunSpec.AdoptedTaskRunSpec(
                                        CompletableFuture.completedFuture("anonymous")));
                assertTrue(
                        store.get(List.of("_default"), "/same-session.json")
                                .value()
                                .get("content")
                                .toString()
                                .contains("anonymous"));
                assertEquals(
                        List.of("_default"),
                        manager.taskStorageNamespace(null, "parent", "same-session"));
                assertSame(handle, repository.getTask(null, "same-session", "task"));
                assertNull(repository.getTask(context("authenticated"), "same-session", "task"));
            } finally {
                repository.shutdown();
            }
        }
    }

    private WorkspaceManager manager() {
        return manager(IsolationScope.USER);
    }

    private WorkspaceManager manager(IsolationScope scope) {
        var namespaces = scope.toNamespaceFactory();
        var filesystem =
                new RemoteFilesystemSpec(new InMemoryStore())
                        .isolationScope(scope)
                        .toFilesystem(workspace, "parent", namespaces);
        return new WorkspaceManager(workspace, filesystem, null, namespaces);
    }

    private WorkspaceManager manager(NamespaceFactory namespaces) {
        return new WorkspaceManager(
                workspace, new RemoteFilesystem(new InMemoryStore(), namespaces), null, namespaces);
    }

    private RuntimeContext context(String user) {
        return RuntimeContext.builder().userId(user).sessionId("same-session").build();
    }

    @Test
    void anotherUserMustNotReadLocalTaskResult() {
        var manager = manager();
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        try {
            repository.putTask(
                    context("a"),
                    "task-a",
                    "child",
                    "same-session",
                    new TaskRunSpec.AdoptedTaskRunSpec(
                            CompletableFuture.completedFuture("a-private-result")));
            assertTrue(
                    manager.readTaskRecord(context("b"), "parent", "same-session", "task-a")
                            .isEmpty());
            assertNull(
                    repository.getTask(context("b"), "same-session", "task-a"),
                    "A local handle must not bypass USER workspace isolation");
        } finally {
            repository.shutdown();
        }
    }

    @Test
    void anotherUserMustNotCancelLocalTask() {
        var manager = manager();
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        var future = new CompletableFuture<String>();
        try {
            repository.putTask(
                    context("a"),
                    "task-a",
                    "child",
                    "same-session",
                    new TaskRunSpec.AdoptedTaskRunSpec(future));
            assertTrue(
                    manager.readTaskRecord(context("b"), "parent", "same-session", "task-a")
                            .isEmpty());
            boolean cancelled = repository.cancelTask(context("b"), "same-session", "task-a");
            assertAll(
                    () -> assertFalse(cancelled, "b has no task in this workspace"),
                    () -> assertFalse(future.isCancelled(), "a's task must remain active"));
        } finally {
            future.complete("done");
            repository.shutdown();
        }
    }

    @Test
    void sameTaskIdsKeepSeparateHandlesAndCancellation() {
        var manager = manager();
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        var first = new CompletableFuture<String>();
        var second = new CompletableFuture<String>();
        try {
            var a =
                    repository.putTask(
                            context("a"),
                            "task",
                            "child",
                            "same-session",
                            new TaskRunSpec.AdoptedTaskRunSpec(first));
            var b =
                    repository.putTask(
                            context("b"),
                            "task",
                            "child",
                            "same-session",
                            new TaskRunSpec.AdoptedTaskRunSpec(second));
            assertSame(a, repository.getTask(context("a"), "same-session", "task"));
            assertSame(b, repository.getTask(context("b"), "same-session", "task"));
            assertEquals(
                    List.of(a),
                    List.copyOf(repository.listTasks(context("a"), "same-session", null)));
            assertEquals(
                    List.of(b),
                    List.copyOf(repository.listTasks(context("b"), "same-session", null)));
            assertTrue(repository.cancelTask(context("a"), "same-session", "task"));
            assertTrue(first.isCancelled());
            assertFalse(second.isCancelled());
            second.complete("b-result");
            assertEquals("b-result", b.getResult());
        } finally {
            first.complete("done");
            second.complete("done");
            repository.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"SESSION", "AGENT", "GLOBAL"})
    void intentionallySharedNamespacesStillUseTheSameHandle(IsolationScope scope) {
        var repository = WorkspaceTaskRepository.forTests(manager(scope), "parent");
        var future = new CompletableFuture<String>();
        try {
            var handle =
                    repository.putTask(
                            context("a"),
                            "task",
                            "child",
                            "same-session",
                            new TaskRunSpec.AdoptedTaskRunSpec(future));
            assertSame(handle, repository.getTask(context("b"), "same-session", "task"));
            assertTrue(repository.cancelTask(context("b"), "same-session", "task"));
            assertTrue(future.isCancelled());
        } finally {
            future.complete("done");
            repository.shutdown();
        }
    }

    @Test
    void customNamespacesOverrideUserIdentity() {
        var repository =
                WorkspaceTaskRepository.forTests(
                        manager(rc -> List.of((String) rc.get("tenant"))), "parent");
        var a = context("same-user");
        var b = context("same-user");
        var shared = context("different-user");
        a.put("tenant", "tenant-a");
        b.put("tenant", "tenant-b");
        shared.put("tenant", "tenant-a");
        try {
            var handle =
                    repository.putTask(
                            a,
                            "task",
                            "child",
                            "same-session",
                            new TaskRunSpec.AdoptedTaskRunSpec(
                                    CompletableFuture.completedFuture("result")));
            assertNull(repository.getTask(b, "same-session", "task"));
            assertSame(handle, repository.getTask(shared, "same-session", "task"));
        } finally {
            repository.shutdown();
        }
    }

    @Test
    void heartbeatUpdatesBothUsersWithMatchingTaskIds() {
        var manager = manager();
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        var first = new CompletableFuture<String>();
        var second = new CompletableFuture<String>();
        var old = Instant.now().minus(Duration.ofHours(1));
        try {
            repository.putTask(
                    context("a"),
                    "task",
                    "child",
                    "same-session",
                    new TaskRunSpec.AdoptedTaskRunSpec(first));
            repository.putTask(
                    context("b"),
                    "task",
                    "child",
                    "same-session",
                    new TaskRunSpec.AdoptedTaskRunSpec(second));
            for (String user : List.of("a", "b")) {
                var record =
                        manager.readTaskRecord(context(user), "parent", "same-session", "task")
                                .orElseThrow();
                record.setLastUpdatedAt(old);
                manager.writeTaskRecord(context(user), "parent", "same-session", record);
            }
            repository.heartbeat();
            for (String user : List.of("a", "b")) {
                assertTrue(
                        manager.readTaskRecord(context(user), "parent", "same-session", "task")
                                .orElseThrow()
                                .getLastUpdatedAt()
                                .isAfter(old));
            }
        } finally {
            first.complete("done");
            second.complete("done");
            repository.shutdown();
        }
    }

    @Test
    void anotherUsersLiveHandleDoesNotHideAnOrphan() {
        var manager = manager();
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        var future = new CompletableFuture<String>();
        try {
            repository.putTask(
                    context("a"),
                    "task",
                    "child",
                    "same-session",
                    new TaskRunSpec.AdoptedTaskRunSpec(future));
            var orphan = new TaskRecord("task", "child", "parent", "same-session", null);
            orphan.setStatus(TaskStatus.RUNNING);
            manager.writeTaskRecord(context("b"), "parent", "same-session", orphan);
            repository.sweepOrphanedTasks(
                    Duration.ofMinutes(10),
                    Duration.ofHours(1),
                    Instant.now().plus(Duration.ofMinutes(11)));
            assertEquals(
                    TaskStatus.FAILED,
                    manager.readTaskRecord(context("b"), "parent", "same-session", "task")
                            .orElseThrow()
                            .getStatus());
            assertEquals(
                    TaskStatus.RUNNING,
                    repository.getTask(context("a"), "same-session", "task").getTaskStatus());
        } finally {
            future.complete("done");
            repository.shutdown();
        }
    }

    @Test
    void recoveredRemoteTasksKeepTheirNamespace() throws Exception {
        var manager = manager();
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        var transport = mock(RemoteSubagentTransport.class);
        when(transport.getStatus(any(), eq("task")))
                .thenReturn(new RemoteTaskStatus("success", null));
        when(transport.waitForResult(any(), eq("task"), anyLong()))
                .thenAnswer(call -> ((RemoteTarget) call.getArgument(0)).baseUrl());
        repository.setTransport(transport);
        try {
            for (String user : List.of("a", "b")) {
                var record = new TaskRecord("task", "child", "parent", "same-session", null);
                record.setStatus(TaskStatus.RUNNING);
                record.setTransportType(AgentProtocolTransport.TYPE);
                record.setRemoteBaseUrl("https://" + user + ".example.test");
                manager.writeTaskRecord(context(user), "parent", "same-session", record);
            }
            var a = repository.getTask(context("a"), "same-session", "task");
            var b = repository.getTask(context("b"), "same-session", "task");
            assertTrue(a.waitForCompletion(5000));
            assertTrue(b.waitForCompletion(5000));
            assertEquals("https://a.example.test", a.getResult());
            assertEquals("https://b.example.test", b.getResult());
            assertSame(a, repository.getTask(context("a"), "same-session", "task"));
            assertSame(b, repository.getTask(context("b"), "same-session", "task"));
        } finally {
            repository.shutdown();
        }
    }

    @Test
    void anonymousCallsCanStillRetrieveTheirOwnTask() {
        var repository = WorkspaceTaskRepository.forTests(manager(), "parent");
        try {
            var handle =
                    repository.putTask(
                            null,
                            "task",
                            "child",
                            "same-session",
                            new TaskRunSpec.AdoptedTaskRunSpec(
                                    CompletableFuture.completedFuture("result")));
            assertSame(handle, repository.getTask(RuntimeContext.empty(), "same-session", "task"));
            assertNull(repository.getTask(context("a"), "same-session", "task"));
        } finally {
            repository.shutdown();
        }
    }

    @Test
    void remoteBackendIsIsolatedWithoutAManagerNamespaceFactory() {
        var manager = new WorkspaceManager(workspace, manager().getFilesystem(), null);
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        var future = new CompletableFuture<String>();
        try {
            repository.putTask(
                    context("a"),
                    "task",
                    "child",
                    "same-session",
                    new TaskRunSpec.AdoptedTaskRunSpec(future));
            assertNull(repository.getTask(context("b"), "same-session", "task"));
            assertFalse(repository.cancelTask(context("b"), "same-session", "task"));
            assertFalse(future.isCancelled());
        } finally {
            future.complete("done");
            repository.shutdown();
        }
    }

    @Test
    void anonymousSessionNameDoesNotCollideWithAuthenticatedUser() {
        var repository = WorkspaceTaskRepository.forTests(manager(), "parent");
        var authenticated =
                RuntimeContext.builder().userId("same-session").sessionId("same-session").build();
        var anonymous = context(null);
        var future = new CompletableFuture<String>();
        try {
            repository.putTask(
                    authenticated,
                    "task",
                    "child",
                    "same-session",
                    new TaskRunSpec.AdoptedTaskRunSpec(future));
            assertNull(repository.getTask(anonymous, "same-session", "task"));
            assertFalse(repository.cancelTask(anonymous, "same-session", "task"));
            assertFalse(future.isCancelled());
        } finally {
            future.complete("done");
            repository.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"USER", "SESSION"})
    void realHarnessWorkspaceForUsesActualRemoteTaskNamespace(IsolationScope scope) {
        try (var agent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(mock(Model.class))
                        .workspace(workspace)
                        .filesystem(
                                new RemoteFilesystemSpec(new InMemoryStore()).isolationScope(scope))
                        .stateStore(
                                mock(
                                        AgentStateStore.class,
                                        org.mockito.AdditionalAnswers.delegatesTo(
                                                new InMemoryAgentStateStore())))
                        .disableMemoryHooks()
                        .disableSubagents()
                        .build()) {
            var owner = RuntimeContext.builder().userId("用户 one").sessionId("session one").build();
            var view = agent.workspaceFor(owner.getUserId(), owner.getSessionId());
            var expected =
                    agent.getWorkspaceManager()
                            .taskStorageNamespace(owner, "parent", owner.getSessionId());
            assertEquals(
                    expected,
                    view.taskStorageNamespace(context("stranger"), "parent", owner.getSessionId()));
            var repository = WorkspaceTaskRepository.forTests(view, "parent");
            try {
                var handle =
                        repository.putTask(
                                context("stranger"),
                                "task",
                                "child",
                                owner.getSessionId(),
                                new TaskRunSpec.AdoptedTaskRunSpec(
                                        CompletableFuture.completedFuture("private-result")));
                assertSame(handle, repository.getTask(null, owner.getSessionId(), "task"));
                assertTrue(
                        agent.workspaceFor("other", "other-session")
                                .readTaskRecord(owner, "parent", owner.getSessionId(), "task")
                                .isEmpty());
            } finally {
                repository.shutdown();
            }
        }
    }

    @Test
    void realHarnessSharedLocalWorkspaceForHasNoTaskNamespace() {
        try (var agent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(mock(Model.class))
                        .workspace(workspace)
                        .filesystem(
                                new LocalFilesystemSpec()
                                        .project(workspace)
                                        .sharedLocalWorkspace(true))
                        .disableMemoryHooks()
                        .disableSubagents()
                        .build()) {
            assertEquals(
                    List.of(),
                    agent.workspaceFor("user-a", "same-session")
                            .taskStorageNamespace(context("b"), "parent", "same-session"));
        }
    }

    @Test
    void realHarnessSandboxWorkspaceForRetainsActualHostTaskStorage() {
        try (var agent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(mock(Model.class))
                        .workspace(workspace)
                        .filesystem(new InMemorySandboxFilesystemSpec())
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .disableSubagents()
                        .build()) {
            var view = agent.workspaceFor("user-a", "same-session");
            assertEquals(
                    List.of(), view.taskStorageNamespace(context("b"), "parent", "same-session"));
            var repository = WorkspaceTaskRepository.forTests(view, "parent");
            try {
                repository.putTask(
                        context("b"),
                        "task",
                        "child",
                        "same-session",
                        new TaskRunSpec.AdoptedTaskRunSpec(
                                CompletableFuture.completedFuture("host-result")));
                assertTrue(
                        java.nio.file.Files.isRegularFile(
                                workspace.resolve("agents/parent/tasks/same-session.json")));
                assertEquals(
                        "host-result",
                        agent.workspaceFor("user-b", "other-session")
                                .readTaskRecord(null, "parent", "same-session", "task")
                                .orElseThrow()
                                .getResult());
            } finally {
                repository.shutdown();
            }
        }
    }

    @Test
    void sharedLocalBackendPreservesLiveHandlesDuringMaintenance() {
        var namespaces = IsolationScope.USER.toNamespaceFactory();
        var filesystem =
                new LocalFilesystemSpec()
                        .project(workspace)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, namespaces);
        var manager = new WorkspaceManager(workspace, filesystem, null, namespaces);
        var repository = WorkspaceTaskRepository.forTests(manager, "parent");
        var future = new CompletableFuture<String>();
        try {
            var handle =
                    repository.putTask(
                            context("a"),
                            "task",
                            "child",
                            "same-session",
                            new TaskRunSpec.AdoptedTaskRunSpec(future));
            assertSame(handle, repository.getTask(context("b"), "same-session", "task"));
            repository.sweepOrphanedTasks(
                    Duration.ofMinutes(10),
                    Duration.ofHours(1),
                    Instant.now().plus(Duration.ofMinutes(11)));
            assertEquals(
                    TaskStatus.RUNNING,
                    manager.readTaskRecord(context("a"), "parent", "same-session", "task")
                            .orElseThrow()
                            .getStatus());
            assertTrue(repository.cancelTask(context("b"), "same-session", "task"));
            assertTrue(future.isCancelled());
        } finally {
            future.complete("done");
            repository.shutdown();
        }
    }
}
