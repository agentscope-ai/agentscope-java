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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicChatFormatter. */
class AnthropicChatFormatterTest extends AnthropicFormatterTestBase {

    private AnthropicChatFormatter formatter;

    private static com.anthropic.models.messages.TextBlock mockTextBlock() {
        return mock(com.anthropic.models.messages.TextBlock.class);
    }

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
        ContentBlock contentBlock = mock(ContentBlock.class);
        var textBlock = mockTextBlock();

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
                        .parameters(Map.of("type", "object", "properties", Map.of()))
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
    void testApplyToolsUsesDefaultToolChoiceNone() {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder();

        ToolSchema searchTool =
                ToolSchema.builder()
                        .name("search")
                        .description("Search the web")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().toolChoice(new ToolChoice.None()).build();

        formatter.applyOptions(paramsBuilder, null, defaultOptions);
        formatter.applyTools(paramsBuilder, List.of(searchTool));

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

        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isNone());
        assertTrue(params.tools().isPresent());
        assertEquals(1, params.tools().get().size());
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
                                                .input(Map.of("query", "test"))
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

    @Test
    void testFormatPdfWithRequestLevelCitations() {
        DataBlock pdf =
                DataBlock.builder()
                        .name("guide.pdf")
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data("JVBERi0xLjQK")
                                        .build())
                        .build();
        Msg msg = Msg.builder().role(MsgRole.USER).content(pdf).build();

        List<MessageParam> result =
                formatter.format(
                        List.of(msg),
                        GenerateOptions.builder().citationsEnabled(true).build(),
                        null);

        ContentBlockParam document = result.get(0).content().asBlockParams().get(0);
        assertTrue(document.isDocument());
        assertTrue(document.asDocument().citations().orElseThrow().enabled().orElseThrow());

        ContentBlockParam withoutCitations =
                formatter.format(List.of(msg)).get(0).content().asBlockParams().get(0);
        assertTrue(withoutCitations.asDocument().citations().isEmpty());
    }

    @Test
    void testFormatWithCitationsDisabledDelegatesToSingleParamFormat() {
        RecordingChatFormatter recordingFormatter = new RecordingChatFormatter();
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        List<MessageParam> result =
                recordingFormatter.format(
                        messages, GenerateOptions.builder().citationsEnabled(false).build(), null);

        assertTrue(recordingFormatter.singleArgumentFormatCalled);
        assertEquals(recordingFormatter.formattedMessages, result);
    }

    private static final class RecordingChatFormatter extends AnthropicChatFormatter {

        private final List<MessageParam> formattedMessages = List.of();
        private boolean singleArgumentFormatCalled;

        @Override
        public List<MessageParam> format(List<Msg> msgs) {
            singleArgumentFormatCalled = true;
            return formattedMessages;
        }
    }

    @Test
    void defaultFormatterPreservesSystemTextNewlines() {
        Msg system =
                Msg.builder().role(MsgRole.SYSTEM).textContent("first\nsecond\n\nthird").build();
        var params =
                MessageCreateParams.builder()
                        .model("claude-test")
                        .maxTokens(32)
                        .messages(
                                List.of(
                                        MessageParam.builder()
                                                .role(MessageParam.Role.USER)
                                                .content("hello")
                                                .build()));
        formatter.applySystemMessage(params, List.of(system));
        assertEquals("first\nsecond\n\nthird", params.build().system().orElseThrow().asString());
    }

    @Test
    void defaultFormatterMarksFailedToolResultAsError() {
        Msg error =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("failed-call")
                                        .name("search")
                                        .state(ToolResultState.ERROR)
                                        .output(TextBlock.builder().text("diagnostic").build())
                                        .build())
                        .build();
        var result =
                formatter
                        .format(List.of(error))
                        .get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asToolResult();
        assertEquals("failed-call", result.toolUseId());
        assertTrue(result.isError().orElse(false));
    }

    @Test
    void testCitationOptionsPreserveCustomDoFormat() {
        AnthropicChatFormatter custom =
                new AnthropicChatFormatter() {
                    @Override
                    public List<MessageParam> doFormat(List<Msg> messages) {
                        return List.of(
                                MessageParam.builder()
                                        .role(MessageParam.Role.USER)
                                        .content("custom formatting")
                                        .build());
                    }
                };
        var result =
                custom.format(
                        List.of(), GenerateOptions.builder().citationsEnabled(true).build(), null);
        assertEquals("custom formatting", result.get(0).content().asString());
    }

    @Test
    void testCitationOptionsPreserveCustomFormat() {
        RecordingChatFormatter custom = new RecordingChatFormatter();
        custom.format(List.of(), GenerateOptions.builder().citationsEnabled(true).build(), null);
        assertTrue(custom.singleArgumentFormatCalled);
    }

    @Test
    void testCitationsApplyToAllDocumentsAndRespectRequestOverride() {
        DataBlock pdf =
                DataBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data("JVBERi0xLjQK")
                                        .build())
                        .build();
        List<Msg> messages =
                List.of(
                        Msg.builder().role(MsgRole.USER).content(pdf).build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("pdf-tool")
                                                .name("read_pdf")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("attached")
                                                                        .build(),
                                                                pdf))
                                                .build())
                                .build());
        GenerateOptions defaults = GenerateOptions.builder().citationsEnabled(true).build();
        var enabled = formatter.format(messages, null, defaults);
        assertTrue(
                enabled.get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asDocument()
                        .citations()
                        .orElseThrow()
                        .enabled()
                        .orElseThrow());
        assertTrue(
                enabled.get(1)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asToolResult()
                        .content()
                        .orElseThrow()
                        .asBlocks()
                        .get(1)
                        .asDocument()
                        .citations()
                        .orElseThrow()
                        .enabled()
                        .orElseThrow());
        var disabled =
                formatter.format(
                        messages,
                        GenerateOptions.builder().citationsEnabled(false).build(),
                        defaults);
        assertFalse(
                disabled.get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asDocument()
                        .citations()
                        .orElseThrow()
                        .enabled()
                        .orElseThrow());
        assertFalse(
                disabled.get(1)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asToolResult()
                        .content()
                        .orElseThrow()
                        .asBlocks()
                        .get(1)
                        .asDocument()
                        .citations()
                        .orElseThrow()
                        .enabled()
                        .orElseThrow());
        assertTrue(
                formatter
                        .format(messages)
                        .get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asDocument()
                        .citations()
                        .isEmpty());
        // Configuring a later request must not mutate an earlier request.
        assertTrue(
                enabled.get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asDocument()
                        .citations()
                        .orElseThrow()
                        .enabled()
                        .orElseThrow());
        var cached = formatter.applyCacheControl(enabled.subList(0, 1));
        assertTrue(
                cached.get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asDocument()
                        .cacheControl()
                        .isPresent());
        assertTrue(
                cached.get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asDocument()
                        .citations()
                        .orElseThrow()
                        .enabled()
                        .orElseThrow());
    }
}
