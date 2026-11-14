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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolInvocationOutcomeTest {
    private Toolkit toolkit(Function<ToolCallParam, Mono<ToolResultBlock>> invocation) {
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("effect")
                                .description("fixture")
                                .inputSchema(Map.of("type", "object"))) {
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return invocation.apply(param);
                    }
                });
        return toolkit;
    }

    private ToolCallParam call(boolean propagate) {
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id("call")
                                .name("effect")
                                .input(Map.of())
                                .content("{}")
                                .build())
                .propagateErrors(propagate)
                .build();
    }

    @Test
    void preservesTheOriginalFailureWithoutRetryWhenRequested() {
        var failure = new IllegalStateException("effect completed, reply lost");
        var calls = new AtomicInteger();
        var toolkit =
                toolkit(
                        param -> {
                            calls.incrementAndGet();
                            assertTrue(param.isPropagateErrors());
                            return Mono.error(failure);
                        });
        var copy = ToolCallParam.builder(call(true)).build();
        StepVerifier.create(toolkit.callTool(copy))
                .expectErrorMatches(error -> error == failure)
                .verify(Duration.ofSeconds(2));
        assertEquals(1, calls.get());
        assertFalse(call(false).isPropagateErrors());
    }

    @Test
    void annotatedSynchronousReactiveAndFutureFailuresRemainUncertain() {
        var effects = new Effects();
        var toolkit = new Toolkit();
        toolkit.registerTool(effects);
        for (String name : List.of("sync", "reactive", "future")) {
            var param =
                    ToolCallParam.builder(call(true))
                            .toolUseBlock(
                                    ToolUseBlock.builder()
                                            .id(name)
                                            .name(name)
                                            .input(Map.of())
                                            .content("{}")
                                            .build())
                            .build();
            StepVerifier.create(toolkit.callTool(param))
                    .expectErrorMatches(error -> error == effects.failure)
                    .verify(Duration.ofSeconds(2));
        }
        assertEquals(3, effects.calls.get());
    }

    static class Effects {
        final IllegalStateException failure =
                new IllegalStateException("effect completed, reply lost");
        final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "sync fixture")
        public String sync() {
            calls.incrementAndGet();
            throw failure;
        }

        @Tool(description = "reactive fixture")
        public Mono<String> reactive() {
            calls.incrementAndGet();
            return Mono.error(failure);
        }

        @Tool(description = "future fixture")
        public CompletableFuture<String> future() {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Test
    void defaultInvocationStillConvertsFailuresAndEmptyCompletionToErrorResults() {
        var failed = toolkit(param -> Mono.error(new IllegalStateException("failed")));
        assertEquals(ToolResultState.ERROR, failed.callTool(call(false)).block().getState());
        var empty = toolkit(param -> Mono.empty());
        assertEquals(ToolResultState.ERROR, empty.callTool(call(false)).block().getState());
        StepVerifier.create(empty.callTool(call(true))).verifyComplete();
    }

    @Test
    void explicitToolErrorsAndSuspensionRemainResults() {
        var error = ToolResultBlock.error("rejected by tool");
        ToolResultBlock returned = toolkit(param -> Mono.just(error)).callTool(call(true)).block();
        assertEquals(error.getState(), returned.getState());
        assertEquals(error.getOutput(), returned.getOutput());
        var suspended =
                toolkit(param -> Mono.just(ToolResultBlock.suspended(param.getToolUseBlock())));
        assertTrue(suspended.callTool(call(true)).block().isSuspended());
    }
}
