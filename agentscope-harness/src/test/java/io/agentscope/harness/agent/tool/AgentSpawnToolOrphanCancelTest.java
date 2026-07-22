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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reproduction / regression test for orphan sub-agent leak in {@code AgentSpawnTool}.
 *
 * <p><b>Scenario</b>: a parent agent invokes {@code agent_spawn} and the parent's subscription to
 * the returned {@link Mono} is cancelled before completion (e.g. by an outer
 * {@code Mono.timeout()}, a user-initiated stop, or upstream Reactor cancel). The inner execution
 * Mono from {@code execLocalSync} is fire-and-forget subscribed via plain {@code .subscribe(...)}
 * inside {@code execWithTimeoutPromotion} — the resulting {@code Disposable} is never captured and
 * never disposed on parent cancel, so the sub-agent keeps running as an orphan.
 *
 * <p>This test mirrors the structure of {@code SubAgentToolTimeoutRetryIntegrationTest} (which
 * covered the equivalent bug in the core {@code SubAgentTool}, fixed in {@code 029cc55e}) but
 * adapted to the harness-side {@code AgentSpawnTool} API:
 *
 * <ul>
 *   <li>{@link DefaultAgentManager} + {@link SubagentEntry} instead of {@code SubAgentProvider}
 *   <li>Direct {@code tool.agentSpawn(...)} call instead of {@code tool.callAsync(param)}
 *   <li>Outer {@code Mono.timeout(2s)} instead of {@code .timeout(3s).retryWhen(1)}
 * </ul>
 *
 * <p>Assertions are written for the <em>fixed</em> state. On current (unfixed) code, the
 * assertions fail and the failure message carries concrete measurements (file lines and model call
 * counts at timeout vs after a wait window) — those numbers form the proof in the GitHub issue.
 */
@DisplayName("AgentSpawnTool parent-cancel: orphan sub-agent must be interrupted")
class AgentSpawnToolOrphanCancelTest {

    private Path tmpFile;

    @AfterEach
    void cleanup() throws Exception {
        if (tmpFile != null) {
            try {
                Files.deleteIfExists(tmpFile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("Sub-agent loop stops after parent timeout cancels the tool subscription")
    @Timeout(30)
    void orphanAgentInterruptedOnParentCancel() throws Exception {
        tmpFile = Files.createTempFile("agentspawn_orphan_", ".log");
        // Start from an empty file so line counts are deterministic.
        Files.writeString(tmpFile, "");

        // ---- slow_tool: writes one line per call. A short sleep per call slows the loop enough
        //      that the outer 2s timeout fires while the agent is still mid-flight (not already
        //      exited via summarizing/max-iters), and the orphan window (post-timeout) shows
        //      measurable progression. ----
        AtomicInteger toolCallCount = new AtomicInteger(0);
        AgentTool slowTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "slow_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Appends one line to a temp file, then returns ok.";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                        return Mono.fromRunnable(
                                        () -> {
                                            toolCallCount.incrementAndGet();
                                            try {
                                                Thread.sleep(150);
                                                Files.writeString(
                                                        tmpFile,
                                                        "line\n",
                                                        StandardOpenOption.APPEND);
                                            } catch (Exception ignored) {
                                                // best-effort write; test asserts on counts, not
                                                // file contents
                                            }
                                        })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(
                                        Mono.just(
                                                ToolResultBlock.of(
                                                        TextBlock.builder().text("ok").build())));
                    }
                };

        Toolkit slowTk = new Toolkit();
        slowTk.registerTool(slowTool);

        // ---- MockModel that emits a UNIQUE tool_call_id on every invocation ----
        // The stock MockModel.withToolCall(...) returns the same id each call, which the
        // ReActAgent dedupes — only the first call executes, then the loop short-circuits to
        // summarizing. Using a generator with a fresh UUID forces every iteration to actually
        // execute slow_tool, so the orphan (pre-fix) keeps writing new lines until interrupted.
        java.util.function.Function<
                        List<io.agentscope.core.message.Msg>,
                        List<io.agentscope.core.model.ChatResponse>>
                gen =
                        messages -> {
                            java.util.Map<String, Object> args = new java.util.HashMap<>();
                            String callId = "call_" + java.util.UUID.randomUUID();
                            io.agentscope.core.model.ChatResponse resp =
                                    io.agentscope.core.model.ChatResponse.builder()
                                            .id("msg_" + java.util.UUID.randomUUID())
                                            .content(
                                                    java.util.List.of(
                                                            io.agentscope.core.message.ToolUseBlock
                                                                    .builder()
                                                                    .name("slow_tool")
                                                                    .id(callId)
                                                                    .input(args)
                                                                    .content(
                                                                            io.agentscope.core.util
                                                                                    .JsonUtils
                                                                                    .getJsonCodec()
                                                                                    .toJson(args))
                                                                    .build()))
                                            .usage(
                                                    new io.agentscope.core.model.ChatUsage(
                                                            8, 15, 23))
                                            .build();
                            return java.util.List.of(resp);
                        };
        MockModel slowModel = new MockModel(gen);

        ReActAgent slowSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("slow_sub")
                                .sysPrompt("slow sub")
                                .model(slowModel)
                                .toolkit(slowTk)
                                .maxIters(50)
                                .build());

        // ---- DefaultAgentManager + AgentSpawnTool ----
        SubagentFactory factory = rc -> slowSpy;
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("slow_agent", "Test slow agent", factory)), null);

