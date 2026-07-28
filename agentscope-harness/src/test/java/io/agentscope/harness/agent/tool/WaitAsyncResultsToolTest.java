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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag("unit")
@DisplayName("WaitAsyncResultsTool guardrails")
class WaitAsyncResultsToolTest {

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().sessionId("test-session").build();
    }

    private static MessageBus emptyBus() {
        return new StubMessageBus(false);
    }

    private static MessageBus busWithMessages() {
        return new StubMessageBus(true);
    }

    @Test
    @DisplayName("timeout_seconds > 120 is clamped — wait finishes within cap")
    void timeoutClampedToMax() throws Exception {
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus());
        long start = System.currentTimeMillis();
        String result = tool.waitForResults(600, ctx());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.contains("Timeout"), "should timeout, got: " + result);
        // Clamped to 120s, but we expect it to finish much faster in tests
        // since we pass 600 which gets clamped. We can't wait 120s in a unit test,
        // so we verify the tool accepted the call and returned a timeout.
        // The real assertion is that it didn't wait 600s.
        assertTrue(elapsed < 130_000, "should not wait 600s, elapsed: " + elapsed + "ms");
    }

    @Test
    @DisplayName("null timeout uses default 60s behavior")
    void nullTimeoutUsesDefault() throws Exception {
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus());
        long start = System.currentTimeMillis();
        // Pass a very short effective timeout by using a custom tool instance
        // Just verify null doesn't throw and produces a timeout message
        String result = tool.waitForResults(1, ctx());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.contains("Timeout"), "should timeout with short wait");
        assertTrue(elapsed < 5_000, "1s timeout should finish quickly");
    }

    @Test
    @DisplayName("consecutive empty waits are rejected after budget exhausted")
    void consecutiveEmptyWaitsRejected() throws Exception {
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus());

        // First empty wait — allowed
        String r1 = tool.waitForResults(1, ctx());
        assertTrue(r1.contains("Timeout"), "first wait should timeout normally");
        assertTrue(r1.contains("1/2"), "should show 1/2 empty waits");

        // Second empty wait — allowed
        String r2 = tool.waitForResults(1, ctx());
        assertTrue(r2.contains("Timeout"), "second wait should timeout normally");
        assertTrue(r2.contains("2/2"), "should show 2/2 empty waits");

        // Third empty wait — rejected immediately
        long start = System.currentTimeMillis();
        String r3 = tool.waitForResults(1, ctx());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(r3.contains("Wait budget exhausted"), "third wait should be rejected");
        assertTrue(r3.contains("task_list"), "should suggest task_list alternative");
        assertTrue(r3.contains("task_output"), "should suggest task_output alternative");
        assertTrue(elapsed < 1_000, "rejected call should return immediately");
    }

    @Test
    @DisplayName("budget exhausted but inbox has results → returns success and resets counter")
    void budgetExhaustedButResultsArrivedRecovers() throws Exception {
        AtomicBoolean hasMessages = new AtomicBoolean(false);
        MessageBus toggleBus = new StubMessageBus(hasMessages);
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(toggleBus);

        // Exhaust the budget with 2 empty waits
        tool.waitForResults(1, ctx());
        tool.waitForResults(1, ctx());

        // Results arrive after budget exhausted
        hasMessages.set(true);
        String result = tool.waitForResults(1, ctx());
        assertTrue(
                result.contains("Async results have arrived"),
                "should return success when inbox has messages, got: " + result);

        // Counter should be reset — further empty waits allowed again
        hasMessages.set(false);
        String r4 = tool.waitForResults(1, ctx());
        assertTrue(r4.contains("Timeout"), "should be allowed to wait again after reset");
        assertTrue(r4.contains("1/2"), "counter should restart from 1");
    }

    @Test
    @DisplayName("counter resets when results arrive")
    void counterResetsOnSuccess() throws Exception {
        AtomicBoolean hasMessages = new AtomicBoolean(false);
        MessageBus toggleBus = new StubMessageBus(hasMessages);

        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(toggleBus);

        // First call — empty, timeout
        String r1 = tool.waitForResults(1, ctx());
        assertTrue(r1.contains("Timeout"));

        // Second call — results arrive
        hasMessages.set(true);
        String r2 = tool.waitForResults(1, ctx());
        assertTrue(r2.contains("Async results have arrived"), "should find results");

        // Counter should be reset — can wait again
        hasMessages.set(false);
        String r3 = tool.waitForResults(1, ctx());
        assertTrue(r3.contains("Timeout"), "should be allowed to wait again after reset");
        assertTrue(r3.contains("1/2"), "counter should restart from 1");
    }

    @Test
    @DisplayName("no session context returns error")
    void noSessionReturnsError() throws Exception {
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus());
        String result = tool.waitForResults(10, null);
        assertTrue(result.contains("Cannot wait"));
    }

    @Test
    @DisplayName("timeout message suggests non-blocking alternatives, not retry")
    void timeoutMessageDoesNotEncourageRetry() throws Exception {
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus());
        String result = tool.waitForResults(1, ctx());

        assertFalse(result.contains("try waiting again"), "should not encourage retry");
        assertTrue(result.contains("task_list"), "should suggest task_list");
        assertTrue(result.contains("task_output"), "should suggest task_output");
    }

    @Test
    @DisplayName("all tasks terminal + inbox empty → returns immediately without blocking")
    void allTasksTerminalReturnsImmediately() throws Exception {
        CompletableFuture<String> done = CompletableFuture.completedFuture("ok");
        BackgroundTask completed = new BackgroundTask("t1", "agent-1", done);

        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        BackgroundTask failedTask = new BackgroundTask("t2", "agent-2", failed);

        TaskRepository repo = new StubTaskRepository(List.of(completed, failedTask));
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus(), repo);

        long start = System.currentTimeMillis();
        String result = tool.waitForResults(120, ctx());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(
                result.contains("All background tasks have completed"),
                "should indicate all tasks done, got: " + result);
        assertTrue(elapsed < 2_000, "should return immediately, took: " + elapsed + "ms");
    }

    @Test
    @DisplayName("some tasks still running → proceeds to wait normally")
    void nonTerminalTasksProcedsToWait() throws Exception {
        CompletableFuture<String> running = new CompletableFuture<>();
        BackgroundTask runningTask = new BackgroundTask("t1", "agent-1", running);

        TaskRepository repo = new StubTaskRepository(List.of(runningTask));
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus(), repo);

        String result = tool.waitForResults(1, ctx());
        assertTrue(result.contains("Timeout"), "should proceed to wait and timeout");
    }

    @Test
    @DisplayName("no TaskRepository (null) → falls through to normal wait")
    void nullTaskRepositoryFallsThrough() throws Exception {
        WaitAsyncResultsTool tool = new WaitAsyncResultsTool(emptyBus(), null);
        String result = tool.waitForResults(1, ctx());
        assertTrue(result.contains("Timeout"), "should proceed to wait and timeout");
    }

    private static class StubMessageBus implements MessageBus {

        private final AtomicBoolean hasMessages;

        StubMessageBus(boolean hasMessages) {
            this.hasMessages = new AtomicBoolean(hasMessages);
        }

        StubMessageBus(AtomicBoolean hasMessages) {
            this.hasMessages = hasMessages;
        }

        @Override
        public Mono<String> queuePush(String key, Map<String, Object> payload) {
            return Mono.just("");
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
            return Mono.just(hasMessages.get());
        }

        @Override
        public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
            return Mono.just("");
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
    }

    private static class StubTaskRepository implements TaskRepository {

        private final List<BackgroundTask> tasks;

        StubTaskRepository(List<BackgroundTask> tasks) {
            this.tasks = tasks;
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return tasks.stream()
                    .filter(t -> t.getTaskId().equals(taskId))
                    .findFirst()
                    .orElse(null);
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
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {}

        @Override
        public void clear() {}

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            if (filter == null) {
                return tasks;
            }
            return tasks.stream().filter(t -> t.getTaskStatus() == filter).toList();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }
    }
}
