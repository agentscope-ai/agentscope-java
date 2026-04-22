/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.formatter.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaMessageConverter.
 */
@DisplayName("OllamaMessageConverter Unit Tests")
class OllamaMessageConverterTest {

    private OllamaMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OllamaMessageConverter();
    }

    @Test
    @DisplayName("Should create converter successfully")
    void testConstructor() {
        assertNotNull(converter);
    }

    @Test
    @DisplayName("Should convert user message with text content")
    void testConvertUserMessageWithText() {
        // Arrange
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello, how are you?").build())
                        .build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertEquals("Hello, how are you?", ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should convert assistant message with text content")
    void testConvertAssistantMessageWithText() {
        // Arrange
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I'm doing well, thank you!").build())
                        .build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("assistant", ollamaMsg.getRole());
        assertEquals("I'm doing well, thank you!", ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should convert message with multiple text blocks")
    void testConvertMessageWithMultipleTextBlocks() {
        // Arrange
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                Arrays.asList(
                                        TextBlock.builder().text("First part").build(),
                                        TextBlock.builder().text("Second part").build()))
                        .build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertEquals("First partSecond part", ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should convert tool result message")
    void testConvertToolResultMessage() {
        // Arrange
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "test_tool",
                        List.of(TextBlock.builder().text("Tool result output").build()),
                        null);
        Msg msg = Msg.builder().role(MsgRole.TOOL).content(toolResult).build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("tool", ollamaMsg.getRole());
        assertEquals("Tool result output", ollamaMsg.getContent());
        assertEquals("call123", ollamaMsg.getToolCallId());
        assertEquals("test_tool", ollamaMsg.getName());
    }

    @Test
    @DisplayName("Should convert assistant message with tool use")
    void testConvertAssistantMessageWithToolUse() {
        // Arrange
        ToolUseBlock toolUse =
                new ToolUseBlock(
                        "call123", "calculator", Map.of("operation", "add", "a", 5, "b", 3), null);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                Arrays.asList(
                                        TextBlock.builder()
                                                .text("Let me calculate that for you.")
                                                .build(),
                                        toolUse))
                        .build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("assistant", ollamaMsg.getRole());
        assertEquals("Let me calculate that for you.", ollamaMsg.getContent());
        assertNotNull(ollamaMsg.getToolCalls());
        assertEquals(1, ollamaMsg.getToolCalls().size());
        assertEquals("calculator", ollamaMsg.getToolCalls().get(0).getFunction().getName());
        assertEquals(5, ollamaMsg.getToolCalls().get(0).getFunction().getArguments().get("a"));
    }

    @Test
    @DisplayName("Should handle message with only tool use and no text")
    void testConvertMessageWithOnlyToolUse() {
        // Arrange
        ToolUseBlock toolUse =
                new ToolUseBlock("call456", "search", Map.of("query", "weather"), null);
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("assistant", ollamaMsg.getRole());
        assertNull(ollamaMsg.getContent()); // No text content
        assertNotNull(ollamaMsg.getToolCalls());
        assertEquals(1, ollamaMsg.getToolCalls().size());
        assertEquals("search", ollamaMsg.getToolCalls().get(0).getFunction().getName());
    }

    @Test
    @DisplayName("Should handle message with null content")
    void testConvertMessageWithNullContent() {
        // Arrange
        Msg msg = Msg.builder().role(MsgRole.USER).build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertNull(ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should handle message with empty content list")
    void testConvertMessageWithEmptyContentList() {
        // Arrange
        Msg msg = Msg.builder().role(MsgRole.USER).content(Arrays.asList()).build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertNull(ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should extract text content from tool result when no direct content")
    void testExtractTextContentFromToolResult() {
        // This test verifies the internal extractTextContent method behavior
        // Arrange
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "test_tool",
                        List.of(TextBlock.builder().text("Tool output").build()),
                        null);
        TextBlock textBlock = TextBlock.builder().text("Additional text").build();
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(Arrays.asList(textBlock, toolResult))
                        .build();

        // Act
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // Assert
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertEquals("Additional text", ollamaMsg.getContent());
    }
}