        TaskRepository stubRepo = new NoopTaskRepository();
        AgentSpawnTool tool = new AgentSpawnTool(manager, stubRepo, 0);

        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();

        // ---- Trigger parent cancel via outer .timeout(2s) ----
        // The agent has a 30s internal timeout so its inner race.orTimeout will not fire first.
        // The outer Mono.timeout(2s) cancels the Mono<String> returned by agentSpawn — that is
        // exactly the parent-cancel scenario we want to exercise.
        long t0 = System.currentTimeMillis();
        try {
            tool.agentSpawn(ctx, null, "slow_agent", "go", null, 30, null)
                    .timeout(Duration.ofSeconds(2))
                    .block();
        } catch (Exception expected) {
            // Expected: outer timeout fires TimeoutException (may be wrapped by .block()).
            // The exact exception type is not asserted — what matters is that the parent
            // subscription has been cancelled by the time we reach the assertions below.
            System.out.println(
                    "[AgentSpawnToolOrphanCancelTest] outer timeout fired at t="
                            + (System.currentTimeMillis() - t0)
                            + "ms, exception class="
                            + expected.getClass().getSimpleName());
        }

        long linesAtTimeout = Files.readAllLines(tmpFile).size();
        long callsAtTimeout = slowModel.getCallCount();
        System.out.println(
                "[AgentSpawnToolOrphanCancelTest] at timeout: lines="
                        + linesAtTimeout
                        + ", modelCalls="
                        + callsAtTimeout
                        + ", toolCalls="
                        + toolCallCount.get());

        // ---- Wait long enough to see if the orphan kept running ----
        Thread.sleep(4_000);

        long linesAfter = Files.readAllLines(tmpFile).size();
        long callsAfter = slowModel.getCallCount();
        long deltaLines = linesAfter - linesAtTimeout;
        long deltaCalls = callsAfter - callsAtTimeout;
        System.out.println(
                "[AgentSpawnToolOrphanCancelTest] after 4s wait: lines="
                        + linesAfter
                        + " (delta="
                        + deltaLines
                        + "), modelCalls="
                        + callsAfter
                        + " (delta="
                        + deltaCalls
                        + "), toolCalls="
                        + toolCallCount.get());

        // ===== Proof #1: interrupt() was called on the slow agent after parent cancel =====
        // Pre-fix: this fails — interrupt is never called because the inner subscription is never
        // disposed.
        verify(slowSpy, atLeastOnce()).interrupt(any(RuntimeContext.class));

        // ===== Proof #2: file did not keep growing after parent cancel =====
        // Pre-fix: this fails — orphan loop writes many more lines during the 4s window.
        //   We allow a slack of 1 to tolerate a single in-flight tool call that completes before
        //   the interrupt flag is observed.
        assertTrue(
                deltaLines <= 1,
                "Orphan kept writing lines after parent cancel. linesAtTimeout="
                        + linesAtTimeout
                        + ", linesAfter="
                        + linesAfter
                        + ", delta="
                        + deltaLines);

        // ===== Proof #3: model was not called many more times after parent cancel =====
        assertTrue(
                deltaCalls <= 1,
                "Orphan kept invoking the model after parent cancel. callsAtTimeout="
                        + callsAtTimeout
                        + ", callsAfter="
                        + callsAfter
                        + ", delta="
                        + deltaCalls);
    }

    /** Minimal no-op TaskRepository — the bug scenario never reaches promotion, so stubs suffice. */
    private static final class NoopTaskRepository implements TaskRepository {
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
}
