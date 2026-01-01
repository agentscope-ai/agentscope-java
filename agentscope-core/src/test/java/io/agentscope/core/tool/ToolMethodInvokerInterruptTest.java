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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for ToolMethodInvoker's simplified error handling after removing
 * ToolInterruptedException special case.
 *
 * Tests verify that:
 * - All exceptions are uniformly wrapped as ToolResultBlock.error
 * - No special handling for interrupted tools
 * - CompletableFuture, Mono, and sync methods handle errors consistently
 */
@DisplayName("ToolMethodInvoker Interrupt/Error Handling Tests")
class ToolMethodInvokerInterruptTest {

    private ToolMethodInvoker invoker;
    private ObjectMapper objectMapper;
    private ToolResultConverter resultConverter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        resultConverter = new ToolResultConverter(objectMapper);
        invoker = new ToolMethodInvoker(objectMapper, resultConverter);
    }

    private ToolResultBlock invokeWithParam(
            Object tools, Method method, Map<String, Object> input) {
        ToolUseBlock toolUseBlock = new ToolUseBlock("test-id", method.getName(), input);
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();
        return invoker.invokeAsync(tools, method, param).block();
    }

    // Test tools with various error scenarios
    static class ErrorTools {
        public String syncError() {
            throw new RuntimeException("Sync method error");
        }

        public CompletableFuture<String> futureError() {
            return CompletableFuture.supplyAsync(
                    () -> {
                        throw new RuntimeException("Future error");
                    });
        }

        public Mono<String> monoError() {
            return Mono.error(new RuntimeException("Mono error"));
        }

        public String syncInterruptedError() {
            throw new RuntimeException(
                    "InterruptedException", new InterruptedException("Interrupted"));
        }

        public CompletableFuture<String> futureInterruptedError() {
            return CompletableFuture.supplyAsync(
                    () -> {
                        throw new RuntimeException(
                                "Future interrupted",
                                new InterruptedException("Thread interrupted"));
                    });
        }

        public Mono<String> monoInterruptedError() {
            return Mono.error(
                    new RuntimeException(
                            "Mono interrupted", new InterruptedException("Interrupted")));
        }

        public String normalMethod() {
            return "Success";
        }

        public CompletableFuture<String> futureNormal() {
            return CompletableFuture.completedFuture("Future success");
        }

        public Mono<String> monoNormal() {
            return Mono.just("Mono success");
        }
    }

    @Test
    @DisplayName("Should handle sync method error uniformly")
    void testSyncMethodErrorHandling() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("syncError");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        // Verify error is wrapped in ToolResultBlock
        assertNotNull(response, "Response should not be null");

        String errorText = ToolTestUtils.extractContent(response);
        assertTrue(errorText.startsWith("Error:"), "Error message should start with Error:");
        assertTrue(
                errorText.contains("Tool execution failed"),
                "Error message should contain standard prefix");
        assertTrue(
                errorText.contains("Sync method error"),
                "Error message should contain original exception message");
    }

    @Test
    @DisplayName("Should handle CompletableFuture error uniformly")
    void testCompletableFutureErrorHandling() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("futureError");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        // Verify error is wrapped
        assertNotNull(response, "Response should not be null");

        String errorText = ToolTestUtils.extractContent(response);
        assertTrue(errorText.startsWith("Error:"), "Error message should start with Error:");
        assertTrue(
                errorText.contains("Tool execution failed"),
                "Error message should contain standard prefix");
        assertTrue(
                errorText.contains("Future error"),
                "Error message should contain original exception message");
    }

    @Test
    @DisplayName("Should handle Mono error uniformly")
    void testMonoErrorHandling() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("monoError");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        // Verify error is wrapped
        assertNotNull(response, "Response should not be null");

        String errorText = ToolTestUtils.extractContent(response);
        assertTrue(errorText.startsWith("Error:"), "Error message should start with Error:");
        assertTrue(
                errorText.contains("Tool execution failed"),
                "Error message should contain standard prefix");
        assertTrue(
                errorText.contains("Mono error"),
                "Error message should contain original exception message");
    }

    @Test
    @DisplayName("Should NOT specially handle InterruptedException in sync methods")
    void testNoInterruptSpecialCaseSyncMethod() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("syncInterruptedError");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        // Verify it's treated as regular error (not special interrupted result)
        assertNotNull(response, "Response should not be null");

        String errorText = ToolTestUtils.extractContent(response);
        // Should contain "Tool execution failed" not special interrupted marker
        assertTrue(errorText.startsWith("Error:"), "Should use standard error format");
        assertTrue(errorText.contains("Tool execution failed"), "Should use standard error format");
        assertTrue(
                errorText.contains("InterruptedException"),
                "Should contain InterruptedException message");
    }

    @Test
    @DisplayName("Should NOT specially handle InterruptedException in CompletableFuture methods")
    void testNoInterruptSpecialCaseFutureMethod() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("futureInterruptedError");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        // Verify it's treated as regular error
        assertNotNull(response, "Response should not be null");

        String errorText = ToolTestUtils.extractContent(response);
        assertTrue(errorText.startsWith("Error:"), "Should use standard error format");
        assertTrue(errorText.contains("Tool execution failed"), "Should use standard error format");
        assertTrue(errorText.contains("interrupted"), "Should contain interrupted message");
    }

    @Test
    @DisplayName("Should NOT specially handle InterruptedException in Mono methods")
    void testNoInterruptSpecialCaseMonoMethod() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("monoInterruptedError");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        // Verify it's treated as regular error
        assertNotNull(response, "Response should not be null");

        String errorText = ToolTestUtils.extractContent(response);
        assertTrue(errorText.startsWith("Error:"), "Should use standard error format");
        assertTrue(errorText.contains("Tool execution failed"), "Should use standard error format");
        assertTrue(
                errorText.toLowerCase().contains("interrupted"),
                "Should contain interrupted message");
    }

    @Test
    @DisplayName("Should handle successful sync method normally")
    void testSuccessfulSyncMethod() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("normalMethod");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        assertNotNull(response, "Response should not be null");
        assertTrue(!ToolTestUtils.isErrorResponse(response), "Should not be error response");

        String content = ToolTestUtils.extractContent(response);
        assertEquals("\"Success\"", content, "Should return normal result");
    }

    @Test
    @DisplayName("Should handle successful CompletableFuture method normally")
    void testSuccessfulFutureMethod() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("futureNormal");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        assertNotNull(response, "Response should not be null");
        assertTrue(!ToolTestUtils.isErrorResponse(response), "Should not be error response");

        String content = ToolTestUtils.extractContent(response);
        assertEquals("\"Future success\"", content, "Should return normal result");
    }

    @Test
    @DisplayName("Should handle successful Mono method normally")
    void testSuccessfulMonoMethod() throws Exception {
        ErrorTools tools = new ErrorTools();
        Method method = ErrorTools.class.getMethod("monoNormal");

        Map<String, Object> input = new HashMap<>();
        ToolResultBlock response = invokeWithParam(tools, method, input);

        assertNotNull(response, "Response should not be null");
        assertTrue(!ToolTestUtils.isErrorResponse(response), "Should not be error response");

        String content = ToolTestUtils.extractContent(response);
        assertEquals("\"Mono success\"", content, "Should return normal result");
    }

    @Test
    @DisplayName("Should wrap all errors with consistent format")
    void testErrorWrappingConsistency() throws Exception {
        ErrorTools tools = new ErrorTools();

        // Test sync error
        Method syncMethod = ErrorTools.class.getMethod("syncError");
        ToolResultBlock syncResponse = invokeWithParam(tools, syncMethod, new HashMap<>());
        String syncError = ToolTestUtils.extractContent(syncResponse);

        // Test future error
        Method futureMethod = ErrorTools.class.getMethod("futureError");
        ToolResultBlock futureResponse = invokeWithParam(tools, futureMethod, new HashMap<>());
        String futureError = ToolTestUtils.extractContent(futureResponse);

        // Test mono error
        Method monoMethod = ErrorTools.class.getMethod("monoError");
        ToolResultBlock monoResponse = invokeWithParam(tools, monoMethod, new HashMap<>());
        String monoError = ToolTestUtils.extractContent(monoResponse);

        // All should start with "Error: Tool execution failed:"
        assertTrue(
                syncError.startsWith("Error: Tool execution failed:"),
                "Sync error format mismatch");
        assertTrue(
                futureError.startsWith("Error: Tool execution failed:"),
                "Future error format mismatch");
        assertTrue(
                monoError.startsWith("Error: Tool execution failed:"),
                "Mono error format mismatch");
    }
}
