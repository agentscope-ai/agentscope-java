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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for async tool execution with CompletableFuture and Mono return types.
 */
@Tag("unit")
@DisplayName("Async Tool Tests")
class AsyncToolTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        toolkit.registerTool(new SampleTools());
    }

    @Test
    @DisplayName("Should execute CompletableFuture async tool")
    void shouldExecuteCompletableFutureAsyncTool() {
        Map<String, Object> input = Map.of("a", 10, "b", 20);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-async-add")
                        .name("async_add")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertEquals("30", extractFirstText(response));
    }

    @Test
    @DisplayName("Should execute Mono async tool")
    void shouldExecuteMonoAsyncTool() {
        Map<String, Object> input = Map.of("str1", "Hello", "str2", "World");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-async-concat")
                        .name("async_concat")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertEquals("\"HelloWorld\"", extractFirstText(response));
    }

    @Test
    @DisplayName("Should execute async tool with delay")
    void shouldExecuteAsyncToolWithDelay() {
        Map<String, Object> input = Map.of("delayMs", 100);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-async-delayed")
                        .name("async_delayed")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String result = extractFirstText(response);
        assertTrue(result.contains("Completed after 100ms"), "Expected delayed completion message");
    }

    @Test
    @DisplayName("Should handle async tool error")
    void shouldHandleAsyncToolError() {
        Map<String, Object> input = Map.of("message", "test failure");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-async-error")
                        .name("async_error")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock response =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String result = extractFirstText(response);
        assertTrue(result.contains("Error:"), "Expected error message in response");
        assertTrue(result.contains("Async error: test failure"), "Expected specific error message");
    }

    @Test
    @DisplayName("Should execute multiple async tools in parallel")
    void shouldExecuteMultipleAsyncToolsInParallel() {
        Map<String, Object> addInput = Map.of("a", 5, "b", 10);
        ToolUseBlock addCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("async_add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        Map<String, Object> concatInput = Map.of("str1", "Test", "str2", "Async");
        ToolUseBlock concatCall =
                ToolUseBlock.builder()
                        .id("call-2")
                        .name("async_concat")
                        .input(concatInput)
                        .content(JsonUtils.getJsonCodec().toJson(concatInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(addCall, concatCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Responses should not be null");
        assertEquals(2, responses.size(), "Should have two responses");

        // Find responses by id
        ToolResultBlock addResponse =
                responses.stream().filter(r -> "call-1".equals(r.getId())).findFirst().orElse(null);
        ToolResultBlock concatResponse =
                responses.stream().filter(r -> "call-2".equals(r.getId())).findFirst().orElse(null);

        assertNotNull(addResponse, "Add response should be present");
        assertEquals("15", extractFirstText(addResponse));

        assertNotNull(concatResponse, "Concat response should be present");
        assertEquals("\"TestAsync\"", extractFirstText(concatResponse));
    }

    @Test
    @DisplayName("Should mix sync and async tools")
    void shouldMixSyncAndAsyncTools() {
        Map<String, Object> syncInput = Map.of("a", 1, "b", 2);
        ToolUseBlock syncCall =
                ToolUseBlock.builder()
                        .id("call-sync")
                        .name("add")
                        .input(syncInput)
                        .content(JsonUtils.getJsonCodec().toJson(syncInput))
                        .build();

        Map<String, Object> asyncInput = Map.of("a", 3, "b", 4);
        ToolUseBlock asyncCall =
                ToolUseBlock.builder()
                        .id("call-async")
                        .name("async_add")
                        .input(asyncInput)
                        .content(JsonUtils.getJsonCodec().toJson(asyncInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(syncCall, asyncCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Responses should not be null");
        assertEquals(2, responses.size(), "Should have two responses");

        ToolResultBlock syncResponse =
                responses.stream()
                        .filter(r -> "call-sync".equals(r.getId()))
                        .findFirst()
                        .orElse(null);
        ToolResultBlock asyncResponse =
                responses.stream()
                        .filter(r -> "call-async".equals(r.getId()))
                        .findFirst()
                        .orElse(null);

        assertNotNull(syncResponse, "Sync response should be present");
        assertEquals("3", extractFirstText(syncResponse));

        assertNotNull(asyncResponse, "Async response should be present");
        assertEquals("7", extractFirstText(asyncResponse));
    }

    private String extractFirstText(ToolResultBlock response) {
        List<ContentBlock> outputs = response.getOutput();
        if (outputs.isEmpty()) return "";
        return ((TextBlock) outputs.get(0)).getText();
    }
}
