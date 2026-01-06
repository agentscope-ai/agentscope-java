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

import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ollama.OllamaOptions;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaMultiAgentFormatter.
 */
@DisplayName("OllamaMultiAgentFormatter Unit Tests")
class OllamaMultiAgentFormatterTest {

    private OllamaMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OllamaMultiAgentFormatter();
    }

    @Test
    @DisplayName("Should create formatter with default constructor")
    void testConstructor() {
        assertNotNull(formatter);
    }

    @Test
    @DisplayName("Should create formatter with custom conversation history prompt")
    void testConstructorWithCustomPrompt() {
        String customPrompt = "Custom history prompt";
        OllamaMultiAgentFormatter customFormatter = new OllamaMultiAgentFormatter(customPrompt);

        assertNotNull(customFormatter);
    }

    @Test
    @DisplayName("Should format single system message")
    void testFormatSystemMessage() {
        // Arrange
        Msg systemMsg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("System instructions").build())
                        .build();
        List<Msg> msgs = Arrays.asList(systemMsg);

        // Act
        List<OllamaMessage> formatted = formatter.format(msgs);

        // Assert
        assertEquals(1, formatted.size());
        assertEquals("system", formatted.get(0).getRole());
        assertEquals("System instructions", formatted.get(0).getContent());
    }

    @Test
    @DisplayName("Should format single user message")
    void testFormatUserMessage() {
        // Arrange
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();
        List<Msg> msgs = Arrays.asList(userMsg);

        // Act
        List<OllamaMessage> formatted = formatter.format(msgs);

        // Assert
        assertEquals(1, formatted.size());
        assertEquals("user", formatted.get(0).getRole());
        assertEquals("Hello", formatted.get(0).getContent());
    }

    @Test
    @DisplayName("Should format single assistant message")
    void testFormatAssistantMessage() {
        // Arrange
        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hi there").build())
                        .build();
        List<Msg> msgs = Arrays.asList(assistantMsg);

        // Act
        List<OllamaMessage> formatted = formatter.format(msgs);

        // Assert
        assertEquals(1, formatted.size());
        assertEquals("assistant", formatted.get(0).getRole());
        assertEquals("Hi there", formatted.get(0).getContent());
    }

    @Test
    @DisplayName("Should format multi-agent conversation with system message")
    void testFormatMultiAgentWithSystemMessage() {
        // Arrange
        Msg systemMsg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("System instructions").build())
                        .build();
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello from Alice").build())
                        .build();
        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(TextBlock.builder().text("Hi Alice").build())
                        .build();
        List<Msg> msgs = Arrays.asList(systemMsg, userMsg, assistantMsg);

        // Act
        List<OllamaMessage> formatted = formatter.format(msgs);

        // Assert
        assertEquals(2, formatted.size());
        assertEquals("system", formatted.get(0).getRole());
        assertEquals("user", formatted.get(1).getRole());
    }

    @Test
    @DisplayName("Should format tool result message")
    void testFormatToolResultMessage() {
        // Arrange
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "calc",
                        java.util.List.of(TextBlock.builder().text("Result: 42").build()),
                        null);
        Msg toolMsg = Msg.builder().role(MsgRole.TOOL).content(toolResult).build();
        List<Msg> msgs = Arrays.asList(toolMsg);

        // Act
        List<OllamaMessage> formatted = formatter.format(msgs);

        // Assert
        assertEquals(1, formatted.size());
        assertEquals("tool", formatted.get(0).getRole());
        assertEquals("Result: 42", formatted.get(0).getContent());
    }

    @Test
    @DisplayName("Should format assistant message with tool use")
    void testFormatAssistantWithToolUse() {
        // Arrange
        ToolUseBlock toolUse =
                new ToolUseBlock(
                        "call123", "calculator", Map.of("operation", "add", "a", 5, "b", 3), null);
        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                Arrays.asList(
                                        TextBlock.builder()
                                                .text("Let me calculate that for you.")
                                                .build(),
                                        toolUse))
                        .build();
        List<Msg> msgs = Arrays.asList(assistantMsg);

        // Act
        List<OllamaMessage> formatted = formatter.format(msgs);

        // Assert
        assertEquals(1, formatted.size());
        assertEquals("assistant", formatted.get(0).getRole());
        assertEquals("Let me calculate that for you.", formatted.get(0).getContent());
    }

    @Test
    @DisplayName("Should parse response correctly")
    void testParseResponse() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");
        response.setCreatedAt("2024-01-01T00:00:00.000Z");
        response.setMessage(new OllamaMessage("assistant", "Response"));

        // Act
        ChatResponse chatResponse = formatter.parseResponse(response, Instant.now());

        // Assert
        assertNotNull(chatResponse);
        assertEquals("test-model", chatResponse.getMetadata().get("model"));
    }

    @Test
    @DisplayName("Should apply GenerateOptions correctly")
    void testApplyGenerateOptions() {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().temperature(0.5).build();

        // Act & Assert - should not throw exception
        assertDoesNotThrow(() -> formatter.applyOptions(request, options, defaultOptions));
    }

    @Test
    @DisplayName("Should apply OllamaOptions correctly")
    void testApplyOllamaOptions() {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        OllamaOptions options = OllamaOptions.builder().temperature(0.7).build();
        OllamaOptions defaultOptions = OllamaOptions.builder().temperature(0.5).build();

        // Act & Assert - should not throw exception
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

        // Act & Assert - should not throw exception
        assertDoesNotThrow(() -> formatter.applyTools(request, tools));
    }

    @Test
    @DisplayName("Should apply tool choice correctly")
    void testApplyToolChoice() {
        // Arrange
        OllamaRequest request = new OllamaRequest();
        ToolChoice toolChoice = new ToolChoice.Auto();

        // Act & Assert - should not throw exception
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

    @Test
    @DisplayName("Should convert tool result to string")
    void testConvertToolResultToString() {
        // Arrange
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "calc",
                        List.of(TextBlock.builder().text("Result: 42").build()),
                        null);
        List<ContentBlock> blocks = Arrays.asList(toolResult);

        // Act
        String result = formatter.convertToolResultToString(blocks);

        // Assert
        assertEquals("Result: 42", result);
    }
}
