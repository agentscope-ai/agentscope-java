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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Result metadata path of {@link McpTool#callAsync}: server-supplied {@code _meta} is not exposed by
 * default, and a registered {@link McpToolResultCustomizer} controls the map placed under {@link
 * McpTool#METADATA_MCP_RESULT_META}.
 */
class McpToolResultMetaTest {

    private McpClientWrapper clientWrapper;
    private Map<String, Object> parameters;

    @BeforeEach
    void setUp() {
        clientWrapper = McpClientWrapperTestSupport.mockWrapper("test-client", true);
        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());
    }

    @Test
    void serverResultMetaIsNotExposedWithoutCustomizer() {
        stubResult(result(Map.of("traceId", "trace-123", "spanId", "span-9"), false));

        ToolResultBlock block = call(newTool());

        assertTrue(block.getMetadata().isEmpty());
        assertNull(block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
        assertEquals("ok", text(block));
    }

    @Test
    void resultWithoutMetaLeavesMetadataEmpty() {
        stubResult(result(Map.of(), false));

        ToolResultBlock block = call(newTool());

        assertTrue(block.getMetadata().isEmpty(), "no meta in, no metadata out");
    }

    @Test
    void customizerReceivesTheCallAndContributesMetadata() {
        stubResult(result(Map.of("traceId", "trace-123"), false));
        AtomicReference<McpToolResultContext> seen = new AtomicReference<>();
        useCustomizer(
                (context, resultMetadata) -> {
                    seen.set(context);
                    Object traceId = context.resultMeta().get("traceId");
                    if (traceId != null) {
                        resultMetadata.put("trace.id", "trace-" + traceId);
                    }
                });

        ToolResultBlock block = call(newTool());

        assertEquals(
                Map.of("trace.id", "trace-trace-123"),
                block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META),
                "configured customizers populate the map exposed under the reserved key");
        assertEquals(1, block.getMetadata().size());
        McpToolResultContext context = seen.get();
        assertNotNull(context);
        assertEquals("test-tool", context.toolName());
        assertEquals("test-tool", context.remoteToolName());
        assertEquals("call-1", context.toolCallId());
        assertEquals("x", context.arguments().get("q"));
        assertEquals("trace-123", context.resultMeta().get("traceId"));
        assertNotNull(context.result());
    }

    @Test
    void customizerSeesArgumentsWithPresetValuesMerged() {
        stubResult(result(Map.of(), false));
        AtomicReference<Map<String, Object>> seenArguments = new AtomicReference<>();
        useCustomizer(
                (context, resultMetadata) -> {
                    seenArguments.set(context.arguments());
                });
        McpTool tool =
                new McpTool(
                        "test-tool",
                        "test-tool",
                        "Description",
                        parameters,
                        null,
                        clientWrapper,
                        Map.of("q", "preset", "tenant", "acme"),
                        "test-client",
                        true);

        call(tool);

        assertEquals(Map.of("q", "x", "tenant", "acme"), seenArguments.get());
    }

    @Test
    void customizerAlsoRunsForErrorResults() {
        stubResult(result(Map.of("traceId", "trace-err"), true));
        useCustomizer(
                (context, resultMetadata) ->
                        resultMetadata.put("traceId", context.resultMeta().get("traceId")));

        ToolResultBlock block = call(newTool());

        assertEquals(ToolResultState.ERROR, block.getState());
        assertEquals(
                Map.of("traceId", "trace-err"),
                block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    @Test
    void customizerCanExplicitlyExposeRawServerMeta() {
        stubResult(result(Map.of("traceId", "raw"), false));
        useCustomizer((context, resultMetadata) -> resultMetadata.putAll(context.resultMeta()));

        ToolResultBlock block = call(newTool());

        assertEquals(
                Map.of("traceId", "raw"),
                block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    @Test
    void customizersRunInOrderAndLaterWritesOverrideEarlierWrites() {
        stubResult(result(Map.of("traceId", "raw"), false));
        useCustomizers(
                (context, resultMetadata) -> resultMetadata.put("trace.id", "first"),
                (context, resultMetadata) -> resultMetadata.put("trace.id", "second"));

        ToolResultBlock block = call(newTool());

        assertEquals(
                Map.of("trace.id", "second"),
                block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    @Test
    void customizerFailureIsLoggedAndDoesNotBlockLaterCustomizersOrOriginalResult() {
        stubResult(result(Map.of("traceId", "raw"), false));
        useCustomizers(
                (context, resultMetadata) -> {
                    throw new IllegalStateException("customizer failed");
                },
                (context, resultMetadata) -> resultMetadata.put("after.failure", true));

        ToolResultBlock block = call(newTool());

        assertEquals("ok", text(block));
        assertEquals(
                Map.of("after.failure", true),
                block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    @Test
    void customizerCanRemoveAnEntryBeforeTheFinalWrap() {
        stubResult(result(Map.of("traceId", "raw"), false));
        useCustomizer(
                (context, resultMetadata) -> {
                    resultMetadata.put("drop", "value");
                    resultMetadata.remove("drop");
                });

        ToolResultBlock block = call(newTool());

        assertEquals(Map.of(), block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    @Test
    void customizerMetadataDoesNotChangeToolOutputOrInputArguments() {
        stubResult(result(Map.of("traceId", "raw"), false));
        AtomicReference<Map<String, Object>> seenArguments = new AtomicReference<>();
        useCustomizer(
                (context, resultMetadata) -> {
                    seenArguments.set(context.arguments());
                    resultMetadata.put("derived", "value");
                });

        ToolResultBlock block = call(newTool());

        assertEquals("ok", text(block));
        assertEquals(Map.of("q", "x"), seenArguments.get());
        assertEquals(
                Map.of("derived", "value"),
                block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    @Test
    void frameworkRemovesNullKeysAndValuesFromCustomizedMetadata() {
        stubResult(result(Map.of("traceId", "raw"), false));
        useCustomizer(
                (context, resultMetadata) -> {
                    resultMetadata.put("drop.null.value", null);
                    resultMetadata.put(null, "drop.null.key");
                    resultMetadata.put("keep", "value");
                });

        ToolResultBlock block = call(newTool());

        assertEquals(
                Map.of("keep", "value"), block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    @Test
    void transportFailureStillProducesAnErrorBlockWithoutMetadata() {
        McpTool tool = newTool();
        when(clientWrapper.callTool(eq("test-tool"), anyMap(), any()))
                .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("boom")));

        ToolResultBlock block = tool.callAsync(param()).block();

        assertNotNull(block);
        assertEquals(ToolResultState.ERROR, block.getState());
        assertTrue(block.getMetadata().isEmpty());
        assertNull(block.getMetadata().get(McpTool.METADATA_MCP_RESULT_META));
    }

    /** Registers a customizer; the mocked wrapper only returns what it is stubbed with. */
    private void useCustomizer(McpToolResultCustomizer customizer) {
        useCustomizers(customizer);
    }

    private void useCustomizers(McpToolResultCustomizer... customizers) {
        when(clientWrapper.getResultCustomizers()).thenReturn(List.of(customizers));
    }

    private McpTool newTool() {
        return new McpTool("test-tool", "Description", parameters, clientWrapper);
    }

    private ToolResultBlock call(McpTool tool) {
        ToolResultBlock block = tool.callAsync(param()).block();
        assertNotNull(block);
        return block;
    }

    private ToolCallParam param() {
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name("test-tool")
                                .input(Map.of("q", "x"))
                                .build())
                .input(Map.of("q", "x"))
                .build();
    }

    private void stubResult(McpSchema.CallToolResult result) {
        when(clientWrapper.callTool(eq("test-tool"), anyMap(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(result));
    }

    private static McpSchema.CallToolResult result(Map<String, Object> meta, boolean isError) {
        Map<String, Object> builderMeta = new LinkedHashMap<>(meta);
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(isError ? "failed" : "ok").build()))
                .isError(isError)
                .meta(builderMeta)
                .build();
    }

    private static String text(ToolResultBlock block) {
        assertFalse(block.getOutput().isEmpty());
        return block.getOutput().stream()
                .filter(b -> b instanceof io.agentscope.core.message.TextBlock)
                .map(b -> ((io.agentscope.core.message.TextBlock) b).getText())
                .findFirst()
                .orElse(null);
    }
}
