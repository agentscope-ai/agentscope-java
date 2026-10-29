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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.search.ToolSearchTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicClientToolSearchTest {
    @Test
    void deferredSchemasNeverReceiveCacheControl() {
        var search =
                ToolSchema.builder()
                        .name("tool_search")
                        .description("Discover")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build();
        var order =
                ToolSchema.builder()
                        .name("get_order")
                        .description("Orders")
                        .deferLoading(true)
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build();
        var builder =
                MessageCreateParams.builder()
                        .model("test")
                        .maxTokens(100)
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content("test")
                                        .build());
        AnthropicToolsHelper.applyTools(
                builder,
                List.of(search, order),
                GenerateOptions.builder().cacheControl(true).build());
        var tools = builder.build().tools().orElseThrow();
        assertTrue(tools.get(0).asTool().cacheControl().isPresent());
        assertTrue(tools.get(1).asTool().deferLoading().orElseThrow());
        assertTrue(tools.get(1).asTool().cacheControl().isEmpty());
    }

    @Test
    void customSearchReturnsNativeToolReferences() {
        var result =
                ToolResultBlock.builder()
                        .id("search_call")
                        .name("tool_search")
                        .state(ToolResultState.SUCCESS)
                        .output(TextBlock.builder().text("Found order").build())
                        .metadata(
                                Map.of(
                                        ToolSearchTool.METADATA_MODE,
                                        "DEFERRED",
                                        ToolSearchTool.METADATA_TOOLS,
                                        List.of(Map.of("name", "get_order"))))
                        .build();
        var converter = new AnthropicMessageConverter(blocks -> "text");
        var messages =
                converter.convert(List.of(ToolResultMessage.builder().content(result).build()));
        var blocks =
                messages.get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asToolResult()
                        .content()
                        .orElseThrow()
                        .asBlocks();
        assertEquals(
                "get_order",
                blocks.stream()
                        .filter(ToolResultBlockParam.Content.Block::isToolReference)
                        .findFirst()
                        .orElseThrow()
                        .asToolReference()
                        .toolName());
        var generic = ToolResultBlock.success("Found order").withIdAndName("call", "tool_search");
        var genericBlocks =
                converter
                        .convert(List.of(ToolResultMessage.builder().content(generic).build()))
                        .get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asToolResult()
                        .content()
                        .orElseThrow()
                        .asBlocks();
        assertTrue(
                genericBlocks.stream()
                        .noneMatch(ToolResultBlockParam.Content.Block::isToolReference));
    }
}
