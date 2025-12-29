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
package io.agentscope.core.formatter.ollama;

import static org.junit.jupiter.api.Assertions.*;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ollama.OllamaOptions;
import java.time.Instant;
import java.util.Arrays;
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

    @BeforeEach
    void setUp() {
        formatter = new OllamaChatFormatter();
    }

    @Test
    @DisplayName("Should create formatter with default components")
    void testConstructor() {
        assertNotNull(formatter);
    }

    @Test
    @DisplayName("Should format messages correctly")
    void testFormatMessages() {
        // Arrange
        Msg msg1 = Msg.builder().role(MsgRole.USER).name("Alice").content(TextBlock.builder().text("Hello").build()).build();
        Msg msg2 = Msg.builder().role(MsgRole.ASSISTANT).name("Bob").content(TextBlock.builder().text("Hi there").build()).build();
        List<Msg> msgs = Arrays.asList(msg1, msg2);

        // Act
        List<OllamaMessage> formatted = formatter.format(msgs);

        // Assert
        assertEquals(2, formatted.size());
        assertEquals("user", formatted.get(0).getRole());
        assertEquals("Hello", formatted.get(0).getContent());
        assertEquals("assistant", formatted.get(1).getRole());
        assertEquals("Hi there", formatted.get(1).getContent());
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
        ToolSchema tool1 = ToolSchema.builder().name("test_tool").description("A test tool").build();
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
        OllamaRequest request = formatter.buildRequest(
                model, messages, stream, options, defaultOptions, tools, toolChoice);

        // Assert
        assertNotNull(request);
        assertEquals(model, request.getModel());
        assertEquals(messages, request.getMessages());
        assertEquals(stream, request.getStream());
    }
}