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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
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
import java.util.Optional;
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

        List<MessageParam> result = formatter.format(List.of(msg));

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(MessageParam.Role.USER, result.get(0).role());
    }

    @Test
    void testFormatSystemMessage() {
        Msg msg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("You are helpful").build()))
                        .build();

        List<MessageParam> result = formatter.format(List.of(msg));

        assertNotNull(result);
        assertEquals(1, result.size());
        // First system message converted to USER
        assertEquals(MessageParam.Role.USER, result.get(0).role());
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

        List<MessageParam> result = formatter.format(List.of(userMsg, assistantMsg));

        assertEquals(2, result.size());
        assertEquals(MessageParam.Role.USER, result.get(0).role());
        assertEquals(MessageParam.Role.ASSISTANT, result.get(1).role());
    }

    @Test
    void testFormatEmptyMessageList() {
        List<MessageParam> result = formatter.format(List.of());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseResponseWithMessage() {
        // Create mock Message
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        com.anthropic.models.messages.ContentBlock contentBlock =
                mock(com.anthropic.models.messages.ContentBlock.class);
        com.anthropic.models.messages.TextBlock textBlock =
                mock(com.anthropic.models.messages.TextBlock.class);

        when(message.id()).thenReturn("msg_test");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);

        when(contentBlock.text()).thenReturn(Optional.of(textBlock));
        when(contentBlock.toolUse()).thenReturn(Optional.empty());
        when(contentBlock.thinking()).thenReturn(Optional.empty());
        when(textBlock.text()).thenReturn("Response");

        Instant startTime = Instant.now();
        ChatResponse response = formatter.parseResponse(message, startTime);

        assertNotNull(response);
        assertEquals("msg_test", response.getId());
        assertEquals(1, response.getContent().size());
        assertNotNull(response.getUsage());
    }

    @Test
    void testParseResponseWithInvalidType() {
        // Pass non-Message object should throw exception
        String invalidResponse = "not a message";
        Instant startTime = Instant.now();

        assertThrows(
                IllegalArgumentException.class,
                () -> formatter.parseResponse(invalidResponse, startTime));
    }

    @Test
    void testApplySystemMessage() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        Msg systemMsg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("You are helpful").build()))
                        .build();

        formatter.applySystemMessage(paramsBuilder, List.of(systemMsg));

        // Build and verify system message was set
        // Note: Anthropic requires at least one message, add a dummy message
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .maxTokens(1024)
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        // System message should be present in params
        // Note: We can't directly access the system field without building,
        // but we can verify no exception was thrown
        assertNotNull(params);
    }

    @Test
    void testApplySystemMessageWithNoSystemMessage() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        formatter.applySystemMessage(paramsBuilder, List.of(userMsg));

        // Should handle gracefully with no system message
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .maxTokens(1024)
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        assertNotNull(params);
    }

    @Test
    void testApplySystemMessageWithEmptyMessages() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        formatter.applySystemMessage(paramsBuilder, List.of());

        // Should handle empty list gracefully
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .maxTokens(1024)
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        assertNotNull(params);
    }

    @Test
    void testApplyOptions() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(2000).topP(0.9).build();

        GenerateOptions defaultOptions = GenerateOptions.builder().build();

        formatter.applyOptions(paramsBuilder, options, defaultOptions);

        // Build params and verify no exception
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        assertNotNull(params);
    }

    @Test
    void testApplyOptionsWithNullOptions() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.5).maxTokens(1024).build();

        formatter.applyOptions(paramsBuilder, null, defaultOptions);

        // Should use default options
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        assertNotNull(params);
    }

    @Test
    void testApplyTools() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        ToolSchema searchTool =
                ToolSchema.builder()
                        .name("search")
                        .description("Search the web")
                        .parameters(
                                java.util.Map.of(
                                        "type", "object", "properties", java.util.Map.of()))
                        .build();

        // First set options, then apply tools (tools need options for tool_choice)
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();

        formatter.applyOptions(paramsBuilder, options, GenerateOptions.builder().build());
        formatter.applyTools(paramsBuilder, List.of(searchTool));

        // Build params and verify no exception
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .maxTokens(1024)
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        assertNotNull(params);
    }

    @Test
    void testApplyToolsWithEmptyList() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        GenerateOptions options = GenerateOptions.builder().build();
        formatter.applyOptions(paramsBuilder, options, GenerateOptions.builder().build());
        formatter.applyTools(paramsBuilder, List.of());

        // Should handle empty tools gracefully
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .maxTokens(1024)
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        assertNotNull(params);
    }

    @Test
    void testApplyToolsWithNullList() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        GenerateOptions options = GenerateOptions.builder().build();
        formatter.applyOptions(paramsBuilder, options, GenerateOptions.builder().build());
        formatter.applyTools(paramsBuilder, null);

        // Should handle null tools gracefully
        MessageCreateParams params =
                paramsBuilder
                        .model("claude-3-5-sonnet-20241022")
                        .maxTokens(1024)
                        .addMessage(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content(
                                                MessageParam.Content.ofBlockParams(
                                                        List.of(
                                                                ContentBlockParam.ofText(
                                                                        TextBlockParam.builder()
                                                                                .text("test")
                                                                                .build()))))
                                        .build())
                        .build();

        assertNotNull(params);
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
                                                .input(java.util.Map.of("query", "test"))
                                                .build()))
                        .build();

        List<MessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals(MessageParam.Role.ASSISTANT, result.get(0).role());
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

        List<MessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        // Tool results are converted to USER messages
        assertEquals(MessageParam.Role.USER, result.get(0).role());
    }
}
