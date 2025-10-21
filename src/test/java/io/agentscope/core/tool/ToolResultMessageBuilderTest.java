/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
    void testBuildWithSingleTextBlock() {
        // Arrange
        ToolUseBlock originalCall =
                ToolUseBlock.builder()
                        .id("tool_123")
                        .name("test_tool")
                        .input(Map.of("param", "value"))
                        .build();

        ToolResponse response =
                new ToolResponse(List.of(TextBlock.builder().text("Success result").build()));

        // Act
        Msg result =
                ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "TestAgent");

        // Assert
        assertNotNull(result);
        assertEquals("TestAgent", result.getName());
        assertEquals(MsgRole.TOOL, result.getRole());

        ContentBlock content = result.getContent();
        assertTrue(content instanceof ToolResultBlock);

        ToolResultBlock toolResult = (ToolResultBlock) content;
        assertEquals("tool_123", toolResult.getId());
        assertEquals("test_tool", toolResult.getName());

        ContentBlock output = toolResult.getOutput();
        assertTrue(output instanceof TextBlock);
        assertEquals("Success result", ((TextBlock) output).getText());
    }

    @Test
    @DisplayName("Should aggregate multiple text blocks with newlines")
    void testBuildWithMultipleTextBlocks() {
        // Arrange
        ToolUseBlock originalCall =
                ToolUseBlock.builder()
                        .id("tool_456")
                        .name("multi_output_tool")
                        .input(Map.of())
                        .build();

        ToolResponse response =
                new ToolResponse(
                        List.of(
                                TextBlock.builder().text("First line").build(),
                                TextBlock.builder().text("Second line").build(),
                                TextBlock.builder().text("Third line").build()));

        // Act
        Msg result =
                ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "TestAgent");

        // Assert
        ToolResultBlock toolResult = (ToolResultBlock) result.getContent();
        TextBlock output = (TextBlock) toolResult.getOutput();
        assertEquals("First line\nSecond line\nThird line", output.getText());
    }

    @Test
    @DisplayName("Should handle empty content list")
    void testBuildWithEmptyContent() {
        // Arrange
        ToolUseBlock originalCall =
                ToolUseBlock.builder().id("tool_789").name("empty_tool").input(Map.of()).build();

        ToolResponse response = new ToolResponse(List.of());

        // Act
        Msg result =
                ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "TestAgent");

        // Assert
        ToolResultBlock toolResult = (ToolResultBlock) result.getContent();
        TextBlock output = (TextBlock) toolResult.getOutput();
        assertEquals("", output.getText());
    }

    @Test
    @DisplayName("Should handle null content list")
    void testBuildWithNullContent() {
        // Arrange
        ToolUseBlock originalCall =
                ToolUseBlock.builder().id("tool_000").name("null_tool").input(Map.of()).build();

        ToolResponse response = new ToolResponse(null);

        // Act
        Msg result =
                ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "TestAgent");

        // Assert
        ToolResultBlock toolResult = (ToolResultBlock) result.getContent();
        TextBlock output = (TextBlock) toolResult.getOutput();
        assertEquals("", output.getText());
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

        ToolResponse response =
                new ToolResponse(List.of(TextBlock.builder().text("Result").build()));

        // Act
        Msg result = ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, "Agent");

        // Assert
        ToolResultBlock toolResult = (ToolResultBlock) result.getContent();
        assertEquals(toolId, toolResult.getId());
        assertEquals(toolName, toolResult.getName());
    }
}
