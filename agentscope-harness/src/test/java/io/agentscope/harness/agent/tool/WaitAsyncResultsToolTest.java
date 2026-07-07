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

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class WaitAsyncResultsToolTest {

    @Test
    void noTasksReturnsEarly() throws Exception {
        WaitAsyncResultsTool tool =
                new WaitAsyncResultsTool(new EmptyMessageBus(), new EmptyTaskRepository());
        RuntimeContext context = RuntimeContext.builder().sessionId("session-1").build();

        String result =
                assertTimeoutPreemptively(
                        Duration.ofMillis(500), () -> tool.waitForResults(2, context));

        assertTrue(result.contains("No async tasks are currently running"), result);
    }

    @Test
    void runningAsyncToolStillWaits() throws Exception {
        WaitAsyncResultsTool tool =
                new WaitAsyncResultsTool(
                        new EmptyMessageBus(),
                        new EmptyTaskRepository(),
                        new RunningAsyncToolRegistry());
        RuntimeContext context = RuntimeContext.builder().sessionId("session-1").build();

        String result =
                assertTimeoutPreemptively(
                        Duration.ofMillis(1500), () -> tool.waitForResults(1, context));

        assertTrue(result.contains("Timeout after 1s"), result);
    }

    private static final class EmptyMessageBus implements MessageBus {

        @Override
        public Mono<String> queuePush(String key, Map<String, Object> payload) {
            return Mono.just("id");
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
            return Mono.just(false);
        }

        @Override
        public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
            return Mono.just("id");
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

        @Override
        public void close() {}
    }

    private static final class EmptyTaskRepository implements TaskRepository {

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
            return null;
        }

        @Override
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {}

        @Override
        public void clear() {}

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }
    }

    private static final class RunningAsyncToolRegistry implements AsyncToolRegistry {

        @Override
        public Mono<Void> register(AsyncToolRecord record) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> complete(String id, String result) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> fail(String id, String error) {
            return Mono.empty();
        }

        @Override
        public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
            return Mono.just(
                    List.of(
                            new AsyncToolRecord(
                                    "async-1",
                                    sessionId,
                                    "slow_tool",
                                    "call-1",
                                    AsyncToolRecord.RUNNING,
                                    Instant.now().minusSeconds(1))));
        }

        @Override
        public Mono<Void> markTimeout(String id) {
            return Mono.empty();
        }
    }
}
