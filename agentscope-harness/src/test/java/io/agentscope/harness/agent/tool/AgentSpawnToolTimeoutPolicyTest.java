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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteSubagentTransport;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/** Acceptance tests for the application-controlled force-sync timeout policy (#3166). */
class AgentSpawnToolTimeoutPolicyTest {

    @ParameterizedTest
    @MethodSource("numericValues")
    void normalizesContextNumbersWithoutWrapping(Object value, int expected) {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, value)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_MAX_TIMEOUT_SECONDS, value)
                        .build();
        assertEquals(expected, AgentSpawnTool.forceSyncTimeoutSeconds(ctx));
        assertEquals(expected > 0 ? expected : 600, AgentSpawnTool.forceSyncMaxTimeoutSeconds(ctx));
        assertEquals(
                (long) (expected > 0 ? expected : 30) * 1000,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, ctx));
    }

    static Stream<Arguments> numericValues() {
        return Stream.of(
                Arguments.of(" 1800 ", 1800),
                Arguments.of(1800L, 1800),
                Arguments.of(1800.0, 1800),
                Arguments.of((double) (1L << 32) + 100, Integer.MAX_VALUE),
                Arguments.of(1800.9, 1800),
                Arguments.of(new BigDecimal("1800.9"), 1800),
                Arguments.of(
                        BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(100)),
                        Integer.MAX_VALUE),
                Arguments.of(new BigDecimal("18446744073709551716.5"), Integer.MAX_VALUE),
                Arguments.of("18446744073709551716", Integer.MAX_VALUE),
                Arguments.of(Long.MAX_VALUE, Integer.MAX_VALUE),
                Arguments.of(Integer.MAX_VALUE, Integer.MAX_VALUE),
                Arguments.of(BigInteger.ONE.shiftLeft(64).negate(), 0),
                Arguments.of("-18446744073709551616", 0),
                Arguments.of(-1, 0),
                Arguments.of(0.5, 0));
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void invalidConfigurationFallsBack(Object value) {
        RuntimeContext invalidMax =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 1800)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_MAX_TIMEOUT_SECONDS, value)
                        .build();
        assertEquals(600_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(5, invalidMax));
        RuntimeContext invalidOverride =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, value)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_MAX_TIMEOUT_SECONDS, 3600)
                        .build();
        assertEquals(5_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(5, invalidOverride));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                        "",
                        " ",
                        "bad",
                        "1.5",
                        "1800.0",
                        true,
                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY)
                .map(Arguments::of);
    }

    @Test
    void disabledForceSyncIgnoresBothApplicationSettings() {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, false)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 1800)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_MAX_TIMEOUT_SECONDS, 1)
                        .build();
        assertEquals(0L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx));
        assertEquals(30_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(null, ctx));
        assertEquals(600_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(1800, ctx));
    }

    @ParameterizedTest
    @CsvSource({"0,1,1", "-1,1,1", ",1,1", "600,1,1", "1800,3600,600"})
    void ceilingAlsoBoundsModelAndDefaultTimeouts(
            Integer modelTimeout, int ceiling, long expectedSeconds) {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_MAX_TIMEOUT_SECONDS, ceiling)
                        .build();
        assertEquals(
                expectedSeconds * 1000,
                AgentSpawnTool.resolveEffectiveTimeoutMs(modelTimeout, ctx));
    }

    @ParameterizedTest
    @CsvSource({
        "1800,,600",
        "1800,3600,1800",
        "7200,3600,3600",
        "1800,300,300",
        "0,3600,30",
        "-1,3600,30",
        "1800,0,600",
        "1800,-100,600",
        "9223372036854775807,3600,3600"
    })
    void applicationOverrideIsBoundedByConfiguredCeiling(
            long override, Integer ceiling, long expectedSeconds) {
        RuntimeContext.Builder builder =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, override);
        if (ceiling != null) {
            builder.put(AgentSpawnTool.CTX_FORCE_SYNC_MAX_TIMEOUT_SECONDS, ceiling);
        }
        assertEquals(
                expectedSeconds * 1000,
                AgentSpawnTool.resolveEffectiveTimeoutMs(0, builder.build()));
    }

    // Real execution paths use short deadlines; numeric tests above verify the >600s values
    // without making the test suite wait for half an hour.
    @ParameterizedTest(name = "remote={0}, route={1}: configured cap cancels")
    @CsvSource({
        "false,SPAWN",
        "false,REUSE",
        "false,SEND",
        "true,SPAWN",
        "true,REUSE",
        "true,SEND"
    })
    @Timeout(15)
    void configuredCapCancelsWithoutPromotion(boolean remote, String route) throws Exception {
        try (Fixture f = new Fixture(remote, false)) {
            RuntimeContext ctx = context(1800, 1);
            String result = f.invoke(route, ctx, 600);
            assertNotNull(result);
            assertTrue(result.contains("status: timeout\n"), result);
            assertTrue(result.contains("1s sync timeout"), result);
            assertFalse(result.contains("timeout_promoted"), result);
            assertFalse(result.contains("task_id:"), result);
            if (remote) {
                verify(f.repository).cancelTask(eq(ctx), eq("parent"), anyString());
                assertTrue(f.remoteResult.isCancelled());
            } else {
                verify(f.agent, atLeastOnce()).interrupt(any(RuntimeContext.class));
                verify(f.repository, never())
                        .putTask(any(), anyString(), anyString(), anyString(), any());
            }
        }
    }

    @ParameterizedTest(name = "remote={0}, route={1}: application timeout overrides model")
    @CsvSource({
        "false,SPAWN",
        "false,REUSE",
        "false,SEND",
        "true,SPAWN",
        "true,REUSE",
        "true,SEND"
    })
    @Timeout(15)
    void applicationOverrideWaitsPastModelDeadline(boolean remote, String route) throws Exception {
        try (Fixture f = new Fixture(remote, true)) {
            String result = f.invoke(route, context(1800, 3600), 1);
            assertNotNull(result);
            assertTrue(result.contains("status: ok"), result);
            assertTrue(result.contains("finished"), result);
            assertFalse(result.contains("timeout"), result);
            verify(f.repository, never()).cancelTask(any(), anyString(), anyString());
            if (!remote) {
                verify(f.repository, never())
                        .putTask(any(), anyString(), anyString(), anyString(), any());
            }
        }
    }

    private static RuntimeContext context(int override, int ceiling) {
        return RuntimeContext.builder()
                .sessionId("parent")
                .userId("user")
                .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, override)
                .put(AgentSpawnTool.CTX_FORCE_SYNC_MAX_TIMEOUT_SECONDS, ceiling)
                .build();
    }

    private static final class Fixture implements AutoCloseable {
        final ReActAgent agent =
                spy(ReActAgent.builder().name("worker").model(new MockModel("finished")).build());
        final TaskRepository repository = mock(TaskRepository.class);
        final CompletableFuture<String> remoteResult = new CompletableFuture<>();
        final AgentSpawnTool tool;

        Fixture(boolean remote, boolean complete) throws Exception {
            SubagentDeclaration declaration =
                    SubagentDeclaration.builder()
                            .name("worker")
                            .description("worker")
                            .persistSession(true)
                            .url(remote ? "http://unused.invalid" : null)
                            .build();
            DefaultAgentManager manager =
                    spy(
                            new DefaultAgentManager(
                                    List.of(
                                            new SubagentEntry(
                                                    "worker", "worker", rc -> agent, declaration)),
                                    null));
            Msg reply = Msg.builder().content(TextBlock.builder().text("finished").build()).build();
            Mono<Msg> execution =
                    complete ? Mono.delay(Duration.ofMillis(1500)).thenReturn(reply) : Mono.never();
            doReturn(execution)
                    .when(manager)
                    .invokeAgent(any(), anyString(), anyString(), anyString(), any());
            when(repository.putTask(any(), anyString(), anyString(), anyString(), any()))
                    .thenAnswer(
                            call -> {
                                assertTrue(
                                        call.getArgument(4)
                                                instanceof TaskRunSpec.RemoteTaskRunSpec);
                                if (complete) {
                                    CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                                            .execute(() -> remoteResult.complete("finished"));
                                }
                                return new BackgroundTask(
                                        call.getArgument(1), "worker", remoteResult);
                            });
            when(repository.cancelTask(any(), anyString(), anyString()))
                    .thenAnswer(call -> remoteResult.cancel(true));
            RemoteSubagentTransport transport = mock(RemoteSubagentTransport.class);
            when(transport.getStatus(any(), anyString()))
                    .thenReturn(new RemoteTaskStatus("running", null));
            tool = new AgentSpawnTool(manager, repository, 0);
            tool.setRemoteTransport(transport);
        }

        String invoke(String route, RuntimeContext ctx, int modelTimeout) {
            String key = null;
            if (!route.equals("SPAWN")) {
                String created =
                        tool.agentSpawn(ctx, null, "worker", null, "label", 0, null).block();
                assertNotNull(created);
                key =
                        created.lines()
                                .filter(line -> line.startsWith("agent_key: "))
                                .findFirst()
                                .orElseThrow()
                                .substring("agent_key: ".length());
            }
            Mono<String> result =
                    route.equals("SEND")
                            ? tool.agentSend(ctx, null, key, null, "work", modelTimeout)
                            : tool.agentSpawn(
                                    ctx, null, "worker", "work", "label", modelTimeout, null);
            return result.block(Duration.ofSeconds(10));
        }

        @Override
        public void close() {
            remoteResult.cancel(true);
        }
    }
}
