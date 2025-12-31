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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolResultMessageBuilderTest {

    @Test
    @DisplayName("Should build tool result message with single text block")
    void testBuildWithSingleThinkingBlock() {
        // Arrange
        ToolUseBlock originalCall =
                ToolUseBlock.builder()
                        .id("tool_123")
                        .name("test_tool")
                        .input(Map.of("param", "value"))
                        .build();

        ToolResultBlock response =
                ToolResultBlock.of(TextBlock.builder().text("Success result").build());

        // Act
        Msg result =
                ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "TestAgent");

        // Assert
        assertNotNull(result);
        assertEquals("TestAgent", result.getName());
        assertEquals(MsgRole.TOOL, result.getRole());

        ContentBlock content = result.getFirstContentBlock();
        assertTrue(content instanceof ToolResultBlock);

        ToolResultBlock toolResult = (ToolResultBlock) content;
        assertEquals("tool_123", toolResult.getId());
        assertEquals("test_tool", toolResult.getName());

        List<ContentBlock> outputs = toolResult.getOutput();
        assertEquals(1, outputs.size());
        assertTrue(outputs.get(0) instanceof TextBlock);
        assertEquals("Success result", ((TextBlock) outputs.get(0)).getText());
    }

    @Test
    @DisplayName("Should handle null content list")
    void testBuildWithNullContent() {
        // Arrange
        ToolUseBlock originalCall =
                ToolUseBlock.builder().id("tool_000").name("null_tool").input(Map.of()).build();

        ToolResultBlock response = ToolResultBlock.of((List<ContentBlock>) null);

        // Act
        Msg result =
                ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "TestAgent");

        // Assert
        ToolResultBlock toolResult = (ToolResultBlock) result.getFirstContentBlock();
        List<ContentBlock> outputs = toolResult.getOutput();
        assertTrue(outputs.isEmpty());
    }

    @Test
    @DisplayName("Should preserve original tool call ID and name")
    void testPreservesOriginalCallInfo() {
        // Arrange
        String toolId = "unique_tool_id_12345";
        String toolName = "important_tool";

        ToolUseBlock originalCall =
                ToolUseBlock.builder()
                        .id(toolId)
                        .name(toolName)
                        .input(Map.of("key", "value"))
                        .build();

        ToolResultBlock response = ToolResultBlock.of(TextBlock.builder().text("Result").build());

        // Act
        Msg result = ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "Agent");

        // Assert
        ToolResultBlock toolResult = (ToolResultBlock) result.getFirstContentBlock();
        assertEquals(toolId, toolResult.getId());
        assertEquals(toolName, toolResult.getName());
    }
}
