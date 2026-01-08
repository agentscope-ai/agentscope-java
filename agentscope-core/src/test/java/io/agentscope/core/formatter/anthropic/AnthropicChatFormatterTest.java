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
package io.agentscope.core.formatter.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicMessage;
import io.agentscope.core.formatter.anthropic.dto.AnthropicRequest;
import io.agentscope.core.formatter.anthropic.dto.AnthropicResponse;
import io.agentscope.core.formatter.anthropic.dto.AnthropicUsage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicChatFormatter. */
class AnthropicChatFormatterTest extends AnthropicFormatterTestBase {

    private AnthropicChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new AnthropicChatFormatter();
    }

    @Test
    void testFormatSimpleUserMessage() {
        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<AnthropicMessage> result = formatter.format(List.of(msg));

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
    }

    @Test
    void testFormatSystemMessage() {
        Msg msg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("You are helpful").build()))
                        .build();

        List<AnthropicMessage> result = formatter.format(List.of(msg));

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
    }

    @Test
    void testFormatMultipleMessages() {
        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        Msg assistantMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hi").build()))
                        .build();

        List<AnthropicMessage> result = formatter.format(List.of(userMsg, assistantMsg));

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("assistant", result.get(1).getRole());
    }

    @Test
    void testFormatEmptyMessageList() {
        List<AnthropicMessage> result = formatter.format(List.of());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseResponseWithMessage() {
        // Create mock Response using DTO
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_test");
        AnthropicContent content = AnthropicContent.text("Response");
        response.setContent(List.of(content));

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(100);
        usage.setOutputTokens(50);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = formatter.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("msg_test", chatResponse.getId());
        assertEquals(1, chatResponse.getContent().size());
        assertNotNull(chatResponse.getUsage());
        assertEquals(100L, chatResponse.getUsage().getInputTokens());
        assertEquals(50L, chatResponse.getUsage().getOutputTokens());
    }

    @Test
    void testApplySystemMessage() {
        AnthropicRequest request = new AnthropicRequest();

        Msg systemMsg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("You are helpful").build()))
                        .build();

        formatter.applySystemMessage(request, List.of(systemMsg));

        assertEquals("You are helpful", request.getSystem());
    }

    @Test
    void testApplySystemMessageWithNoSystemMessage() {
        AnthropicRequest request = new AnthropicRequest();

        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        formatter.applySystemMessage(request, List.of(userMsg));

        // System should remain null or empty
        Object system = request.getSystem();
        assertTrue(system == null || (system instanceof String && ((String) system).isEmpty()));
    }

    @Test
    void testApplySystemMessageWithEmptyMessages() {
        AnthropicRequest request = new AnthropicRequest();
        formatter.applySystemMessage(request, List.of());
        Object system = request.getSystem();
        assertTrue(system == null || (system instanceof String && ((String) system).isEmpty()));
    }

    @Test
    void testApplyOptions() {
        AnthropicRequest request = new AnthropicRequest();
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(2000).topP(0.9).build();

        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        formatter.applyOptions(request, options, defaultOptions);

        assertEquals(0.7, request.getTemperature());
        assertEquals(2000, request.getMaxTokens());
        assertEquals(0.9, request.getTopP());
    }

    @Test
    void testApplyOptionsWithNullOptions() {
        AnthropicRequest request = new AnthropicRequest();
        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.5).maxTokens(1024).build();

        formatter.applyOptions(request, null, defaultOptions);

        assertEquals(0.5, request.getTemperature());
        assertEquals(1024, request.getMaxTokens());
    }

    @Test
    void testApplyTools() {
        AnthropicRequest request = new AnthropicRequest();
        ToolSchema searchTool =
                ToolSchema.builder()
                        .name("search")
                        .description("Search the web")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build();

        // First set options, then apply tools (tools need options for tool_choice)
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();

        formatter.applyOptions(request, options, GenerateOptions.builder().build());
        formatter.applyTools(request, List.of(searchTool));

        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
        assertEquals("search", request.getTools().get(0).getName());

        assertNotNull(request.getToolChoice());
        @SuppressWarnings("unchecked")
        Map<String, Object> toolChoice = (Map<String, Object>) request.getToolChoice();
        assertEquals("auto", toolChoice.get("type"));
    }

    @Test
    void testApplyToolsWithEmptyList() {
        AnthropicRequest request = new AnthropicRequest();
        GenerateOptions options = GenerateOptions.builder().build();
        formatter.applyOptions(request, options, GenerateOptions.builder().build());
        formatter.applyTools(request, List.of());

        assertTrue(request.getTools() == null || request.getTools().isEmpty());
    }

    @Test
    void testApplyToolsWithNullList() {
        AnthropicRequest request = new AnthropicRequest();
        GenerateOptions options = GenerateOptions.builder().build();
        formatter.applyOptions(request, options, GenerateOptions.builder().build());
        formatter.applyTools(request, null);

        assertTrue(request.getTools() == null);
    }

    @Test
    void testFormatWithToolUseMessage() {
        Msg msg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("tool_123")
                                                .name("search")
                                                .input(Map.of("query", "test"))
                                                .build()))
                        .build();

        List<AnthropicMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("assistant", result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertEquals("tool_use", result.get(0).getContent().get(0).getType());
    }

    @Test
    void testFormatWithToolResultMessage() {
        Msg msg =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("tool_123")
                                                .name("search")
                                                .output(TextBlock.builder().text("Result").build())
                                                .build()))
                        .build();

        List<AnthropicMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        // Tool results are converted to USER messages
        assertEquals("user", result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertEquals("tool_result", result.get(0).getContent().get(0).getType());
    }
}
