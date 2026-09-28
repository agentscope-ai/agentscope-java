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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * Tests for the metadata propagation switches in {@link McpTool#callAsync}.
 *
 * <p>Effective propagation is a logical AND of two switches:
 *
 * <ul>
 *   <li>the connection-level {@link McpClientWrapper#isPropagateMeta()}, read live on every call
 *       (disabling a connection takes effect immediately, even for tools registered earlier);
 *   <li>the per-tool {@link McpTool#isPropagateMeta()} restriction, fixed at registration time.
 * </ul>
 *
 * <p>When the AND evaluates to false, the {@code meta} argument must be {@code null} so the field
 * is omitted entirely from the {@link McpSchema.CallToolRequest} — neither user {@link McpMeta}
 * entries nor the framework tool-call id leave the process.
 */
class McpToolMetaPropagationTest {

    private static final String TOOL_CALL_ID_KEY = "io.agentscope/toolCallId";

    private McpClientWrapper mockClientWrapper;
    private Map<String, Object> parameters;
    private McpSchema.CallToolResult successResult;

    @BeforeEach
    void setUp() {
        // Connection-level switch on by default; individual tests re-stub as needed.
        mockClientWrapper = McpClientWrapperTestSupport.mockWrapper("test-client", true);

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

    private McpTool newTool() {
        return new McpTool("test-tool", "Description", parameters, mockClientWrapper);
    }

    private ToolCallParam paramWithMeta() {
        McpMeta meta = new McpMeta(Map.of("traceId", "trace-123", "callbackUrl", "internal"));
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name("test-tool")
                                .input(Map.of())
                                .build())
                .runtimeContext(RuntimeContext.builder().put(McpMeta.class, meta).build())
                .build();
    }

    private ToolCallParam paramWithoutMeta() {
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name("test-tool")
                                .input(Map.of())
                                .build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureMeta(McpTool tool, ToolCallParam param) {
        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), any(Map.class)))
                .thenReturn(Mono.just(successResult));
        assertNotNull(tool.callAsync(param).block());

        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class), metaCaptor.capture());
        return metaCaptor.getValue();
    }

    private void assertMetaOmitted(McpTool tool, ToolCallParam param) {
        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), isNull()))
                .thenReturn(Mono.just(successResult));
        assertNotNull(tool.callAsync(param).block());
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class), isNull());
    }

    @Test
    void propagatesMetaByDefault() {
        McpTool tool = newTool();

        Map<String, Object> meta = captureMeta(tool, paramWithMeta());

        assertNotNull(meta);
        assertEquals("trace-123", meta.get("traceId"));
        assertEquals("call-1", meta.get(TOOL_CALL_ID_KEY));
        assertEquals("internal", meta.get("callbackUrl"));
        assertTrue(tool.isPropagateMeta());
    }

    @Test
    void toolDisabledOmitsMetaEntirely() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);
        assertFalse(tool.isPropagateMeta());

        assertMetaOmitted(tool, paramWithMeta());
    }

    @Test
    void toolDisabledWithoutRuntimeContextStillOmitsMeta() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);

        assertMetaOmitted(tool, paramWithoutMeta());
    }

    @Test
    void wrapperDisabledAtCallTimeOmitsMetaEvenWhenToolAllows() {
        // Tool-level restriction allows propagation, but the live connection-level switch
        // wins the AND: meta must be omitted.
        McpTool tool = newTool();
        when(mockClientWrapper.isPropagateMeta()).thenReturn(false);

        assertMetaOmitted(tool, paramWithMeta());
    }

    @Test
    void bothDisabledOmitsMeta() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);
        when(mockClientWrapper.isPropagateMeta()).thenReturn(false);

        assertMetaOmitted(tool, paramWithMeta());
    }

    @Test
    void wrapperToggledAtRuntimeTakesEffectImmediately() {
        // The wrapper switch is read live: no re-registration needed for the change to apply.
        McpTool tool = newTool();

        Map<String, Object> meta = captureMeta(tool, paramWithMeta());
        assertEquals("trace-123", meta.get("traceId"));

        // Flip the connection-level switch after the tool was already used.
        when(mockClientWrapper.isPropagateMeta()).thenReturn(false);

        McpTool sameTool = tool;
        assertMetaOmitted(sameTool, paramWithMeta());
    }

    @Test
    void reEnablingToolRestoresPropagation() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);
        tool.setPropagateMeta(true);

        Map<String, Object> meta = captureMeta(tool, paramWithMeta());

        assertEquals("trace-123", meta.get("traceId"));
        assertEquals("call-1", meta.get(TOOL_CALL_ID_KEY));
    }

    @Test
    void propagatesUserMetaWithoutToolUseBlock() {
        // buildMetaMap must tolerate a missing ToolUseBlock: user McpMeta entries still
        // propagate, only the framework tool-call id is absent.
        McpTool tool = newTool();

        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(
                                RuntimeContext.builder()
                                        .put(
                                                McpMeta.class,
                                                new McpMeta(Map.of("traceId", "trace-123")))
                                        .build())
                        .build();

        Map<String, Object> meta = captureMeta(tool, param);

        assertNotNull(meta);
        assertEquals("trace-123", meta.get("traceId"));
        assertFalse(meta.containsKey(TOOL_CALL_ID_KEY));
    }

    @Test
    void omitsToolCallIdWhenToolUseBlockHasNoId() {
        // A ToolUseBlock without an id still propagates user McpMeta entries, but the
        // framework tool-call id cannot be attached.
        McpTool tool = newTool();

        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder().name("test-tool").input(Map.of()).build())
                        .runtimeContext(
                                RuntimeContext.builder()
                                        .put(
                                                McpMeta.class,
                                                new McpMeta(Map.of("traceId", "trace-123")))
                                        .build())
                        .build();

        Map<String, Object> meta = captureMeta(tool, param);

        assertNotNull(meta);
        assertEquals("trace-123", meta.get("traceId"));
        assertFalse(meta.containsKey(TOOL_CALL_ID_KEY));
    }

    @Test
    void disabledStillConvertsSuccessfulResults() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);

        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), isNull()))
                .thenReturn(Mono.just(successResult));

        ToolResultBlock result = tool.callAsync(paramWithMeta()).block();
        assertNotNull(result);
        assertNotNull(result.getOutput());
        assertFalse(result.getOutput().isEmpty());
    }
}
