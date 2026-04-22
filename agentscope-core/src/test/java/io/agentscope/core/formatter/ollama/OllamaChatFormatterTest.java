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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ollama.OllamaOptions;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaChatFormatter.
 */
@DisplayName("OllamaChatFormatter Unit Tests")
class OllamaChatFormatterTest {

    private OllamaChatFormatter formatter;
    private String imagePath;

    @BeforeEach
    void setUp() {
        formatter = new OllamaChatFormatter();
        imagePath = "src/test/resources/dog.png";
    }

    @Test
    @DisplayName("Should create formatter with default components")
    void testConstructor() {
        assertNotNull(formatter);
    }

    @Test
    @DisplayName("Should format messages correctly - aligned with Python test_chat_formatter")
    void testFormatMessages() {
        // Arrange: System messages
        List<Msg> msgsSystem =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .name("system")
                                .content(
                                        TextBlock.builder()
                                                .text("You're a helpful assistant.")
                                                .build())
                                .build());

        // Arrange: Conversation messages
        List<Msg> msgsConversation =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        Arrays.asList(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imagePath)
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build())
                                .build());

        // Arrange: Tool messages
        List<Msg> msgsTools =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        Arrays.asList(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(
                                                                Collections.singletonMap(
                                                                        "country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .name("system")
                                .content(
                                        Arrays.asList(
                                                ToolResultBlock.of(
                                                        "1",
                                                        "get_capital",
                                                        Arrays.asList(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build()))))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build())
                                .build());

        // Act
        List<OllamaMessage> formatted =
                formatter.format(concatLists(msgsSystem, msgsConversation, msgsTools));

        // Assert: Check the expected result matches Python's ground_truth_chat
        assertEquals(7, formatted.size());

        assertEquals("system", formatted.get(0).getRole());
        assertEquals("You're a helpful assistant.", formatted.get(0).getContent());

        assertEquals("user", formatted.get(1).getRole());
        assertEquals("What is the capital of France?", formatted.get(1).getContent());
        // We can't check the exact Base64 content as it will be different for the actual image,
        // but we can check that images list is not null and has one item
        if (formatted.get(1).getImages() != null) {
            assertEquals(1, formatted.get(1).getImages().size());
        }

        assertEquals("assistant", formatted.get(2).getRole());
        assertEquals("The capital of France is Paris.", formatted.get(2).getContent());

        assertEquals("user", formatted.get(3).getRole());
        assertEquals("What is the capital of Japan?", formatted.get(3).getContent());

        assertEquals("assistant", formatted.get(4).getRole());
        assertTrue(
                formatted.get(4).getToolCalls() != null
                        && formatted.get(4).getToolCalls().size() > 0,
                "Tool calls should exist");
        if (formatted.get(4).getToolCalls() != null && !formatted.get(4).getToolCalls().isEmpty()) {
            assertEquals(
                    "get_capital", formatted.get(4).getToolCalls().get(0).getFunction().getName());
            assertEquals(
                    "Japan",
                    formatted
                            .get(4)
                            .getToolCalls()
                            .get(0)
                            .getFunction()
                            .getArguments()
                            .get("country"));
        }

        assertEquals("tool", formatted.get(5).getRole());
        assertEquals("1", formatted.get(5).getToolCallId());
        assertEquals("get_capital", formatted.get(5).getName());
        assertNotNull(formatted.get(5).getContent());
        assertTrue(formatted.get(5).getContent().contains("The capital of Japan is Tokyo."));

        assertEquals("assistant", formatted.get(6).getRole());
        assertEquals("The capital of Japan is Tokyo.", formatted.get(6).getContent());
    }

    @Test
    @DisplayName(
            "Should format messages without system message - aligned with Python"
                    + " test_chat_formatter")
    void testFormatMessagesWithoutSystem() {
        // Arrange: Conversation and tools without system message
        List<Msg> msgsConversation =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of France?")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build())
                                .build());

        List<Msg> msgsTools =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        Arrays.asList(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(
                                                                Collections.singletonMap(
                                                                        "country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .name("system")
                                .content(
                                        Arrays.asList(
                                                ToolResultBlock.of(
                                                        "1",
                                                        "get_capital",
                                                        Arrays.asList(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build()))))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build())
                                .build());

        // Act
        List<OllamaMessage> formatted = formatter.format(concatLists(msgsConversation, msgsTools));

        // Assert: Should match ground_truth_chat[1:] (without system message)
        assertEquals(6, formatted.size());
        assertEquals("user", formatted.get(0).getRole());
        assertEquals("assistant", formatted.get(1).getRole());
        assertEquals("user", formatted.get(2).getRole());
        assertEquals("assistant", formatted.get(3).getRole());
        assertEquals("tool", formatted.get(4).getRole());
        assertEquals("assistant", formatted.get(5).getRole());
    }

    @Test
    @DisplayName(
            "Should format messages with promote tool result images - aligned with Python"
                    + " test_chat_formatter_with_extract_image_blocks")
    void testFormatMessagesWithPromoteToolResultImages() {
        // Arrange: Create a formatter with promoteToolResultImages = true
        OllamaChatFormatter formatterWithPromote = new OllamaChatFormatter(true);

        // Arrange: System messages
        List<Msg> msgsSystem =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .name("system")
                                .content(
                                        TextBlock.builder()
                                                .text("You're a helpful assistant.")
                                                .build())
                                .build());

        // Arrange: Conversation messages
        List<Msg> msgsConversation =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        Arrays.asList(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imagePath)
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build())
                                .build());

        // Arrange: Tool messages
        List<Msg> msgsTools =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        Arrays.asList(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(
                                                                Collections.singletonMap(
                                                                        "country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .name("system")
                                .content(
                                        Arrays.asList(
                                                ToolResultBlock.of(
                                                        "1",
                                                        "get_capital",
                                                        Arrays.asList(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build()))))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build())
                                .build());

        // Act
        List<OllamaMessage> formatted =
                formatterWithPromote.format(concatLists(msgsSystem, msgsConversation, msgsTools));

        // Assert: Should have an extra message for the promoted image (8 instead of 7)
        assertEquals(8, formatted.size());

        assertEquals("system", formatted.get(0).getRole());
        assertEquals("user", formatted.get(1).getRole());
        assertEquals("assistant", formatted.get(2).getRole());
        assertEquals("user", formatted.get(3).getRole());
        assertEquals("assistant", formatted.get(4).getRole());
        assertEquals("tool", formatted.get(5).getRole());

        // Check the promoted image message (this should be at index 6)
        assertEquals("user", formatted.get(6).getRole());
        if (formatted.get(6).getContent() != null) {
            assertTrue(
                    formatted.get(6).getContent().contains("image contents from the tool result"));
            assertTrue(formatted.get(6).getContent().contains("get_capital"));
        }
        if (formatted.get(6).getImages() != null) {
            assertEquals(1, formatted.get(6).getImages().size());
        }

        assertEquals("assistant", formatted.get(7).getRole());
    }

    @Test
    @DisplayName("Should parse response correctly")
    void testParseResponse() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");
        response.setCreatedAt("2024-01-01T00:00:00.000Z");
        response.setMessage(new OllamaMessage("assistant", "Hello"));

        // Act
        ChatResponse chatResponse = formatter.parseResponse(response, Instant.now());

        // Assert
        assertNotNull(chatResponse);
        assertEquals("test-model", chatResponse.getMetadata().get("model"));
    }

    @Test
    @DisplayName("Should apply generate options correctly")
    void testApplyGenerateOptions() {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().temperature(0.5).build();

        // Act & Assert - this should not throw an exception
        assertDoesNotThrow(() -> formatter.applyOptions(request, options, defaultOptions));
    }

    @Test
    @DisplayName("Should apply Ollama options correctly")
    void testApplyOllamaOptions() {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        OllamaOptions options = OllamaOptions.builder().temperature(0.7).build();
        OllamaOptions defaultOptions = OllamaOptions.builder().temperature(0.5).build();

        // Act & Assert - this should not throw an exception
        assertDoesNotThrow(() -> formatter.applyOptions(request, options, defaultOptions));
    }

    @Test
    @DisplayName("Should apply tools correctly")
    void testApplyTools() {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        ToolSchema tool1 =
                ToolSchema.builder().name("test_tool").description("A test tool").build();
        List<ToolSchema> tools = Arrays.asList(tool1);

        // Act & Assert - this should not throw an exception
        assertDoesNotThrow(() -> formatter.applyTools(request, tools));
    }

    @Test
    @DisplayName("Should apply tool choice correctly")
    void testApplyToolChoice() {
        // Test with Specific tool choice
        OllamaRequest request = new OllamaRequest();
        ToolChoice toolChoice = new ToolChoice.Specific("test_tool");

        // Act & Assert - this should not throw an exception
        assertDoesNotThrow(() -> formatter.applyToolChoice(request, toolChoice));
    }

    @Test
    @DisplayName("Should build complete request with all parameters")
    void testBuildRequest() {
        // Arrange
        String model = "test-model";
        OllamaMessage msg1 = new OllamaMessage("user", "Hello");
        List<OllamaMessage> messages = Arrays.asList(msg1);
        boolean stream = false;
        OllamaOptions options = OllamaOptions.builder().temperature(0.7).build();
        OllamaOptions defaultOptions = OllamaOptions.builder().temperature(0.5).build();
        ToolSchema tool = ToolSchema.builder().name("test_tool").description("A test tool").build();
        List<ToolSchema> tools = Arrays.asList(tool);
        ToolChoice toolChoice = new ToolChoice.Auto();

        // Act
        OllamaRequest request =
                formatter.buildRequest(
                        model, messages, stream, options, defaultOptions, tools, toolChoice);

        // Assert
        assertNotNull(request);
        assertEquals(model, request.getModel());
        assertEquals(messages, request.getMessages());
        assertEquals(stream, request.getStream());
    }

    // Helper method to concatenate lists
    private <T> List<T> concatLists(List<T>... lists) {
        return Arrays.stream(lists).flatMap(List::stream).toList();
    }
}
