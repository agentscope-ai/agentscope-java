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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class ToolMethodInvokerVoidTest {

    private final VoidTools tools = new VoidTools();
    private final ToolMethodInvoker invoker =
            new ToolMethodInvoker(new DefaultToolResultConverter());

    @ParameterizedTest
    @ValueSource(strings = {"syncVoid", "boxedVoid", "monoVoid", "futureVoid"})
    void completedVoidToolsReturnCompletionText(String methodName) throws Exception {
        complete(methodName);
        StepVerifier.create(invoke(methodName, null))
                .assertNext(
                        result ->
                                assertEquals(
                                        "(completed with no output)",
                                        ToolTestUtils.extractContent(result)))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"syncVoid", "boxedVoid", "monoVoid", "futureVoid"})
    void customConverterReceivesVoidCompletion(String methodName) throws Exception {
        complete(methodName);
        AtomicInteger conversions = new AtomicInteger();
        Type expectedType = methodName.equals("syncVoid") ? Void.TYPE : Void.class;
        ToolResultConverter converter =
                (value, returnType) -> {
                    assertNull(value);
                    assertEquals(expectedType, returnType);
                    conversions.incrementAndGet();
                    return ToolResultBlock.success("Saved");
                };

        StepVerifier.create(invoke(methodName, converter))
                .assertNext(result -> assertEquals("Saved", ToolTestUtils.extractContent(result)))
                .verifyComplete();
        assertEquals(1, conversions.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"monoVoid", "futureVoid"})
    void convertsOnlyAfterAsyncVoidCompletion(String methodName) throws Exception {
        AtomicInteger conversions = new AtomicInteger();
        ToolResultConverter converter =
                (value, returnType) -> {
                    conversions.incrementAndGet();
                    return ToolResultBlock.success("Saved");
                };

        StepVerifier.create(invoke(methodName, converter))
                .then(() -> assertEquals(0, conversions.get()))
                .then(() -> complete(methodName))
                .assertNext(result -> assertEquals("Saved", ToolTestUtils.extractContent(result)))
                .verifyComplete();
        assertEquals(1, conversions.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"monoVoid", "futureVoid"})
    void failedVoidCompletionDoesNotInvokeConverter(String methodName) throws Exception {
        AtomicInteger conversions = new AtomicInteger();
        ToolResultConverter converter =
                (value, returnType) -> {
                    conversions.incrementAndGet();
                    return ToolResultBlock.success("Saved");
                };

        StepVerifier.create(invoke(methodName, converter))
                .then(() -> fail(methodName))
                .assertNext(
                        result -> {
                            assertEquals(ToolResultState.ERROR, result.getState());
                            assertTrue(
                                    ToolTestUtils.extractContent(result).contains("save failed"));
                        })
                .verifyComplete();
        assertEquals(0, conversions.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"monoVoid", "futureVoid"})
    void cancelledVoidCompletionDoesNotInvokeConverter(String methodName) throws Exception {
        AtomicInteger conversions = new AtomicInteger();
        ToolResultConverter converter =
                (value, returnType) -> {
                    conversions.incrementAndGet();
                    return ToolResultBlock.success("Saved");
                };

        Disposable subscription = invoke(methodName, converter).subscribe();
        subscription.dispose();

        assertEquals(0, conversions.get());
        if (methodName.equals("futureVoid")) {
            assertTrue(tools.future.isCancelled());
        } else {
            assertTrue(tools.monoCancelled);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"emptyMono", "nullFuture"})
    void nonVoidEmptyCompletionKeepsExistingContract(String methodName) throws Exception {
        StepVerifier.create(invoke(methodName, null)).verifyComplete();
    }

    @Test
    void toolkitKeepsVoidResultsAssociatedWithCalls() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        List<ToolUseBlock> calls =
                List.of("syncVoid", "boxedVoid", "monoVoid", "futureVoid").stream()
                        .map(name -> new ToolUseBlock("call-" + name, name, Map.of()))
                        .toList();

        StepVerifier.create(toolkit.callTools(calls, null, null, null))
                .then(() -> complete("monoVoid"))
                .then(() -> complete("futureVoid"))
                .assertNext(
                        results -> {
                            assertEquals(calls.size(), results.size());
                            for (int i = 0; i < calls.size(); i++) {
                                assertEquals(calls.get(i).getId(), results.get(i).getId());
                                assertEquals(calls.get(i).getName(), results.get(i).getName());
                                assertEquals(
                                        "(completed with no output)",
                                        ToolTestUtils.extractContent(results.get(i)));
                            }
                        })
                .verifyComplete();
    }

    private Mono<ToolResultBlock> invoke(String name, ToolResultConverter converter)
            throws NoSuchMethodException {
        return invoker.invokeAsync(
                tools,
                VoidTools.class.getMethod(name),
                ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("call-" + name, name, Map.of()))
                        .build(),
                converter);
    }

    private void complete(String name) {
        if (name.equals("futureVoid")) {
            tools.future.complete(null);
        } else {
            tools.completion.tryEmitEmpty();
        }
    }

    private void fail(String name) {
        if (name.equals("futureVoid")) {
            tools.future.completeExceptionally(new IllegalStateException("save failed"));
        } else {
            tools.completion.tryEmitError(new IllegalStateException("save failed"));
        }
    }

    static class VoidTools {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final Sinks.Empty<Void> completion = Sinks.empty();
        boolean monoCancelled;

        @Tool(description = "Save without a return value")
        public void syncVoid() {}

        @Tool(description = "Save with a boxed void return value")
        public Void boxedVoid() {
            return null;
        }

        @Tool(description = "Save through Mono")
        public Mono<Void> monoVoid() {
            return completion.asMono().doOnCancel(() -> monoCancelled = true);
        }

        @Tool(description = "Save through CompletableFuture")
        public CompletableFuture<Void> futureVoid() {
            return future;
        }

        public Mono<String> emptyMono() {
            return Mono.empty();
        }

        public CompletableFuture<String> nullFuture() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
