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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolkitCallToolIdentityTest {

    @Test
    void stampsSuccessWithoutLosingOutputMetadataOrState() {
        Toolkit toolkit = new Toolkit();
        ToolResultBlock original =
                ToolResultBlock.of(
                                List.of(TextBlock.builder().text("done").build()),
                                Map.of("key", "value"))
                        .withState(ToolResultState.SUCCESS)
                        .withIdAndName("stale-id", "stale-name");
        toolkit.registerAgentTool(tool("test", Mono.just(original)));

        ToolResultBlock result = call(toolkit, "test", "{}");

        assertSame(original.getOutput(), result.getOutput());
        assertEquals(original.getMetadata(), result.getMetadata());
        assertEquals(original.getState(), result.getState());
    }

    @Test
    void stampsToolExecutionError() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool("test", Mono.error(new IllegalStateException("boom"))));

        ToolResultBlock result = call(toolkit, "test", "{}");

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(result.getOutput().get(0) instanceof TextBlock);
    }

    @Test
    void stampsMissingToolError() {
        assertEquals(ToolResultState.ERROR, call(new Toolkit(), "missing", "{}").getState());
    }

    @Test
    void stampsValidationFailure() {
        Toolkit toolkit = new Toolkit();
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("must not run")));
        when(tool.getParameters())
                .thenReturn(Map.of("type", "object", "required", List.of("required")));
        toolkit.registerAgentTool(tool);

        assertEquals(ToolResultState.ERROR, call(toolkit, "test", "{}").getState());
    }

    @Test
    void preservesCallerThreadAndDoesNotApplyToolkitTimeout() {
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .executionConfig(
                                        ExecutionConfig.builder()
                                                .timeout(Duration.ofMillis(1))
                                                .build())
                                .build());
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        toolkit.registerAgentTool(
                tool(
                        "test",
                        Mono.defer(
                                () -> {
                                    invocationThread.set(Thread.currentThread());
                                    return Mono.delay(Duration.ofSeconds(1))
                                            .thenReturn(
                                                    ToolResultBlock.text("done")
                                                            .withState(ToolResultState.SUCCESS));
                                })));
        Thread caller = Thread.currentThread();
        ToolUseBlock use = ToolUseBlock.builder().id("call-id").name("test").content("{}").build();

        StepVerifier.withVirtualTime(
                        () -> toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).build()))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(
                        result -> {
                            assertEquals("call-id", result.getId());
                            assertEquals("test", result.getName());
                            assertEquals(ToolResultState.SUCCESS, result.getState());
                        })
                .verifyComplete();
        assertSame(caller, invocationThread.get());
    }

    @Test
    void stampsNonJsonExplicitInputAsReactiveValidationErrorWithoutFallback() {
        Toolkit toolkit = new Toolkit();
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("must not run")));
        when(tool.getParameters())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("x", Map.of("type", "integer"))));
        toolkit.registerAgentTool(tool);
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("non-json-direct")
                        .name("test")
                        .input(Map.of("x", 1))
                        .content("{\"x\":1}")
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(use).input(Map.of("x", new Object())).build();

        Mono<ToolResultBlock> publisher = assertDoesNotThrow(() -> toolkit.callTool(param));
        StepVerifier.create(publisher)
                .assertNext(result -> assertSerializationError(result, use))
                .verifyComplete();
        verify(tool, never()).callAsync(any(ToolCallParam.class));
        assertEquals(1, use.getInput().get("x"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stampsNonJsonBatchInputAsReactiveValidationErrorWithoutRawFallback(boolean parallel) {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(parallel).build());
        AgentTool tool = tool("test", Mono.just(ToolResultBlock.text("must not run")));
        when(tool.getParameters())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("x", Map.of("type", "integer"))));
        toolkit.registerAgentTool(tool);
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("non-json-batch")
                        .name("test")
                        .input(Map.of("x", new Object()))
                        .content("{\"x\":1}")
                        .build();

        Mono<List<ToolResultBlock>> publisher =
                assertDoesNotThrow(() -> toolkit.callTools(List.of(use), null, null, null));
        StepVerifier.create(publisher)
                .assertNext(
                        results -> {
                            assertEquals(1, results.size());
                            assertSerializationError(results.get(0), use);
                        })
                .verifyComplete();
        verify(tool, never()).callAsync(any(ToolCallParam.class));
    }

    private static void assertSerializationError(ToolResultBlock result, ToolUseBlock use) {
        assertEquals(use.getId(), result.getId());
        assertEquals(use.getName(), result.getName());
        assertEquals(ToolResultState.ERROR, result.getState());
        String text = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(text.startsWith("Error: Parameter validation failed for tool 'test':"), text);
        assertTrue(text.contains("serialize"), text);
    }

    private static AgentTool tool(String name, Mono<ToolResultBlock> result) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        when(tool.callAsync(org.mockito.ArgumentMatchers.any(ToolCallParam.class)))
                .thenReturn(result);
        return tool;
    }

    private static ToolResultBlock call(Toolkit toolkit, String name, String content) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-id").name(name).content(content).build();
        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).build())
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals("call-id", result.getId());
        assertEquals(name, result.getName());
        return result;
    }
}
