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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A task started by one user must not be visible to, or cancellable by, another user. */
class WorkspaceTaskRepositoryUserIsolationTest {

    @TempDir Path workspace;
    private WorkspaceTaskRepository repo;

    @BeforeEach
    void setUp() {
        var scope = IsolationScope.USER;
        var namespaces = scope.toNamespaceFactory();
        var filesystem =
                new RemoteFilesystemSpec(new InMemoryStore())
                        .isolationScope(scope)
                        .toFilesystem(workspace, "parent", namespaces);
        repo =
                WorkspaceTaskRepository.forTests(
                        new WorkspaceManager(workspace, filesystem, null, namespaces), "parent");
    }

    @AfterEach
    void tearDown() {
        repo.shutdown();
    }

    private static RuntimeContext ctx(String user) {
        return RuntimeContext.builder().userId(user).sessionId("shared-session").build();
    }

    @Test
    void otherUserWithSameSessionIdCannotGetOrCancelRunningTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repo.putTask(
                ctx("user-a"),
                "task-1",
                "child",
                "shared-session",
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            started.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "done";
                        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertNotNull(repo.getTask(ctx("user-a"), "shared-session", "task-1"));
        assertNull(repo.getTask(ctx("user-b"), "shared-session", "task-1"));

        assertFalse(repo.cancelTask(ctx("user-b"), "shared-session", "task-1"));
        BackgroundTask owned = repo.getTask(ctx("user-a"), "shared-session", "task-1");
        assertEquals(TaskStatus.RUNNING, owned.getTaskStatus());

        release.countDown();
    }
}
