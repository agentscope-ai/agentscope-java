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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolResultContextTest {

    @Test
    void defensivelyCopiesArgumentsAndResultMeta() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("q", "x");
        Map<String, Object> resultMeta = new LinkedHashMap<>();
        resultMeta.put("traceId", "trace-1");
        resultMeta.put("nullable", null);
        McpSchema.CallToolResult result = result(resultMeta);

        McpToolResultContext context =
                new McpToolResultContext(
                        "local-name", "remote-name", "call-1", arguments, resultMeta, result);

        arguments.put("q", "changed");
        resultMeta.clear();

        assertEquals("x", context.arguments().get("q"));
        assertEquals("trace-1", context.resultMeta().get("traceId"));
        assertTrue(context.resultMeta().containsKey("nullable"));
        assertSame(result, context.result());
        assertThrows(UnsupportedOperationException.class, () -> context.arguments().put("a", "b"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.resultMeta().put("traceId", "changed"));
    }

    @Test
    void nullOrEmptyMapsNormalizeToEmptyImmutableMaps() {
        McpSchema.CallToolResult result = result(Map.of());

        McpToolResultContext context =
                new McpToolResultContext(null, null, null, null, null, result);

        assertTrue(context.arguments().isEmpty());
        assertTrue(context.resultMeta().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> context.arguments().put("a", "b"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.resultMeta().put("traceId", "changed"));
    }

    private static McpSchema.CallToolResult result(Map<String, Object> meta) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder("ok").build()))
                .meta(new LinkedHashMap<>(meta))
                .build();
    }
}
