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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests operator-controlled subagent timeout resolution: a per-subagent
 * {@link SubagentDeclaration#getTimeoutSeconds()} or a per-call
 * {@link AgentSpawnTool#CTX_TIMEOUT_SECONDS} overrides the LLM's {@code timeout_seconds} argument,
 * so long-running subagents can be bounded by the application rather than the model.
 */
@DisplayName("AgentSpawnTool operator timeout (declaration / CTX_TIMEOUT_SECONDS)")
class AgentSpawnToolDeclarationTimeoutTest {

    private static final RuntimeContext EMPTY = RuntimeContext.empty();

    private static Optional<SubagentDeclaration> declWithTimeout(Integer seconds) {
        return Optional.of(
                SubagentDeclaration.builder()
                        .name("sub")
                        .description("long-running subagent")
                        .timeoutSeconds(seconds)
                        .build());
    }

    @Test
    @DisplayName("Declaration timeout overrides the LLM timeout_seconds")
    void declarationOverridesLlmValue() {
        assertEquals(
                120_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY, declWithTimeout(120)),
                "declaration timeout must win over the LLM's 5s");
    }

    @Test
    @DisplayName("Declaration timeout overrides an LLM async request (timeout_seconds=0)")
    void declarationOverridesAsyncRequest() {
        assertEquals(
                90_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY, declWithTimeout(90)),
                "a configured timeout means wait synchronously, overriding async 0");
    }

    @Test
    @DisplayName("Declaration timeout is clamped to the maximum")
    void declarationClampedToMax() {
        assertEquals(
                600_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY, declWithTimeout(9999)),
                "declaration timeout above 600s must clamp");
    }

    @Test
    @DisplayName("Non-positive declaration timeout is ignored, deferring to the LLM value")
    void nonPositiveDeclarationIgnored() {
        assertEquals(
                5_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY, declWithTimeout(0)),
                "timeout<=0 is unset; fall back to the LLM's 5s");
        assertEquals(
                0L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY, declWithTimeout(-1)),
                "unset declaration keeps async 0");
    }

    @Test
    @DisplayName("Null declaration timeout leaves LLM behavior unchanged")
    void nullDeclarationLeavesLlmBehavior() {
        assertEquals(
                0L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY, declWithTimeout(null)),
                "no configured timeout keeps async 0");
        assertEquals(
                7_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(7, EMPTY, Optional.empty()),
                "no declaration keeps the LLM's 7s");
    }

    @Test
    @DisplayName("CTX_TIMEOUT_SECONDS overrides both the declaration and the LLM value")
    void contextOverridesDeclaration() {
        RuntimeContext ctx =
                RuntimeContext.builder().put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, 200).build();
        assertEquals(
                200_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, ctx, declWithTimeout(120)),
                "per-call context timeout takes precedence over the declaration");
    }

    @Test
    @DisplayName("CTX_TIMEOUT_SECONDS accepts a numeric string and overrides async 0")
    void contextAcceptsStringAndOverridesAsync() {
        RuntimeContext ctx =
                RuntimeContext.builder().put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, "150").build();
        assertEquals(150_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, Optional.empty()));
    }

    @Test
    @DisplayName("Force-sync absolute override still wins over declaration timeout")
    void forceSyncOverrideBeatsDeclaration() {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 45)
                        .build();
        assertEquals(
                45_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, ctx, declWithTimeout(120)),
                "force-sync absolute override retains top priority");
    }

    @Test
    @DisplayName("Two-arg overload is unchanged (no declaration): async 0 stays async")
    void twoArgOverloadUnchanged() {
        assertEquals(0L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY));
        assertEquals(5_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "invalid", " ", "2147483648"})
    void unsetOrInvalidContextFallsThroughToDeclaration(String value) {
        RuntimeContext ctx =
                RuntimeContext.builder().put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, value).build();
        assertEquals(
                120_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, declWithTimeout(120)));
        assertEquals(0L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, Optional.empty()));
    }

    @Test
    void contextNumbersAreClampedAndNullContextFallsBack() {
        RuntimeContext ctx =
                RuntimeContext.builder().put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, 9999L).build();
        assertEquals(
                600_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, declWithTimeout(120)));
        assertEquals(
                120_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, null, declWithTimeout(120)));
        assertEquals(
                30_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(null, null, Optional.empty()));
    }

    @Test
    void forceSyncPrecedenceIncludesGeneralContextAndDefaultForZeroHardOverride() {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, 200)
                        .build();
        assertEquals(
                200_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, declWithTimeout(120)));
        ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 45);
        assertEquals(
                45_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, declWithTimeout(120)));
        ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 0);
        assertEquals(
                30_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, declWithTimeout(120)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void operatorWaitAppliesToSpawnSendAndPersistedReuse(boolean contextOverride) {
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("sub")
                        .description("subagent")
                        .persistSession(true)
                        .timeoutSeconds(contextOverride ? null : 5)
                        .build();
        ReActAgent child = ReActAgent.builder().name("sub").model(new MockModel("done")).build();
        TaskRepository repository = mock(TaskRepository.class);
        AgentSpawnTool tool = toolFor(child, declaration, repository);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("parent").userId("user").build();
        if (contextOverride) {
            ctx.put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, 5);
        }

        String first =
                tool.agentSpawn(ctx, null, "sub", "first", "same", 0, null)
                        .block(Duration.ofSeconds(20));
        assertNotNull(first);
        assertTrue(first.contains("status: ok"), first);
        String key =
                first.lines()
                        .filter(line -> line.startsWith("agent_key: "))
                        .findFirst()
                        .orElseThrow()
                        .substring("agent_key: ".length());
        String reused =
                tool.agentSpawn(ctx, null, "sub", "second", "same", 0, null)
                        .block(Duration.ofSeconds(20));
        assertNotNull(reused);
        assertTrue(reused.contains("agent_key: " + key), reused);
        assertTrue(reused.contains("status: ok"), reused);
        String sent =
                tool.agentSend(ctx, null, key, null, "third", 0).block(Duration.ofSeconds(20));
        assertNotNull(sent);
        assertTrue(sent.contains("status: ok"), sent);
        verify(repository, never()).putTask(any(), anyString(), anyString(), anyString(), any());
        if (contextOverride) {
            RuntimeContext nextCall =
                    RuntimeContext.builder().sessionId("parent").userId("user").build();
            String background =
                    tool.agentSend(nextCall, null, key, null, "background", 0)
                            .block(Duration.ofSeconds(20));
            assertNotNull(background);
            assertTrue(background.contains("status: accepted"), background);
            verify(repository).putTask(any(), anyString(), anyString(), anyString(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void declarationWaitPromotesOrInterruptsAccordingToForceSync(boolean forceSync)
            throws Exception {
        CompletableFuture<ChatResponse> response = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        MockModel model =
                new MockModel("unused") {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Mono.fromFuture(response, true)
                                .doOnCancel(() -> cancelled.set(true))
                                .flux();
                    }
                };
        ReActAgent child = ReActAgent.builder().name("sub").model(model).build();
        TaskRepository repository = mock(TaskRepository.class);
        AgentSpawnTool tool = toolFor(child, declWithTimeout(1).orElseThrow(), repository);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("parent")
                        .userId("user")
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, forceSync)
                        .build();
        try {
            String result =
                    tool.agentSpawn(ctx, null, "sub", "wait", null, 0, null)
                            .block(Duration.ofSeconds(20));
            assertNotNull(result);
            if (forceSync) {
                assertTrue(result.contains("status: timeout"), result);
                assertFalse(result.contains("task_id:"), result);
                assertTrue(cancelled.get(), "Force-sync timeout must cancel the model execution");
                verify(repository, never())
                        .putTask(any(), anyString(), anyString(), anyString(), any());
            } else {
                assertTrue(result.contains("status: timeout_promoted"), result);
                assertTrue(result.contains("task_id:"), result);
                ArgumentCaptor<TaskRunSpec> spec = ArgumentCaptor.forClass(TaskRunSpec.class);
                verify(repository)
                        .putTask(any(), anyString(), anyString(), anyString(), spec.capture());
                TaskRunSpec.AdoptedTaskRunSpec adopted =
                        assertInstanceOf(TaskRunSpec.AdoptedTaskRunSpec.class, spec.getValue());
                response.complete(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("done").build()))
                                .build());
                assertEquals("done", adopted.future().get(20, TimeUnit.SECONDS));
                assertFalse(cancelled.get(), "Promotion must leave the execution running");
            }
        } finally {
            response.complete(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    private static AgentSpawnTool toolFor(
            ReActAgent child, SubagentDeclaration declaration, TaskRepository repository) {
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("sub", "subagent", rc -> child, declaration)),
                        null);
        return new AgentSpawnTool(manager, repository, 0);
    }
}
