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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolResultMessageBuilder}.
 */
@Tag("unit")
@DisplayName("ToolResultMessageBuilder Unit Tests")
class ToolResultMessageBuilderTest {

    @Test
    @DisplayName("Should build tool result message with id, name, and title from original call")
    void testBuildToolResultMsgWithTitle() {
        ToolUseBlock originalCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("search_tool")
                        .title("Search Tool")
                        .input(Map.of("query", "hello"))
                        .build();

        ToolResultBlock result =
                ToolResultBlock.of(
                        "result",
                        "search_tool",
                        TextBlock.builder().text("found 5 results").build());

        Msg msg = ToolResultMessageBuilder.buildToolResultMsg(result, originalCall, "agent-1");

        assertEquals("agent-1", msg.getName());
        assertEquals(MsgRole.TOOL, msg.getRole());

        ToolResultBlock toolResult = (ToolResultBlock) msg.getContent().get(0);
        assertEquals("call-1", toolResult.getId());
        assertEquals("search_tool", toolResult.getName());
        assertEquals("Search Tool", toolResult.getTitle());
    }

    @Test
    @DisplayName("Should build tool result message with null title")
    void testBuildToolResultMsgWithNullTitle() {
        ToolUseBlock originalCall = new ToolUseBlock("call-2", "simple_tool", null, Map.of(), null);

        ToolResultBlock result =
                ToolResultBlock.of(null, "simple_tool", TextBlock.builder().text("done").build());

        Msg msg = ToolResultMessageBuilder.buildToolResultMsg(result, originalCall, "agent-2");

        ToolResultBlock toolResult = (ToolResultBlock) msg.getContent().get(0);
        assertEquals("call-2", toolResult.getId());
        assertEquals("simple_tool", toolResult.getName());
        assertNull(toolResult.getTitle());
    }

    @Test
    @DisplayName("Should preserve output blocks from result")
    void testBuildPreservesOutputBlocks() {
        ToolUseBlock originalCall =
                new ToolUseBlock("call-3", "multi_output_tool", Map.of("x", 1), null);

        ToolResultBlock result =
                ToolResultBlock.of(
                        null,
                        "multi_output_tool",
                        List.of(
                                TextBlock.builder().text("result 1").build(),
                                TextBlock.builder().text("result 2").build()));

        Msg msg = ToolResultMessageBuilder.buildToolResultMsg(result, originalCall, "agent-3");

        ToolResultBlock toolResult = (ToolResultBlock) msg.getContent().get(0);
        assertEquals(2, toolResult.getOutput().size());
        assertEquals("result 1", ((TextBlock) toolResult.getOutput().get(0)).getText());
        assertEquals("result 2", ((TextBlock) toolResult.getOutput().get(1)).getText());
    }

    @Test
    @DisplayName("Should propagate title from ToolUseBlock with custom title")
    void testBuildWithCustomTitle() {
        ToolUseBlock originalCall =
                ToolUseBlock.builder()
                        .id("call-4")
                        .name("query_db")
                        .title("Query Database")
                        .input(Map.of("sql", "SELECT *"))
                        .build();

        ToolResultBlock result =
                ToolResultBlock.of(null, "query_db", TextBlock.builder().text("rows: 10").build());

        Msg msg = ToolResultMessageBuilder.buildToolResultMsg(result, originalCall, "agent-4");

        ToolResultBlock toolResult = (ToolResultBlock) msg.getContent().get(0);
        assertEquals("Query Database", toolResult.getTitle());
    }
}
