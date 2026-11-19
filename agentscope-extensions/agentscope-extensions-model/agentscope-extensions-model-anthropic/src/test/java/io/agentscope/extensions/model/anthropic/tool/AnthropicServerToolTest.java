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
package io.agentscope.extensions.model.anthropic.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebFetchTool20250910;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicServerTool. */
class AnthropicServerToolTest {

    @Test
    void testSdkToolUnionEntry() {
        ToolUnion toolUnion =
                ToolUnion.ofWebFetchTool20250910(WebFetchTool20250910.builder().maxUses(7).build());

        AnthropicServerTool tool = AnthropicServerTool.of(toolUnion);

        assertEquals("web_fetch", tool.getName());
        assertSame(toolUnion, tool.toToolUnion());
        assertSame(tool.toToolUnion(), tool.toToolUnion());
    }

    @Test
    void testSdkToolUnionRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicServerTool.of(null));
    }

    @Test
    void testSdkToolUnionRejectsCustomClientTool() {
        Tool customTool =
                Tool.builder()
                        .name("local_tool")
                        .inputSchema(Tool.InputSchema.builder().build())
                        .build();

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AnthropicServerTool.of(ToolUnion.ofTool(customTool)));

        assertTrue(exception.getMessage().contains("custom client tools"));
    }

    @Test
    void testSdkBuilderAdditionalPropertyPassesThrough() throws Exception {
        ToolUnion toolUnion =
                ToolUnion.ofWebFetchTool20250910(
                        WebFetchTool20250910.builder()
                                .maxUses(7)
                                .putAdditionalProperty("future_param", JsonValue.from(1))
                                .build());

        AnthropicServerTool tool = AnthropicServerTool.of(toolUnion);

        String json = ObjectMappers.jsonMapper().writeValueAsString(tool.toToolUnion());
        assertTrue(json.contains("\"future_param\":1"));
    }
}
