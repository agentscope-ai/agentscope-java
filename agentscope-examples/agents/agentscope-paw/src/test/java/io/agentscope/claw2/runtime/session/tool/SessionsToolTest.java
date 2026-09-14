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
package io.agentscope.claw2.runtime.session.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.claw2.runtime.session.CommandLane;
import io.agentscope.claw2.runtime.session.SendResult;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SpawnResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionsToolTest {
    @TempDir Path root;

    @Test
    void spawnedTaskIsImmediatelyQueryableOnlyFromParentAndDoesNotWakeDefaultChat()
            throws Exception {
        var manager = mock(SessionAgentManager.class);
        var context =
                RuntimeContext.builder()
                        .sessionId("assigned-session")
                        .put("agentTaskManaged", true)
                        .build();
        when(manager.requesterKeyForSession("assigned-session")).thenReturn("assigned-session");
        when(manager.registerSession("researcher", null, "assigned-session", 0))
                .thenReturn(
                        new SpawnResult(
                                "run", "child-key", "child-id", "", "researcher", "ok", null));
        var release = new CountDownLatch(1);
        when(manager.execute(
                        eq("child-key"),
                        eq("Research EV"),
                        eq(0L),
                        eq(false),
                        eq(CommandLane.SUBAGENT),
                        same(context)))
                .thenAnswer(
                        invocation -> {
                            release.await(5, TimeUnit.SECONDS);
                            return new SendResult("child-key", "ok", "EV evidence", null);
                        });
        var repo = new WorkspaceTaskRepository(new WorkspaceManager(root), "parent");
        try {
            var tool = new SessionsTool(manager, () -> repo);
            String response =
                    tool.sessionsSpawn(context, "researcher", "Research EV", null, "run", 0);
            String id =
                    response.lines()
                            .filter(l -> l.startsWith("task_id:"))
                            .findFirst()
                            .orElseThrow()
                            .substring(8)
                            .trim();
            var task = repo.getTask(context, "assigned-session", id);
            assertNotNull(task);
            assertNull(repo.getTask(context, "another-session", id));
            assertTrue(new TaskTool(repo).taskOutput(context, id, false, 0L).contains("running"));
            release.countDown();
            assertTrue(task.waitForCompletion(5000));
            assertEquals("EV evidence", task.getResult());
            assertTrue(
                    new TaskTool(repo).taskOutput(context, id, false, 0L).contains("EV evidence"));
            verify(manager, never()).announceCompletion(any(), any(), any());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TaskTool(repo).taskOutput(context, "missing", false, 0L));
        } finally {
            release.countDown();
            repo.shutdown();
        }
    }

    @Test
    void failedChildIsFailedInRepositoryAndCompletionIsPersistedBeforeAnnouncement()
            throws Exception {
        var manager = mock(SessionAgentManager.class);
        var context = RuntimeContext.builder().sessionId("chat-session").build();
        when(manager.requesterKeyForSession("chat-session")).thenReturn("chat-session");
        when(manager.registerSession("researcher", null, "chat-session", 0))
                .thenReturn(
                        new SpawnResult(
                                "run", "child-key", "child-id", "", "researcher", "ok", null));
        when(manager.execute(anyString(), anyString(), anyLong(), eq(false), any(), same(context)))
                .thenReturn(new SendResult("child-key", "error", null, "missing web capability"));
        var repo = new WorkspaceTaskRepository(new WorkspaceManager(root), "parent");
        var announced = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            assertEquals(
                                    TaskStatus.FAILED,
                                    repo.listTasks(context, "chat-session", null)
                                            .iterator()
                                            .next()
                                            .getTaskStatus());
                            assertEquals(
                                    1, repo.findPendingDeliveries(context, "chat-session").size());
                            announced.countDown();
                            return null;
                        })
                .when(manager)
                .announceCompletion(eq("child-key"), isNull(), any(Throwable.class));
        try {
            new SessionsTool(manager, () -> repo)
                    .sessionsSpawn(context, "researcher", "Research EV", null, "run", 0);
            assertTrue(announced.await(5, TimeUnit.SECONDS));
        } finally {
            repo.shutdown();
        }
    }
}
