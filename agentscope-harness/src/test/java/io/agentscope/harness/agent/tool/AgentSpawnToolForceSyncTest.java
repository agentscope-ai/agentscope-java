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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tests for {@link AgentSpawnTool#CTX_FORCE_SYNC}: coerce async to sync, and hard-fail on timeout
 * without promoting to a background {@code task_id}.
 */
@DisplayName("AgentSpawnTool force_sync via RuntimeContext")
class AgentSpawnToolForceSyncTest {

    @Test
    @DisplayName("isForceSync / resolveEffectiveTimeoutMs helpers")
    void helpers() {
        assertFalse(AgentSpawnTool.isForceSync(null));
        assertFalse(AgentSpawnTool.isForceSync(RuntimeContext.empty()));
        assertTrue(
                AgentSpawnTool.isForceSync(
                        RuntimeContext.builder().put(AgentSpawnTool.CTX_FORCE_SYNC, true).build()));
        assertTrue(
                AgentSpawnTool.isForceSync(
                        RuntimeContext.builder()
                                .put(AgentSpawnTool.CTX_FORCE_SYNC, "true")
                                .build()));
        assertFalse(
                AgentSpawnTool.isForceSync(
                        RuntimeContext.builder()
                                .put(AgentSpawnTool.CTX_FORCE_SYNC, false)
                                .build()));

        RuntimeContext noForce = RuntimeContext.empty();
        RuntimeContext forceOnly =
                RuntimeContext.builder().put(AgentSpawnTool.CTX_FORCE_SYNC, true).build();
        RuntimeContext forceWithOverride =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
                        .build();
        RuntimeContext forceWithStringOverride =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, "45")
                        .build();
        RuntimeContext overrideWithoutForce =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
                        .build();

        // Without force-sync, 0 stays async — even if a timeout override is present.
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(0, noForce) == 0L);
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(0, overrideWithoutForce) == 0L);
        // With force-sync, 0 coerces to the 30s default when no override is set.
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(0, forceOnly) == 30_000L);
        // Explicit positive LLM timeouts are preserved without override.
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(5, forceOnly) == 5_000L);
        // App override wins over LLM timeout (including async 0).
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(0, forceWithOverride) == 120_000L);
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(5, forceWithOverride) == 120_000L);
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(5, forceWithStringOverride) == 45_000L);
        // Non-positive override falls back to the default sync timeout.
        RuntimeContext forceBadOverride =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 0)
                        .build();
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(5, forceBadOverride) == 30_000L);
        // Deployer-provided override is honored as-is (no 600s ceiling).
        RuntimeContext forceHuge =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 9999)
                        .build();
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(5, forceHuge) == 9_999_000L);
        // LLM-requested timeouts (no override) remain clamped at 600s.
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(9999, noForce) == 600_000L);
        assertTrue(AgentSpawnTool.resolveEffectiveTimeoutMs(9999, forceOnly) == 600_000L);
    }

    @Test
    @DisplayName("timeout_seconds=0 under force_sync waits synchronously (not background accepted)")
    @Timeout(30)
    void zeroTimeoutCoercedToSync() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("fast_sub")
                        .sysPrompt("fast")
                        .model(new MockModel("done"))
                        .build();
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("fast_agent", "Fast", rc -> agent)), null);
        CapturingTaskRepository repo = new CapturingTaskRepository();
        AgentSpawnTool tool = new AgentSpawnTool(manager, repo, 0);

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .userId("u1")
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .build();

        String result =
                assertText(
                        tool.agentSpawn(ctx, null, "fast_agent", "go", null, 0, null)
                                .block(Duration.ofSeconds(15)),
                        ToolResultState.SUCCESS);

        assertNotNull(result);
        assertTrue(result.contains("status: ok"), "Expected sync ok reply, got: " + result);
        assertFalse(
                result.contains("status: accepted"),
                "Force-sync must not return background accepted. Got: " + result);
        assertTrue(repo.putCount.get() == 0, "No background task should be submitted");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(15)
    void toolkitKeepsDelegationAndOrdinaryToolTimeoutsSeparate(boolean batch) throws Exception {
        Agent child = Mockito.mock(Agent.class);
        Mockito.when(child.getAgentId()).thenReturn("child");
        Mockito.when(child.getName()).thenReturn("child");
        Mockito.when(child.call(Mockito.<List<Msg>>any()))
                .thenReturn(
                        Mono.just(Msg.builder().name("child").textContent("done").build())
                                .delayElement(Duration.ofMillis(200)));
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("slow_agent", "Slow", rc -> child)), null);
        Toolkit toolkit =
                timedToolkit(new AgentSpawnTool(manager, new CapturingTaskRepository(), 0));
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("parent")
                        .userId("user")
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 5)
                        .build();
        for (String name : List.of("agent_spawn", "agent_send")) {
            Map<String, Object> input =
                    "agent_spawn".equals(name)
                            ? Map.of("agent_id", "slow_agent", "task", "go", "label", "slow")
                            : Map.of("label", "slow", "message", "continue");
            String result =
                    assertText(
                            callTool(toolkit, name, input, ctx, batch).block(Duration.ofSeconds(5)),
                            ToolResultState.SUCCESS);
            assertTrue(result.contains("status: ok"), result);
        }

        CountDownLatch ordinaryCancelled = new CountDownLatch(1);
        AgentTool ordinary = Mockito.mock(AgentTool.class);
        Mockito.when(ordinary.getName()).thenReturn("ordinary");
        Mockito.when(ordinary.getDescription()).thenReturn("Ordinary tool");
        Mockito.when(ordinary.getParameters()).thenReturn(Map.of("type", "object"));
        Mockito.when(ordinary.callAsync(Mockito.any()))
                .thenReturn(Mono.<ToolResultBlock>never().doOnCancel(ordinaryCancelled::countDown));
        toolkit.registerTool(ordinary);
        String error =
                assertText(
                        callTool(toolkit, "ordinary", Map.of(), ctx, batch)
                                .block(Duration.ofSeconds(5)),
                        ToolResultState.ERROR);
        assertTrue(error.contains("Tool execution timeout after PT0.05S"), error);
        assertTrue(ordinaryCancelled.await(5, TimeUnit.SECONDS));

        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childCancelled = new CountDownLatch(1);
        Mockito.when(child.call(Mockito.<List<Msg>>any()))
                .thenReturn(
                        Mono.<Msg>never()
                                .doOnSubscribe(ignored -> childStarted.countDown())
                                .doOnCancel(childCancelled::countDown));
        Disposable subscription =
                callTool(
                                toolkit,
                                "agent_send",
                                Map.of("label", "slow", "message", "continue"),
                                ctx,
                                batch)
                        .subscribe();
        try {
            assertTrue(childStarted.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(childCancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("force_sync timeout interrupts agent and does not promote to AdoptedTaskRunSpec")
    @Timeout(30)
    void timeoutHardFailsWithoutPromotion() throws Exception {
        CountDownLatch toolCancelled = new CountDownLatch(1);
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
                                                        TextBlock.builder().text("ok").build())))
                                .doOnCancel(toolCancelled::countDown);
                    }
                };

        Toolkit tk = new Toolkit();
        tk.registerTool(slowTool);

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

        SubagentFactory factory = rc -> agentSpy;
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("slow_agent", "Test slow agent", factory)), null);

        CapturingTaskRepository captureRepo = new CapturingTaskRepository();
        AgentSpawnTool tool = new AgentSpawnTool(manager, captureRepo, 0);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .userId("u1")
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .build();

        String result =
                assertText(
                        callTool(
                                        timedToolkit(tool),
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                "slow_agent",
                                                "task",
                                                "go",
                                                "timeout_seconds",
                                                1),
                                        ctx,
                                        true)
                                .block(Duration.ofSeconds(10)),
                        ToolResultState.ERROR);

        assertNotNull(result, "agentSpawn returned null");
        assertTrue(
                result.contains("status: timeout"),
                "Expected hard 'status: timeout', got: " + result);
        assertFalse(
                result.contains("timeout_promoted"), "Force-sync must not promote. Got: " + result);
        assertFalse(
                result.contains("task_id:"),
                "Force-sync timeout must not return task_id. Got: " + result);
        assertTrue(
                captureRepo.putCount.get() == 0,
                "TaskRepository.putTask must not be called under force_sync timeout");

        assertTrue(
                toolCancelled.await(5, TimeUnit.SECONDS), "Timed-out child tool must be cancelled");
    }

    private static Toolkit timedToolkit(AgentSpawnTool tool) {
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .executionConfig(
                                        ExecutionConfig.builder()
                                                .timeout(Duration.ofMillis(50))
                                                .build())
                                .build());
        toolkit.registerTool(tool);
        return toolkit;
    }

    private static Mono<ToolResultBlock> callTool(
            Toolkit toolkit,
            String name,
            Map<String, Object> input,
            RuntimeContext ctx,
            boolean batch) {
        ToolUseBlock call = ToolUseBlock.builder().id(name).name(name).input(input).build();
        return batch
                ? toolkit.callTools(List.of(call), null, null, ctx).map(results -> results.get(0))
                : toolkit.callTool(
                        ToolCallParam.builder().toolUseBlock(call).runtimeContext(ctx).build());
    }

    private static final class CapturingTaskRepository implements TaskRepository {
        private final AtomicInteger putCount = new AtomicInteger(0);
        private final AtomicReference<TaskRunSpec> capturedSpec = new AtomicReference<>();

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            putCount.incrementAndGet();
            capturedSpec.set(spec);
            return null;
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return null;
        }

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
