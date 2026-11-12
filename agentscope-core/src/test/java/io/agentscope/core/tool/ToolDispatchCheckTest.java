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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.session.SessionRecorder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@Tag("unit")
class ToolDispatchCheckTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void timeoutRetryChecksAgainWithoutRestartingTheTool() {
        AtomicBoolean failed = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Toolkit toolkit = guarded(failed);
        toolkit.registerAgentTool(
                tool(
                        "pending",
                        () -> {
                            calls.incrementAndGet();
                            failed.set(true);
                            return Mono.never();
                        }));
        ExecutionConfig retry =
                ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(1))
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();
        List<ToolResultBlock> results =
                toolkit.callTools(List.of(call("id", "pending")), retry, null, null).block(TIMEOUT);
        assertEquals(1, calls.get());
        assertEquals(ToolResultState.ERROR, results.get(0).getState());
        assertEquals("id", results.get(0).getId());
    }

    @Test
    void rejectsExternalDispatchBeforeSuspensionAndLeavesDefaultBehaviorIntact() {
        Toolkit toolkit = guarded(new AtomicBoolean(true));
        ToolBase external =
                new ToolBase(
                        ToolBase.builder()
                                .name("external")
                                .description("external")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                                .externalTool(true)) {};
        toolkit.registerAgentTool(external);
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(call("id", "external")).build();
        ToolResultBlock denied = toolkit.callTool(param).block(TIMEOUT);
        assertEquals(ToolResultState.ERROR, denied.getState());
        assertEquals("id", denied.getId());
        assertFalse(denied.isSuspended());
        Toolkit defaults = new Toolkit();
        defaults.registerAgentTool(external);
        assertTrue(defaults.callTool(param).block(TIMEOUT).isSuspended());
    }

    private static Toolkit guarded(AtomicBoolean failed) {
        return new Toolkit(
                ToolkitConfig.builder()
                        .beforeToolCall(
                                param -> {
                                    if (failed.get())
                                        throw new IllegalStateException("run stopped");
                                })
                        .build());
    }

    private static ToolUseBlock call(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of()).content("{}").build();
    }

    private static ToolBase tool(String name, Supplier<Mono<ToolResultBlock>> execution) {
        return new ToolBase(
                ToolBase.builder()
                        .name(name)
                        .description(name)
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .concurrencySafe(false)) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return execution.get();
            }
        };
    }

    @Test
    void stopsQueuedToolAfterAsynchronousFailureAndKeepsPairing() {
        AtomicBoolean failed = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Sinks.One<ToolResultBlock> first = Sinks.one();
        Toolkit toolkit = guarded(failed);
        toolkit.registerAgentTool(
                tool("first", () -> first.asMono().doOnNext(r -> failed.set(true))));
        toolkit.registerAgentTool(
                tool(
                        "later",
                        () -> {
                            calls.incrementAndGet();
                            return Mono.just(ToolResultBlock.success("done"));
                        }));
        StepVerifier.create(
                        toolkit.callTools(
                                List.of(call("a", "first"), call("b", "later")), null, null, null))
                .then(() -> first.tryEmitValue(ToolResultBlock.error("infrastructure")))
                .assertNext(
                        results -> {
                            assertEquals(
                                    List.of("a", "b"),
                                    results.stream().map(ToolResultBlock::getId).toList());
                            assertEquals("later", results.get(1).getName());
                            assertTrue(results.get(1).getState() == ToolResultState.ERROR);
                            assertEquals(0, calls.get());
                        })
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void checksEverySubscriptionAndCopiedLateRegisteredTools() {
        AtomicBoolean failed = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Toolkit copy = guarded(failed).copy();
        copy.registerAgentTool(
                tool(
                        "late",
                        () -> {
                            calls.incrementAndGet();
                            return Mono.just(ToolResultBlock.success("ok"));
                        }));
        Mono<List<ToolResultBlock>> execution =
                copy.callTools(List.of(call("id", "late")), null, null, null);
        assertFalse(execution.block(TIMEOUT).get(0).getState() == ToolResultState.ERROR);
        failed.set(true);
        assertTrue(execution.block(TIMEOUT).get(0).getState() == ToolResultState.ERROR);
        assertEquals(1, calls.get());
        Toolkit independent = guarded(new AtomicBoolean());
        independent.registerAgentTool(tool("late", () -> Mono.just(ToolResultBlock.success("ok"))));
        assertFalse(
                independent
                                .callTools(List.of(call("id", "late")), null, null, null)
                                .block(TIMEOUT)
                                .get(0)
                                .getState()
                        == ToolResultState.ERROR);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAfterDispatchPersistenceWait(boolean withInterceptor) {
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Sinks.Empty<Void> persisted = Sinks.empty();
        SessionRecorder recorder = mock(SessionRecorder.class);
        when(recorder.record(anyString(), any())).thenReturn(Mono.empty());
        when(recorder.record(eq("tool/dispatch"), any()))
                .thenReturn(persisted.asMono().doOnSubscribe(subscription -> stopped.set(true)));
        when(recorder.actionId(anyString())).thenReturn("action");
        RuntimeContext context =
                RuntimeContext.builder().put(SessionRecorder.CONTEXT_KEY, recorder).build();
        ToolkitConfig.Builder config =
                ToolkitConfig.builder()
                        .beforeToolCall(
                                param -> {
                                    if (stopped.get())
                                        throw new IllegalStateException("run stopped");
                                });
        if (withInterceptor) {
            config.interceptor(
                    (invocation, next) -> {
                        assertFalse(stopped.get());
                        return next.apply(invocation);
                    });
        }
        Toolkit toolkit = new Toolkit(config.build());
        toolkit.registerTool(
                tool(
                        "waiting",
                        () -> {
                            calls.incrementAndGet();
                            return Mono.just(ToolResultBlock.success("done"));
                        }));
        StepVerifier.create(
                        toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("id", "waiting"))
                                        .runtimeContext(context)
                                        .build()))
                .then(
                        () -> {
                            assertEquals(0, calls.get());
                            assertEquals(Sinks.EmitResult.OK, persisted.tryEmitEmpty());
                        })
                .assertNext(
                        result -> {
                            assertEquals(ToolResultState.ERROR, result.getState());
                            assertEquals(0, calls.get());
                        })
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void checkReceivesInterceptorRewrittenParameters() {
        AtomicReference<ToolCallParam> checked = new AtomicReference<>();
        AtomicReference<ToolCallParam> invoked = new AtomicReference<>();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .interceptor(
                                        (invocation, next) ->
                                                next.apply(
                                                        invocation.withInput(
                                                                Map.of("value", "rewritten"))))
                                .beforeToolCall(checked::set)
                                .build());
        toolkit.registerTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("rewritten")
                                .description("rewritten")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        invoked.set(param);
                        return Mono.just(ToolResultBlock.success("done"));
                    }
                });
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("id", "rewritten"))
                                        .build())
                        .block(TIMEOUT);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertNotNull(checked.get());
        assertEquals(Map.of("value", "rewritten"), checked.get().getInput());
        assertSame(checked.get(), invoked.get());
    }

    @Test
    void stopDoesNotCancelAnAlreadyStartedCall() {
        AtomicBoolean stopped = new AtomicBoolean();
        Sinks.One<ToolResultBlock> result = Sinks.one();
        Toolkit toolkit = guarded(stopped);
        toolkit.registerTool(
                tool(
                        "started",
                        () -> {
                            stopped.set(true);
                            return result.asMono();
                        }));
        StepVerifier.create(
                        toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("id", "started"))
                                        .build()))
                .then(
                        () -> {
                            assertEquals(
                                    Sinks.EmitResult.OK,
                                    result.tryEmitValue(ToolResultBlock.success("done")));
                        })
                .assertNext(value -> assertEquals(ToolResultState.SUCCESS, value.getState()))
                .expectComplete()
                .verify(TIMEOUT);
    }
}
