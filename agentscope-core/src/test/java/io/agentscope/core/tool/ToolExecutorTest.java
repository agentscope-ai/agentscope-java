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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import io.agentscope.core.util.JsonUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for ToolExecutor.
 *
 * <p>These tests verify execution paths that invoke the executor itself so regressions in
 * scheduling, ordering, timeout handling, and error propagation are detected.
 */
@Tag("unit")
@DisplayName("ToolExecutor Unit Tests")
class ToolExecutorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        toolkit.registerTool(new SampleTools());
    }

    @Test
    @DisplayName("Should execute multiple tool calls in parallel via Toolkit")
    void shouldExecuteToolsInParallel() {
        Map<String, Object> addInput = Map.of("a", 10, "b", 20);
        Map<String, Object> concatInput = Map.of("str1", "Hello", "str2", "World");

        ToolUseBlock addCall =
                ToolUseBlock.builder()
                        .id("call-add")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();
        ToolUseBlock concatCall =
                ToolUseBlock.builder()
                        .id("call-concat")
                        .name("concat")
                        .input(concatInput)
                        .content(JsonUtils.getJsonCodec().toJson(concatInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(addCall, concatCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return responses for tool calls");
        assertEquals(2, responses.size(), "All tool calls should be executed");

        Map<String, ToolResultBlock> responsesById =
                responses.stream()
                        .collect(Collectors.toMap(ToolResultBlock::getId, Function.identity()));

        ToolResultBlock addResponse = responsesById.get("call-add");
        ToolResultBlock concatResponse = responsesById.get("call-concat");

        assertNotNull(addResponse, "Add tool response should be present");
        assertEquals("30", extractFirstText(addResponse), "Add tool result mismatch");

        assertNotNull(concatResponse, "Concat tool response should be present");
        assertEquals(
                "\"HelloWorld\"", extractFirstText(concatResponse), "Concat tool result mismatch");
    }

    @Test
    @DisplayName("Should wrap tool errors inside executor response")
    void shouldReturnErrorWhenToolThrows() {
        Map<String, Object> errorInput = Map.of("message", "test failure");
        ToolUseBlock errorCall =
                ToolUseBlock.builder()
                        .id("call-error")
                        .name("error_tool")
                        .input(errorInput)
                        .content(JsonUtils.getJsonCodec().toJson(errorInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(errorCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return an error response");
        assertEquals(1, responses.size(), "Single failing call should yield one response");

        String content = extractFirstText(responses.get(0));
        assertEquals(
                "Error: Tool execution failed: Tool error: test failure",
                content,
                "Error message should be wrapped by executor");
    }

    @Test
    @DisplayName("Should convert empty tool publishers to error responses")
    void shouldReturnErrorWhenToolCompletesEmpty() {
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "empty_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that completes without a result";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.empty();
                    }
                });

        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock emptyCall =
                ToolUseBlock.builder()
                        .id("call-empty")
                        .name("empty_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(emptyCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return an error response");
        assertEquals(1, responses.size(), "Empty completion should still yield one response");
        assertEquals("call-empty", responses.get(0).getId(), "Response should keep tool call id");
        assertEquals("empty_tool", responses.get(0).getName(), "Response should keep tool name");
        assertEquals(
                "Error: Tool execution failed: Tool completed without returning a result",
                extractFirstText(responses.get(0)));
    }

    @Test
    @DisplayName("Should return suspended result for external tools")
    void shouldReturnSuspendedResultForExternalTools() {
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_api")
                        .description("Execute API outside the agent runtime")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("endpoint", Map.of("type", "string"))))
                        .build());

        Map<String, Object> input = Map.of("endpoint", "/users");
        ToolUseBlock externalCall =
                ToolUseBlock.builder()
                        .id("call-external")
                        .name("external_api")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(externalCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return a suspended response");
        assertEquals(1, responses.size(), "Single external call should yield one response");

        ToolResultBlock response = responses.get(0);
        assertEquals("call-external", response.getId(), "Response should keep tool call id");
        assertEquals("external_api", response.getName(), "Response should keep tool name");
        assertTrue(response.isSuspended(), "External tool should surface as suspended");
        assertEquals("[Awaiting external execution]", extractFirstText(response));
    }

    @Test
    @DisplayName("Should validate external tool input before suspension")
    void shouldValidateExternalToolInputBeforeSuspension() {
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_api")
                        .description("Execute API outside the agent runtime")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("endpoint", Map.of("type", "string")),
                                        "required",
                                        List.of("endpoint")))
                        .build());

        Map<String, Object> input = Map.of("endpoint", 42);
        ToolUseBlock invalidExternalCall =
                ToolUseBlock.builder()
                        .id("call-invalid-external")
                        .name("external_api")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(invalidExternalCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return a validation response");
        assertEquals(1, responses.size(), "Single external call should yield one response");

        ToolResultBlock response = responses.get(0);
        assertEquals(
                "call-invalid-external", response.getId(), "Response should keep tool call id");
        assertEquals("external_api", response.getName(), "Response should keep tool name");
        assertTrue(!response.isSuspended(), "Invalid external input must not suspend");

        String errorText = extractFirstText(response);
        assertTrue(
                errorText.startsWith("Error: Parameter validation failed for tool 'external_api'"),
                "External tool should fail validation before suspension: " + errorText);
    }

    @Test
    @DisplayName("Should reject inactive grouped external tools before suspension")
    void shouldRejectInactiveGroupedExternalToolsBeforeSuspension() {
        toolkit.createToolGroup("inactiveExternal", "Inactive external tools", false);
        toolkit.registration()
                .agentTool(
                        new SchemaOnlyTool(
                                ToolSchema.builder()
                                        .name("external_inactive")
                                        .description("Inactive external API")
                                        .parameters(
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        Map.of(
                                                                "endpoint",
                                                                Map.of("type", "string"))))
                                        .build()))
                .group("inactiveExternal")
                .apply();

        Map<String, Object> input = Map.of("endpoint", "/users");
        ToolUseBlock externalCall =
                ToolUseBlock.builder()
                        .id("call-inactive-external")
                        .name("external_inactive")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(externalCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Executor should return an authorization response");
        assertEquals(1, responses.size(), "Single external call should yield one response");

        ToolResultBlock response = responses.get(0);
        assertEquals("call-inactive-external", response.getId(), "Response should keep call id");
        assertEquals("external_inactive", response.getName(), "Response should keep tool name");
        assertTrue(!response.isSuspended(), "Inactive external tool must not suspend");
        assertEquals(
                "Error: Unauthorized tool call: 'external_inactive' is not available",
                extractFirstText(response));
    }

    @Test
    @DisplayName("Should NOT specially handle InterruptedException in error path")
    void testToolErrorWithoutInterruptSpecialCase() {
        // Create a tool that throws RuntimeException with InterruptedException cause
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "interrupted_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that simulates interrupted error";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(
                                new RuntimeException(
                                        "Execution error",
                                        new InterruptedException("Thread interrupted")));
                    }
                });

        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock interruptedCall =
                ToolUseBlock.builder()
                        .id("call-interrupt")
                        .name("interrupted_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(interruptedCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Should return error response");
        assertEquals(1, responses.size(), "Should have one response");

        String errorText = extractFirstText(responses.get(0));
        // Should be standard error format, not special interrupted result
        assertTrue(
                errorText.startsWith("Error:"), "Should use standard error format: " + errorText);
        assertTrue(
                errorText.contains("Tool execution failed")
                        || errorText.contains("Execution error"),
                "Should contain error message");
    }

    @Test
    @DisplayName("Should handle concurrent tool execution with errors")
    void testConcurrentToolExecutionWithErrors() {
        // Register a tool that sometimes fails (thread-safe counter)
        AtomicInteger callCount = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "flaky_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that fails on first call";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        int count = callCount.getAndIncrement();
                        if (count == 0) {
                            return Mono.error(new RuntimeException("First call failed"));
                        }
                        return Mono.just(
                                ToolResultBlock.of(
                                        TextBlock.builder()
                                                .text("Call " + count + " succeeded")
                                                .build()));
                    }
                });

        // Execute multiple calls in parallel
        Map<String, Object> emptyInput = Map.of();
        Map<String, Object> addInput = Map.of("a", 1, "b", 2);
        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("flaky_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call-2")
                        .name("flaky_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolUseBlock call3 =
                ToolUseBlock.builder()
                        .id("call-3")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(call1, call2, call3), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Should return responses");
        assertEquals(3, responses.size(), "Should have three responses");

        // Count how many calls succeeded vs failed
        long errorCount =
                responses.stream().filter(r -> extractFirstText(r).startsWith("Error:")).count();
        long successCount =
                responses.stream()
                        .filter(
                                r ->
                                        extractFirstText(r).contains("succeeded")
                                                || extractFirstText(r).equals("3"))
                        .count();

        // Exactly one flaky_tool call should fail (the first one to execute)
        // and two should succeed (one flaky_tool + one add)
        assertEquals(1, errorCount, "Exactly one call should fail");
        assertEquals(2, successCount, "Exactly two calls should succeed");
    }

    @Test
    @DisplayName("Should apply preset parameters after explicit ToolCallParam input")
    void shouldApplyPresetParametersAfterExplicitInput() {
        class OverrideTool {
            @Tool(description = "Test preset precedence with explicit ToolCallParam input")
            public ToolResultBlock testOverride(
                    @ToolParam(name = "param1") String param1,
                    @ToolParam(name = "param2") String param2) {
                return ToolResultBlock.text(
                        String.format("param1: %s, param2: %s", param1, param2));
            }
        }

        toolkit.registration()
                .tool(new OverrideTool())
                .presetParameters(
                        Map.of(
                                "testOverride",
                                Map.of("param1", "preset_value1", "param2", "preset_value2")))
                .apply();

        Map<String, Object> explicitInput = Map.of("param1", "agent_value1");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-override")
                        .name("testOverride")
                        .input(Map.of())
                        .content("{}")
                        .build();

        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolCall)
                                        .input(explicitInput)
                                        .build())
                        .block(TIMEOUT);

        assertNotNull(result, "Result should not be null");
        String resultText = extractFirstText(result);
        assertTrue(
                resultText.contains("param1: preset_value1"),
                "Preset value should override explicit ToolCallParam input");
        assertTrue(resultText.contains("param2: preset_value2"), "Preset value should be used");
    }

    @Test
    @DisplayName("Should use only preset parameters when both input sources are absent")
    void shouldUseOnlyPresetParametersWhenInputsAbsent() {
        class PresetOnlyTool {
            @Tool(description = "Test preset usage when no explicit inputs are present")
            public ToolResultBlock presetOnly(@ToolParam(name = "param1") String param1) {
                return ToolResultBlock.text("param1: " + param1);
            }
        }

        toolkit.registration()
                .tool(new PresetOnlyTool())
                .presetParameters(Map.of("presetOnly", Map.of("param1", "preset_value1")))
                .apply();

        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-preset-only")
                        .name("presetOnly")
                        .content("{}")
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(result, "Result should not be null");
        assertEquals("param1: preset_value1", extractFirstText(result));
    }

    @Test
    @DisplayName("Should execute without preset parameters when registration metadata is absent")
    void shouldExecuteWhenRegisteredMetadataIsAbsent() {
        AgentTool echoTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "metadata_gap_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool for simulating a metadata lookup gap";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(
                                ToolResultBlock.text("value: " + param.getInput().get("value")));
                    }
                };

        ToolRegistry registryWithMetadataGap =
                new ToolRegistry() {
                    @Override
                    RegisteredToolFunction getRegisteredTool(String name) {
                        return null;
                    }
                };
        registryWithMetadataGap.registerTool(
                echoTool.getName(), echoTool, new RegisteredToolFunction(echoTool, null, null));
        ToolExecutor executor =
                new ToolExecutor(
                        toolkit,
                        registryWithMetadataGap,
                        new ToolGroupManager(),
                        ToolkitConfig.defaultConfig());
        Map<String, Object> input = Map.of("value", "caller_value");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-metadata-gap")
                        .name(echoTool.getName())
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                executor.execute(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertNotNull(result, "Result should not be null");
        assertEquals("value: caller_value", extractFirstText(result));
    }

    @Test
    @DisplayName("Should format all error messages consistently")
    void testErrorMessageFormat() {
        // Register various failing tools
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "null_pointer_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that throws NPE";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", new HashMap<>());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(new NullPointerException("Null value encountered"));
                    }
                });

        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "illegal_arg_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that throws IllegalArgumentException";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", new HashMap<>());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(
                                new IllegalArgumentException("Invalid argument provided"));
                    }
                });

        // Execute both tools
        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock npeCall =
                ToolUseBlock.builder()
                        .id("npe")
                        .name("null_pointer_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolUseBlock argCall =
                ToolUseBlock.builder()
                        .id("arg")
                        .name("illegal_arg_tool")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(npeCall, argCall), null, null, null).block(TIMEOUT);

        assertNotNull(responses, "Should return responses");
        assertEquals(2, responses.size(), "Should have two responses");

        // Both should follow same error format
        for (int i = 0; i < responses.size(); i++) {
            String errorText = extractFirstText(responses.get(i));
            assertTrue(
                    errorText.startsWith("Error:"),
                    "Error " + i + " should start with 'Error:': " + errorText);
            assertTrue(
                    errorText.contains("Tool execution failed")
                            || errorText.contains("encountered")
                            || errorText.contains("provided"),
                    "Error " + i + " should contain meaningful message: " + errorText);
        }
    }

    // ==================== Retry Behavior Tests ====================

    @Test
    @DisplayName("Should retry annotation sync tools that throw exceptions")
    void shouldRetryAnnotationSyncToolExceptions() {
        FlakyTools flaky = new FlakyTools();
        toolkit.registerTool(flaky);

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-flaky-sync", "flaky_sync")),
                                retryConfig(2),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals("\"recovered\"", extractFirstText(responses.get(0)));
        assertEquals(2, flaky.calls.get());
    }

    @Test
    @DisplayName("Should retry annotation Mono tools that fail with exceptions")
    void shouldRetryAnnotationMonoToolExceptions() {
        FlakyTools flaky = new FlakyTools();
        toolkit.registerTool(flaky);

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-flaky-mono", "flaky_mono")),
                                retryConfig(2),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals("\"recovered\"", extractFirstText(responses.get(0)));
        assertEquals(2, flaky.calls.get());
    }

    @Test
    @DisplayName("Should retry annotation CompletableFuture tools that fail with exceptions")
    void shouldRetryAnnotationFutureToolExceptions() {
        FlakyTools flaky = new FlakyTools();
        toolkit.registerTool(flaky);

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-flaky-future", "flaky_future")),
                                retryConfig(2),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals("\"recovered\"", extractFirstText(responses.get(0)));
        assertEquals(2, flaky.calls.get());
    }

    @Test
    @DisplayName("Should retry MCP tool calls on transport failures")
    void shouldRetryMcpToolTransportFailures() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("test-client");
        McpTool mcpTool = new McpTool("flaky_mcp", "Flaky MCP tool", emptySchema(), wrapper);
        when(wrapper.callTool(eq("flaky_mcp"), any(), any()))
                .thenReturn(Mono.error(new IOException("Network down")))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("mcp recovered")),
                                        false)));
        toolkit.registerTool(mcpTool);

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-flaky-mcp", "flaky_mcp")),
                                retryConfig(2),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertTrue(extractFirstText(responses.get(0)).contains("mcp recovered"));
        verify(wrapper, times(2)).callTool(eq("flaky_mcp"), any(), any());
    }

    @Test
    @DisplayName("Should not retry MCP protocol-level business errors")
    void shouldNotRetryMcpProtocolErrors() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("test-client");
        McpTool mcpTool = new McpTool("mcp_error", "Failing MCP tool", emptySchema(), wrapper);
        when(wrapper.callTool(eq("mcp_error"), any(), any()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("boom")), true)));
        toolkit.registerTool(mcpTool);

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-mcp-error", "mcp_error")),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(ToolResultState.ERROR, responses.get(0).getState());
        verify(wrapper, times(1)).callTool(eq("mcp_error"), any(), any());
    }

    @Test
    @DisplayName("Should retry custom AgentTool failures that surface as errors")
    void shouldRetryCustomAgentToolErrors() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "custom_flaky";
                    }

                    @Override
                    public String getDescription() {
                        return "Custom tool that fails once";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        int attempt = calls.incrementAndGet();
                        if (attempt == 1) {
                            return Mono.error(new IOException("Transient custom failure"));
                        }
                        return Mono.just(ToolResultBlock.text("custom recovered"));
                    }
                });

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-custom-flaky", "custom_flaky")),
                                retryConfig(2),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals("custom recovered", extractFirstText(responses.get(0)));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("Should not retry tool-returned error results")
    void shouldNotRetrySemanticErrorResults() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "semantic_error";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that reports a business error result";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.just(ToolResultBlock.error("Business failure"));
                    }
                });

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-semantic-error", "semantic_error")),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(1, calls.get());
        assertTrue(extractFirstText(responses.get(0)).contains("Business failure"));
    }

    @Test
    @DisplayName("Should respect the retryOn predicate")
    void shouldRespectRetryOnPredicate() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "never_retry";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool whose failures are filtered out by retryOn";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.error(new IOException("Always failing"));
                    }
                });

        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(1))
                        .maxBackoff(Duration.ofMillis(10))
                        .retryOn(error -> false)
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-never-retry", "never_retry")),
                                config,
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(1, calls.get());
        assertEquals(ToolResultState.ERROR, responses.get(0).getState());
    }

    @Test
    @DisplayName("Should exhaust retries and return an error result with id and name")
    void shouldExhaustRetriesWithErrorResult() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "always_fail";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that always fails";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.error(new IOException("Always failing"));
                    }
                });

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-always-fail", "always_fail")),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(3, calls.get());
        ToolResultBlock result = responses.get(0);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("call-always-fail", result.getId());
        assertEquals("always_fail", result.getName());
        assertTrue(extractFirstText(result).contains("Always failing"));
    }

    @Test
    @DisplayName("Should retry tool execution timeouts when retryOn matches")
    void shouldRetryTimeouts() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "never_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that never completes";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.never();
                    }
                });

        ExecutionConfig config =
                ExecutionConfig.builder()
                        .timeout(Duration.ofMillis(100))
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(1))
                        .maxBackoff(Duration.ofMillis(10))
                        .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(List.of(toolCall("call-never", "never_tool")), config, null, null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(2, calls.get());
        assertTrue(extractFirstText(responses.get(0)).contains("timeout"));
    }

    @Test
    @DisplayName("Should isolate retry exhaustion in parallel batches")
    void shouldIsolateRetryExhaustionInParallelBatches() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "parallel_fail";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that always fails in parallel batches";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.error(new IOException("Always failing"));
                    }
                });

        Map<String, Object> addInput = Map.of("a", 1, "b", 2);
        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(
                                        toolCall("call-parallel-fail", "parallel_fail"),
                                        toolCall("call-parallel-add", "add", addInput)),
                                retryConfig(2),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(2, responses.size());
        Map<String, ToolResultBlock> byId =
                responses.stream()
                        .collect(Collectors.toMap(ToolResultBlock::getId, Function.identity()));
        assertEquals(2, calls.get());
        assertEquals(ToolResultState.ERROR, byId.get("call-parallel-fail").getState());
        assertEquals("3", extractFirstText(byId.get("call-parallel-add")));
    }

    @Test
    @DisplayName("Should not retry external tool suspensions")
    void shouldNotRetryExternalToolSuspensions() {
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_retry")
                        .description("External tool with retry config")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("endpoint", Map.of("type", "string"))))
                        .build());

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(
                                        toolCall(
                                                "call-external-retry",
                                                "external_retry",
                                                Map.of("endpoint", "/users"))),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuspended());
    }

    @Test
    @DisplayName("Should not retry annotation tools that suspend via ToolSuspendException")
    void shouldNotRetryToolSuspensionExceptions() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "suspend_tool", description = "Tool that suspends")
                    public String suspend() {
                        calls.incrementAndGet();
                        throw new ToolSuspendException("awaiting external execution");
                    }
                });

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-suspend", "suspend_tool")),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(1, calls.get());
        assertTrue(responses.get(0).isSuspended());
    }

    @Test
    @DisplayName("Should not retry Mono tools that suspend via ToolSuspendException")
    void shouldNotRetryMonoToolSuspension() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "suspend_mono", description = "Mono tool that suspends")
                    public Mono<String> suspend() {
                        calls.incrementAndGet();
                        return Mono.error(new ToolSuspendException("awaiting external execution"));
                    }
                });

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-suspend-mono", "suspend_mono")),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(1, calls.get());
        assertTrue(responses.get(0).isSuspended());
    }

    @Test
    @DisplayName("Should not retry CompletableFuture tools that suspend via ToolSuspendException")
    void shouldNotRetryFutureToolSuspension() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "suspend_future", description = "Future tool that suspends")
                    public CompletableFuture<String> suspend() {
                        calls.incrementAndGet();
                        return CompletableFuture.failedFuture(
                                new ToolSuspendException("awaiting external execution"));
                    }
                });

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-suspend-future", "suspend_future")),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(1, calls.get());
        assertTrue(responses.get(0).isSuspended());
    }

    @Test
    @DisplayName("Should not retry ToolSuspendException wrapped in a non-standard exception")
    void shouldNotRetryWrappedToolSuspension() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "wrapped_suspend";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that suspends behind a custom wrapper";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.error("unused"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsyncForExecution(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.error(
                                new IllegalStateException(
                                        "wrapped",
                                        new ToolSuspendException("awaiting external execution")));
                    }
                });

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-wrapped-suspend", "wrapped_suspend")),
                                retryConfig(3),
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(1, calls.get());
        assertTrue(responses.get(0).isSuspended());
    }

    @Test
    @DisplayName("Should not retry deterministic failures with the retryable-errors predicate")
    void shouldNotRetryDeterministicFailureWithRetryableErrors() {
        AtomicInteger calls = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "misconfigured_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that fails deterministically";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.error(new IllegalStateException("Not initialized"));
                    }
                });

        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(1))
                        .maxBackoff(Duration.ofMillis(10))
                        .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                        .build();

        List<ToolResultBlock> responses =
                toolkit.callTools(
                                List.of(toolCall("call-misconfigured", "misconfigured_tool")),
                                config,
                                null,
                                null)
                        .block(TIMEOUT);

        assertEquals(1, responses.size());
        assertEquals(1, calls.get());
        assertEquals(ToolResultState.ERROR, responses.get(0).getState());
    }

    @Test
    @DisplayName("Should keep the callAsync error-result contract on direct calls")
    void shouldKeepCallAsyncContractOnDirectCalls() {
        // Custom tool failing with an error signal: direct calls still receive an error result
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "direct_error";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that fails with an error signal";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return emptySchema();
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(new IllegalStateException("Direct failure"));
                    }
                });
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolCall("call-direct", "direct_error"))
                                        .build())
                        .block(TIMEOUT);
        assertEquals("Error: Tool execution failed: Direct failure", extractFirstText(result));

        // Annotation tools keep converting exceptions to error results on the direct path
        ToolResultBlock annotationResult =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                toolCall(
                                                        "call-direct-annotation",
                                                        "error_tool",
                                                        Map.of("message", "direct")))
                                        .input(Map.of("message", "direct"))
                                        .build())
                        .block(TIMEOUT);
        assertEquals(
                "Error: Tool execution failed: Tool error: direct",
                extractFirstText(annotationResult));

        // MCP tools keep their own error formatting on the direct path
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("test-client");
        McpTool mcpTool = new McpTool("direct_mcp", "Direct MCP tool", emptySchema(), wrapper);
        when(wrapper.callTool(eq("direct_mcp"), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("Network error")));
        toolkit.registerTool(mcpTool);
        ToolResultBlock mcpResult =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolCall("call-direct-mcp", "direct_mcp"))
                                        .build())
                        .block(TIMEOUT);
        String mcpText = extractFirstText(mcpResult);
        assertTrue(mcpText.contains("MCP tool error"));
        assertTrue(mcpText.contains("Network error"));
    }

    // ==================== Test Helpers ====================

    private ToolUseBlock toolCall(String id, String name) {
        return toolCall(id, name, Map.of());
    }

    private ToolUseBlock toolCall(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    private ExecutionConfig retryConfig(int maxAttempts) {
        return ExecutionConfig.builder()
                .maxAttempts(maxAttempts)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(10))
                .build();
    }

    private Map<String, Object> emptySchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        return schema;
    }

    private String extractFirstText(ToolResultBlock response) {
        assertTrue(
                ToolTestUtils.isValidToolResultBlock(response),
                "Tool response should contain content");
        List<ContentBlock> outputs = response.getOutput();
        if (outputs.isEmpty()) return "";
        return ((TextBlock) outputs.get(0)).getText();
    }

    /** Annotation-based tools that fail on the first call and recover afterwards. */
    public static class FlakyTools {

        final AtomicInteger calls = new AtomicInteger(0);

        @Tool(name = "flaky_sync", description = "Throws IOException on the first call")
        public String flakySync() throws IOException {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("Transient sync failure");
            }
            return "recovered";
        }

        @Tool(name = "flaky_mono", description = "Fails with IOException on the first call")
        public Mono<String> flakyMono() {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) {
                return Mono.error(new IOException("Transient mono failure"));
            }
            return Mono.just("recovered");
        }

        @Tool(name = "flaky_future", description = "Fails with IOException on the first call")
        public CompletableFuture<String> flakyFuture() {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) {
                return CompletableFuture.failedFuture(new IOException("Transient future failure"));
            }
            return CompletableFuture.completedFuture("recovered");
        }
    }
}
