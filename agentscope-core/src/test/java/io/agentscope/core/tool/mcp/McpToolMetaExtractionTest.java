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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * Tests for MCP meta extraction logic in {@link McpTool#callAsync(ToolCallParam)}.
 *
 * <p>Validates that only objects registered under the {@link McpMeta} type namespace are included
 * in the {@code meta} parameter of {@code CallToolRequest}, and that all ContextStore
 * implementations are supported.
 */
class McpToolMetaExtractionTest {

    private McpClientWrapper mockClientWrapper;
    private Map<String, Object> parameters;
    private McpSchema.CallToolResult successResult;

    @BeforeEach
    void setUp() {
        mockClientWrapper = mock(McpClientWrapper.class);
        when(mockClientWrapper.getName()).thenReturn("test-client");

        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());

        McpSchema.TextContent resultContent = new McpSchema.TextContent("Success");
        successResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();
    }

    /**
     * Helper: capture the meta Map passed to clientWrapper.callTool(name, args, meta).
     *
     * <p>We mock the 3-arg overload (with meta) and capture the 3rd argument.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureMeta(McpTool tool, ToolCallParam param) {
        // Mock the 3-arg callTool to return success
        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), any(Map.class)))
                .thenReturn(Mono.just(successResult));

        // Execute
        ToolResultBlock result = tool.callAsync(param).block();
        assertNotNull(result);

        // Capture the meta argument
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class), metaCaptor.capture());

        return metaCaptor.getValue();
    }

    // ==================== RuntimeContext path ====================

    @Test
    void testMetaExtracted_FromRuntimeContext() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpMeta mcpMeta = new McpMeta(Map.of("traceId", "abc-123", "userId", "u456"));
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertEquals(2, capturedMeta.size());
        assertEquals("abc-123", capturedMeta.get("traceId"));
        assertEquals("u456", capturedMeta.get("userId"));
    }

    @Test
    void testMetaExtracted_SingleEntry() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpMeta mcpMeta = new McpMeta(Map.of("callbackUrl", "https://hook.example.com"));
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertEquals(1, capturedMeta.size());
        assertEquals("https://hook.example.com", capturedMeta.get("callbackUrl"));
    }

    // ==================== Null / empty safety ====================

    @Test
    void testMetaExtracted_ContextIsNull_EmptyMetaSent() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        // No context set — should not NPE, should send empty meta
        ToolCallParam param = ToolCallParam.builder().input(new HashMap<>()).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertNotNull(capturedMeta);
        assertTrue(capturedMeta.isEmpty());
    }

    @Test
    void testMetaExtracted_ContextWithNoMcpMeta_EmptyMetaSent() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        // Context exists but has no McpMeta registered — only a non-meta object
        ToolExecutionContext ctx =
                ToolExecutionContext.builder()
                        .register("just-a-string-value") // not McpMeta
                        .build();

        ToolCallParam param = ToolCallParam.builder().input(new HashMap<>()).context(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertNotNull(capturedMeta);
        assertTrue(capturedMeta.isEmpty());
    }

    @Test
    void testMetaExtracted_McpMetaIsEmpty_EmptyMetaSent() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        // McpMeta registered but with empty entries
        McpMeta emptyMeta = new McpMeta(null);
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, emptyMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertNotNull(capturedMeta);
        assertTrue(capturedMeta.isEmpty());
    }

    // ==================== Value types in meta ====================

    @Test
    void testMetaExtracted_ComplexValueTypes() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpMeta mcpMeta =
                new McpMeta(
                        Map.of(
                                "stringVal",
                                "hello",
                                "intVal",
                                42,
                                "boolVal",
                                true,
                                "nested",
                                Map.of("inner", "value")));

        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertEquals(4, capturedMeta.size());
        assertEquals("hello", capturedMeta.get("stringVal"));
        assertEquals(42, capturedMeta.get("intVal"));
        assertEquals(true, capturedMeta.get("boolVal"));
        assertEquals(Map.of("inner", "value"), capturedMeta.get("nested"));
    }

    // ==================== Preset arguments + meta coexist ====================

    @Test
    void testMetaExtracted_PresetArgsAndMetaCoexist() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("units", "celsius");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        McpMeta mcpMeta = new McpMeta(Map.of("traceId", "abc-123"));
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        Map<String, Object> input = new HashMap<>();
        input.put("location", "Beijing");

        ToolCallParam param = ToolCallParam.builder().input(input).runtimeContext(ctx).build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);

        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), any(Map.class)))
                .thenReturn(Mono.just(successResult));

        ToolResultBlock result = tool.callAsync(param).block();
        assertNotNull(result);

        verify(mockClientWrapper)
                .callTool(eq("test-tool"), argsCaptor.capture(), metaCaptor.capture());

        // Arguments should contain both preset and input
        Map<String, Object> capturedArgs = argsCaptor.getValue();
        assertEquals("Beijing", capturedArgs.get("location"));
        assertEquals("celsius", capturedArgs.get("units"));

        // Meta should contain McpMeta entries
        Map<String, Object> capturedMeta = metaCaptor.getValue();
        assertEquals(1, capturedMeta.size());
        assertEquals("abc-123", capturedMeta.get("traceId"));
    }
}
