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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Regression test for the <b>timeout-promotion</b> path of {@code
 * AgentSpawnTool.execWithTimeoutPromotion}.
 *
 * <p>The sibling test {@code AgentSpawnToolOrphanCancelTest} covers the orphan-cancel fix; this
 * test pins down the sibling guarantee: when the internal {@code race.orTimeout} fires (because
 * the agent legitimately exceeds {@code timeout_seconds}), the in-flight execution is wrapped as
 * an {@link TaskRunSpec.AdoptedTaskRunSpec} and submitted to the {@link TaskRepository}, so the
 * user can still retrieve the eventual result via {@code task_output}.
 *
 * <p>The orphan-cancel fix adds a {@code doFinally(CANCEL) → interrupt} handler and a
 * {@code sink.onCancel(innerSub::dispose)} hook on the inner Mono. Neither of those should fire on
 * the promotion path (it terminates via {@code sink.success(...)} after a CompletableFuture
 * timeout, not via Reactor cancel). This test asserts both halves of that contract:
 *
 * <ol>
 *   <li>The agent is NOT interrupted — it keeps running and eventually produces a reply.
 *   <li>The {@link TaskRunSpec.AdoptedTaskRunSpec#future()} completes with that reply.
 * </ol>
 *
 * <p>If a future refactor accidentally disposes the inner subscription in the timeout branch (the
 * most plausible regression), this test fails on both halves.
 */
@DisplayName(
        "AgentSpawnTool timeout-promotion: AdoptedTaskRunSpec still works after orphan-cancel fix")
class AgentSpawnToolPromotionTest {

    @Test
    @DisplayName(
            "Inner timeout wraps in-flight execution as AdoptedTaskRunSpec; future completes with"
                    + " reply")
    @Timeout(30)
    void innerTimeoutPromotesToAdoptedTaskRunSpec() throws Exception {
        // ---- slow_tool: sleeps 2s before returning. Forces the 1s inner timeout to fire first.
        // ----
        AgentTool slowTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "slow_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Sleeps 2s, then returns ok.";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                        return Mono.fromRunnable(
                                        () -> {
                                            try {
                                                Thread.sleep(2_000);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(
                                        Mono.just(
                                                ToolResultBlock.of(
                                                        TextBlock.builder().text("ok").build())));
                    }
                };

        Toolkit tk = new Toolkit();
        tk.registerTool(slowTool);

        // ---- Stateful MockModel: 1st call returns tool_call, 2nd+ returns final text "task done".
        // ----
        AtomicInteger modelCall = new AtomicInteger(0);
        Function<List<io.agentscope.core.message.Msg>, List<ChatResponse>> gen =
                messages -> {
                    if (modelCall.incrementAndGet() == 1) {
                        Map<String, Object> args = new HashMap<>();
                        return List.of(
                                ChatResponse.builder()
                                        .id("msg_" + java.util.UUID.randomUUID())
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .name("slow_tool")
                                                                .id("call_1")
                                                                .input(args)
                                                                .content("{}")
                                                                .build()))
                                        .usage(new ChatUsage(8, 15, 23))
                                        .build());
                    }
                    return List.of(
                            ChatResponse.builder()
                                    .id("msg_" + java.util.UUID.randomUUID())
                                    .content(List.of(TextBlock.builder().text("task done").build()))
                                    .usage(new ChatUsage(10, 20, 30))
                                    .build());
                };
        MockModel model = new MockModel(gen);

        ReActAgent agentSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("slow_sub")
                                .sysPrompt("slow sub")
                                .model(model)
                                .toolkit(tk)
                                .build());

        // ---- DefaultAgentManager + AgentSpawnTool with a capturing TaskRepository ----
        SubagentFactory factory = rc -> agentSpy;
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("slow_agent", "Test slow agent", factory)), null);

        AtomicReference<TaskRunSpec> capturedSpec = new AtomicReference<>();
        AtomicReference<String> capturedTaskId = new AtomicReference<>();
        TaskRepository captureRepo = new CapturingTaskRepository(capturedSpec, capturedTaskId);

        AgentSpawnTool tool = new AgentSpawnTool(manager, captureRepo, 0);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();

        // ---- Call agentSpawn with 1s timeout — agent takes ~2s+ to finish naturally ----
        String result =
                tool.agentSpawn(ctx, null, "slow_agent", "go", null, 1, null)
                        .block(Duration.ofSeconds(10));

        // ===== Proof #1: tool returned the promotion message, not an error =====
        assertNotNull(result, "agentSpawn returned null");
        assertTrue(
                result.contains("status: timeout_promoted"),
                "Expected 'status: timeout_promoted' in result, got: " + result);
        assertNotNull(capturedTaskId.get(), "TaskRepository.putTask was never called");
        assertTrue(
                result.contains("task_id: " + capturedTaskId.get()),
                "Result should include the captured task_id. result=" + result);

        // ===== Proof #2: the captured spec is AdoptedTaskRunSpec (not Local/Remote) =====
        TaskRunSpec spec = capturedSpec.get();
        assertNotNull(spec, "Captured TaskRunSpec was null");
        assertTrue(
                spec instanceof TaskRunSpec.AdoptedTaskRunSpec,
                "Expected AdoptedTaskRunSpec but got " + spec.getClass().getSimpleName());

        // ===== Proof #3: the adopted future eventually completes with the agent's reply =====
        // This is the critical assertion: the orphan-cancel fix must NOT dispose the inner
        // subscription when the timeout fires via race.orTimeout (CompletableFuture), otherwise
        // the agent would be killed and this future would never complete.
        CompletableFuture<String> adoptedFuture = ((TaskRunSpec.AdoptedTaskRunSpec) spec).future();
        String finalReply = adoptedFuture.get(15, TimeUnit.SECONDS);
        assertNotNull(finalReply, "Adopted future completed with null");
        assertTrue(
                finalReply.contains("task done"),
                "Adopted future should complete with agent's reply. Got: " + finalReply);

        // ===== Proof #4: interrupt() was never called on the agent =====
        // Promotion is supposed to keep the agent running, not kill it. If the orphan-cancel
        // fix accidentally fires on the promotion path, interrupt() would be invoked here.
        verify(agentSpy, never()).interrupt(any(RuntimeContext.class));
    }

    /** Captures the {@link TaskRunSpec} submitted to {@code putTask} so the test can inspect it. */
    private static final class CapturingTaskRepository implements TaskRepository {
        private final AtomicReference<TaskRunSpec> capturedSpec;
        private final AtomicReference<String> capturedTaskId;

        CapturingTaskRepository(AtomicReference<TaskRunSpec> spec, AtomicReference<String> id) {
            this.capturedSpec = spec;
            this.capturedTaskId = id;
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            capturedSpec.set(spec);
            capturedTaskId.set(taskId);
            return null;
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
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
}
