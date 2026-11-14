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
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkspaceTaskRepositoryNamespaceTest {
    @TempDir Path workspace;

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
