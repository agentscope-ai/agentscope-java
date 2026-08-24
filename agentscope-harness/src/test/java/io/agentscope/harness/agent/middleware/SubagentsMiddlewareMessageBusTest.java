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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SubagentsMiddlewareMessageBusTest {

    @Test
    void completionNotificationIncludesSessionTaskProgress() {
        RecordingTaskRepository repo = new RecordingTaskRepository();
        RecordingMessageBus bus = new RecordingMessageBus();
        RuntimeContext rc =
                RuntimeContext.builder().userId("user-1").sessionId("session-1").build();

        repo.tasksBySession.put(
                "session-1",
                List.of(
                        completedTask("task-1"),
                        runningTask("task-2"),
                        runningTask("task-3"),
                        runningTask("task-4"),
                        runningTask("task-5")));
        repo.tasksBySession.put("other-session", List.of(completedTask("other-task")));

        SubagentsMiddleware middleware =
                new SubagentsMiddleware(
                        List.of(),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null);
        middleware.wireMessageBus(bus, "parent-agent");
        repo.complete(rc, "task-1", "worker", "session-1", "first result");

        String firstHint = bus.hintFor("session-1");
        assertTrue(firstHint.contains("[1/5 tasks terminal]"), firstHint);
        assertTrue(firstHint.contains("first result"), firstHint);
        assertSame(rc, repo.lastRuntimeContext);
        assertEquals("session-1", repo.lastSessionId);

        repo.tasksBySession.put(
                "session-1",
                List.of(
                        completedTask("task-1"),
                        completedTask("task-2"),
                        completedTask("task-3"),
                        completedTask("task-4"),
                        completedTask("task-5")));
        repo.complete(rc, "task-5", "worker", "session-1", "last result");

        String lastHint = bus.hintFor("session-1");
        assertTrue(lastHint.contains("[5/5 tasks terminal]"), lastHint);
        assertTrue(lastHint.contains("last result"), lastHint);
    }

    @Test
    void terminalProgressIncludesCompletedFailedAndCancelledTasks() {
        RecordingTaskRepository repo = new RecordingTaskRepository();
        RecordingMessageBus bus = new RecordingMessageBus();
        repo.tasksBySession.put(
                "session-1",
                List.of(
                        completedTask("completed"),
                        failedTask("failed"),
                        cancelledTask("cancelled"),
                        runningTask("running")));

        new SubagentsMiddleware(
                        List.of(),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null)
                .wireMessageBus(bus, "parent-agent");
        repo.complete(RuntimeContext.empty(), "completed", "worker", "session-1", "result");

        assertTrue(
                bus.hintFor("session-1").contains("[3/4 tasks terminal]"),
                bus.hintFor("session-1"));
    }

    @Test
    void progressFailureFallsBackToOriginalNotificationAndStillWakesSession() {
        RecordingTaskRepository repo = new RecordingTaskRepository();
        RecordingMessageBus bus = new RecordingMessageBus();
        repo.failListTasks = true;

        new SubagentsMiddleware(
                        List.of(),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null)
                .wireMessageBus(bus, "parent-agent");
        repo.complete(
                RuntimeContext.builder().userId("user-1").sessionId("session-1").build(),
                "task-1",
                "worker",
                "session-1",
                "result");

        String hint = bus.hintFor("session-1");
        assertTrue(hint.contains("has completed.\n\nResult:"), hint);
        assertFalse(hint.contains("tasks terminal"), hint);
        Map<String, Object> wakeup = bus.lastPayload("agentscope:wakeups");
        assertNotNull(wakeup);
        assertEquals("user-1", wakeup.get("userId"));
        assertEquals("session-1", wakeup.get("sessionId"));
        assertEquals("parent-agent", wakeup.get("agentId"));
    }

    @Test
    void dynamicMiddlewareUsesSameCompletionNotificationFormat() {
        RecordingTaskRepository repo = new RecordingTaskRepository();
        RecordingMessageBus bus = new RecordingMessageBus();
        repo.tasksBySession.put(
                "session-1", List.of(completedTask("task-1"), runningTask("task-2")));

        DynamicSubagentsMiddleware middleware =
                new DynamicSubagentsMiddleware(
                        List.of(), null, null, null, null, new Object(), repo);
        middleware.wireMessageBus(bus, "parent-agent");
        repo.complete(RuntimeContext.empty(), "task-1", "worker", "session-1", "result");

        String hint = bus.hintFor("session-1");
        assertTrue(hint.contains("[1/2 tasks terminal]"), hint);
        assertTrue(hint.contains("result"), hint);
    }

    private static BackgroundTask completedTask(String taskId) {
        return new BackgroundTask(taskId, "worker", CompletableFuture.completedFuture("result"));
    }

    private static BackgroundTask runningTask(String taskId) {
        return new BackgroundTask(taskId, "worker", new CompletableFuture<>());
    }

    private static BackgroundTask failedTask(String taskId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("failed"));
        return new BackgroundTask(taskId, "worker", future);
    }

    private static BackgroundTask cancelledTask(String taskId) {
        BackgroundTask task = runningTask(taskId);
        task.cancel(false);
        return task;
    }

    private static final class RecordingTaskRepository implements TaskRepository {

        private final Map<String, Collection<BackgroundTask>> tasksBySession = new HashMap<>();
        private TaskCompletionCallback callback;
        private RuntimeContext lastRuntimeContext;
        private String lastSessionId;
        private boolean failListTasks;

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
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            lastRuntimeContext = rc;
            lastSessionId = sessionId;
            if (failListTasks) {
                throw new IllegalStateException("task store unavailable");
            }
            return tasksBySession.getOrDefault(sessionId, List.of()).stream()
                    .filter(task -> filter == null || task.getTaskStatus() == filter)
                    .toList();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }

        @Override
        public void setCompletionCallback(TaskCompletionCallback callback) {
            this.callback = callback;
        }

        void complete(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                String result) {
            assertNotNull(callback, "completion callback must be wired");
            callback.onCompleted(rc, taskId, subAgentId, sessionId, result);
        }
    }

    private static final class RecordingMessageBus implements MessageBus {

        private final Map<String, List<Map<String, Object>>> pushes = new HashMap<>();

        @Override
        public Mono<String> queuePush(String key, Map<String, Object> payload) {
            pushes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(payload);
            return Mono.just(Integer.toString(pushes.get(key).size()));
        }

        @Override
        public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> queueDelete(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> queuePeek(String key) {
            return Mono.just(pushes.containsKey(key));
        }

        @Override
        public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
            return Mono.just("1");
        }

        @Override
        public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> logTrim(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> publish(String key, Map<String, Object> payload) {
            return Mono.empty();
        }

        @Override
        public Flux<Map<String, Object>> subscribe(String key) {
            return Flux.empty();
        }

        String hintFor(String sessionId) {
            Object hint = lastPayload("agentscope:inbox:" + sessionId).get("hint");
            return hint != null ? hint.toString() : "";
        }

        Map<String, Object> lastPayload(String key) {
            List<Map<String, Object>> payloads = pushes.get(key);
            assertNotNull(payloads, "expected a payload for " + key);
            return payloads.get(payloads.size() - 1);
        }
    }
}
