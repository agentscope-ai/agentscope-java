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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicMessageConverter. */
class AnthropicMessageConverterTest extends AnthropicFormatterTestBase {

    private AnthropicMessageConverter converter;

    @BeforeEach
    void setUp() {
        // Use identity converter for tool results (just concatenate text)
        converter =
                new AnthropicMessageConverter(
                        blocks -> {
                            StringBuilder sb = new StringBuilder();
                            for (ContentBlock block : blocks) {
                                if (block instanceof TextBlock tb) {
                                    sb.append(tb.getText());
                                }
                            }
                            return sb.toString();
                        });
    }

    @Test
    void testConvertSimpleUserMessage() {
        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        MessageParam param = result.get(0);
        assertEquals(MessageParam.Role.USER, param.role());
        assertTrue(param.content().isBlockParams());
        List<ContentBlockParam> blocks = param.content().asBlockParams();
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).isText());
        assertEquals("Hello", blocks.get(0).asText().text());
    }

    @Test
    void testConvertAssistantMessage() {
        Msg msg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hi there").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        MessageParam param = result.get(0);
        assertEquals(MessageParam.Role.ASSISTANT, param.role());
    }

    @Test
    void testConvertSystemMessageFirst() {
        Msg msg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("System prompt").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        MessageParam param = result.get(0);
        // First system message is converted to USER in Anthropic
        assertEquals(MessageParam.Role.USER, param.role());
    }

    @Test
    void testConvertSystemMessageNotFirst() {
        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        Msg systemMsg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("Note").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(userMsg, systemMsg));

        assertEquals(2, result.size());
        // Both converted to USER
        assertEquals(MessageParam.Role.USER, result.get(0).role());
        assertEquals(MessageParam.Role.USER, result.get(1).role());
    }

    @Test
    void testConvertMultipleTextBlocks() {
        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First").build(),
                                        TextBlock.builder().text("Second").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertEquals(2, blocks.size());
        assertEquals("First", blocks.get(0).asText().text());
        assertEquals("Second", blocks.get(1).asText().text());
    }

    @Test
    void testConvertImageBlock() {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/png")
                        .build();

        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(ImageBlock.builder().source(source).build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).isImage());
    }

    @Test
    void testConvertThinkingBlock() {
        Msg msg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder()
                                                .thinking("Let me think...")
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).isText());
        assertEquals("Let me think...", blocks.get(0).asText().text());
    }

    @Test
    void testConvertToolUseBlock() {
        Map<String, Object> input = Map.of("query", "test");
        Msg msg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .input(input)
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).isToolUse());

        ToolUseBlockParam toolUse = blocks.get(0).asToolUse();
        assertEquals("call_123", toolUse.id());
        assertEquals("search", toolUse.name());
        // Note: input validation happens during API calls, not during conversion
    }

    @Test
    void testConvertToolResultBlockString() {
        Msg msg =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("Result text")
                                                                .build())
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        // Tool result creates separate user message
        assertEquals(1, result.size());
        MessageParam param = result.get(0);
        assertEquals(MessageParam.Role.USER, param.role());

        List<ContentBlockParam> blocks = param.content().asBlockParams();
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).isToolResult());

        ToolResultBlockParam toolResult = blocks.get(0).asToolResult();
        assertEquals("call_123", toolResult.toolUseId());
        assertTrue(toolResult.content().isPresent());
        assertTrue(toolResult.content().get().isBlocks());
    }

    @Test
    void testConvertToolResultBlockWithTextBlock() {
        TextBlock textBlock = TextBlock.builder().text("Tool output").build();
        Msg msg =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .output(textBlock)
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        MessageParam param = result.get(0);
        assertEquals(MessageParam.Role.USER, param.role());

        List<ContentBlockParam> blocks = param.content().asBlockParams();
        assertTrue(blocks.get(0).isToolResult());
        ToolResultBlockParam toolResult = blocks.get(0).asToolResult();
        assertTrue(toolResult.content().isPresent());
        assertTrue(toolResult.content().get().isBlocks());
    }

    @Test
    void testConvertToolResultBlockMultiBlock() {
        List<ContentBlock> outputBlocks = new ArrayList<>();
        outputBlocks.add(TextBlock.builder().text("First").build());
        outputBlocks.add(TextBlock.builder().text("Second").build());

        Msg msg =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .output((List<ContentBlock>) outputBlocks)
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertTrue(blocks.get(0).isToolResult());
    }

    @Test
    void testConvertParallelToolCallsToAlternatingMessages() {
        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("Check Beijing and Hangzhou weather in parallel.")
                                        .build())
                        .build();
        Msg assistantMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("I will check both cities at the same time.")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("call_2")
                                                .name("get_weather")
                                                .input(Map.of("city", "Hangzhou"))
                                                .build()))
                        .build();
        Msg toolResultsMsg1 =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("Beijing: sunny, 28 C")
                                                                .build())
                                                .build()))
                        .build();
        Msg toolResultsMsg2 =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_2")
                                                .name("get_weather")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("Hangzhou: cloudy, 30 C")
                                                                .build())
                                                .build()))
                        .build();

        List<MessageParam> result =
                converter.convert(List.of(userMsg, assistantMsg, toolResultsMsg1, toolResultsMsg2));

        assertEquals(5, result.size());
        assertEquals(MessageParam.Role.USER, result.get(0).role());
        assertEquals(
                "Check Beijing and Hangzhou weather in parallel.",
                result.get(0).content().asBlockParams().get(0).asText().text());
        assertEquals(MessageParam.Role.ASSISTANT, result.get(1).role());
        assertEquals(2, result.get(1).content().asBlockParams().size());
        assertTrue(result.get(1).content().asBlockParams().get(0).isText());
        assertEquals(
                "I will check both cities at the same time.",
                result.get(1).content().asBlockParams().get(0).asText().text());
        assertTrue(result.get(1).content().asBlockParams().get(1).isToolUse());
        assertEquals("call_1", result.get(1).content().asBlockParams().get(1).asToolUse().id());
        assertEquals(MessageParam.Role.USER, result.get(2).role());
        assertTrue(result.get(2).content().asBlockParams().get(0).isToolResult());
        assertEquals(
                "call_1",
                result.get(2).content().asBlockParams().get(0).asToolResult().toolUseId());
        assertEquals(MessageParam.Role.ASSISTANT, result.get(3).role());
        assertEquals(1, result.get(3).content().asBlockParams().size());
        assertTrue(result.get(3).content().asBlockParams().get(0).isToolUse());
        assertEquals("call_2", result.get(3).content().asBlockParams().get(0).asToolUse().id());
        assertEquals(MessageParam.Role.USER, result.get(4).role());
        assertTrue(result.get(4).content().asBlockParams().get(0).isToolResult());
        assertEquals(
                "call_2",
                result.get(4).content().asBlockParams().get(0).asToolResult().toolUseId());
    }

    @Test
    void testConvertToolResultBlockNullOutput() {
        // Builder without output() call will have null output, which becomes empty list
        Msg msg =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).content().asBlockParams().get(0).isToolResult());
    }

    @Test
    void testConvertMixedContentBlocks() {
        Base64Source imageSource =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/png")
                        .build();

        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Look at this:").build(),
                                        ImageBlock.builder().source(imageSource).build(),
                                        TextBlock.builder().text("What is it?").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertEquals(3, blocks.size());
        assertTrue(blocks.get(0).isText());
        assertTrue(blocks.get(1).isImage());
        assertTrue(blocks.get(2).isText());
    }

    @Test
    @org.junit.jupiter.api.Disabled(
            "Stage 1 Msg.validateRoleContent rejects SYSTEM + ToolResultBlock at construction;"
                    + " this split-message fallback is unreachable. See"
                    + " io.agentscope.core.message.Msg#validateRoleContent.")
    void testConvertMessageWithToolResultAndRegularContent() {
        Msg msg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Note:").build(),
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .output(TextBlock.builder().text("Result").build())
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        // Should split into two messages: regular content + tool result
        assertEquals(2, result.size());

        // First message has regular content
        assertEquals(MessageParam.Role.USER, result.get(0).role());
        List<ContentBlockParam> firstBlocks = result.get(0).content().asBlockParams();
        assertEquals(1, firstBlocks.size());
        assertTrue(firstBlocks.get(0).isText());

        // Second message has tool result
        assertEquals(MessageParam.Role.USER, result.get(1).role());
        List<ContentBlockParam> secondBlocks = result.get(1).content().asBlockParams();
        assertEquals(1, secondBlocks.size());
        assertTrue(secondBlocks.get(0).isToolResult());
    }

    @Test
    void testConvertMultipleMessages() {
        Msg msg1 =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hi").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg1, msg2));

        assertEquals(2, result.size());
        assertEquals(MessageParam.Role.USER, result.get(0).role());
        assertEquals(MessageParam.Role.ASSISTANT, result.get(1).role());
    }

    @Test
    void testExtractSystemMessagePresent() {
        Msg msg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("System prompt").build()))
                        .build();

        String systemMessage = converter.extractSystemMessage(List.of(msg));

        assertEquals("System prompt", systemMessage);
    }

    @Test
    void testExtractSystemMessageMultipleTextBlocks() {
        Msg msg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First").build(),
                                        TextBlock.builder().text("Second").build()))
                        .build();

        String systemMessage = converter.extractSystemMessage(List.of(msg));

        assertEquals("First\nSecond", systemMessage);
    }

    @Test
    void testExtractSystemMessageNotFirst() {
        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        Msg systemMsg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("Note").build()))
                        .build();

        String systemMessage = converter.extractSystemMessage(List.of(userMsg, systemMsg));

        assertNull(systemMessage);
    }

    @Test
    void testExtractSystemMessageEmpty() {
        String systemMessage = converter.extractSystemMessage(List.of());

        assertNull(systemMessage);
    }

    @Test
    void testExtractSystemMessageNonSystemRole() {
        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        String systemMessage = converter.extractSystemMessage(List.of(msg));

        assertNull(systemMessage);
    }

    @Test
    void testConvertEmptyMessage() {
        Msg msg = Msg.builder().name("User").role(MsgRole.USER).content(List.of()).build();

        List<MessageParam> result = converter.convert(List.of(msg));

        // Empty content should return empty result or be filtered
        assertTrue(result.isEmpty() || result.get(0).content().asBlockParams().isEmpty());
    }

    @Test
    void testConvertToolUseBlockWithNullInput() {
        Msg msg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .input(null)
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertTrue(blocks.get(0).isToolUse());
        assertEquals("call_123", blocks.get(0).asToolUse().id());
        assertEquals("search", blocks.get(0).asToolUse().name());
        // Note: null input is converted to empty map during conversion
    }

    @Test
    void testConvertToolRoleMessage() {
        Msg msg =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(List.of(TextBlock.builder().text("Result").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        // TOOL role should be converted to USER
        assertEquals(MessageParam.Role.USER, result.get(0).role());
    }

    // ==================== Server tools ====================

    private static ToolUseBlock serverToolUseBlock(String id) {
        return ToolUseBlock.builder()
                .id(id)
                .name("web_search")
                .input(Map.of("query", "AgentScope"))
                .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                .build();
    }

    private static ToolResultBlock serverToolResultBlock(String id) {
        String rawJson =
                "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\""
                        + id
                        + "\",\"content\":[{\"type\":\"web_search_result\","
                        + "\"url\":\"https://example.com\",\"title\":\"Example\","
                        + "\"encrypted_content\":\"enc_abc123\",\"page_age\":\"2 days\"}]}";
        return ToolResultBlock.builder()
                .id(id)
                .name("web_search")
                .output(TextBlock.builder().text("Example (https://example.com)").build())
                .metadata(
                        Map.of(
                                ToolResultBlock.METADATA_SERVER_TOOL,
                                true,
                                AnthropicResponseParser.METADATA_SERVER_TOOL_RESULT,
                                rawJson))
                .build();
    }

    @Test
    void testServerToolBlocksStayInlineInAssistantMessage() {
        Msg assistantMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        serverToolUseBlock("srvtoolu_01"),
                                        serverToolResultBlock("srvtoolu_01"),
                                        TextBlock.builder().text("Based on the search...").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(assistantMsg));

        // Server tool blocks must NOT be split into separate user messages
        assertEquals(1, result.size());
        MessageParam param = result.get(0);
        assertEquals(MessageParam.Role.ASSISTANT, param.role());

        List<ContentBlockParam> blocks = param.content().asBlockParams();
        assertEquals(3, blocks.size());

        // server_tool_use echoed with original id and input
        assertTrue(blocks.get(0).isServerToolUse());
        assertEquals("srvtoolu_01", blocks.get(0).asServerToolUse().id());

        // web_search_tool_result echoed verbatim from the raw JSON in metadata
        assertTrue(blocks.get(1).isWebSearchToolResult());
        var resultParam = blocks.get(1).asWebSearchToolResult();
        assertEquals("srvtoolu_01", resultParam.toolUseId());
        var items = resultParam.content().asItem();
        assertEquals(1, items.size());
        assertEquals("https://example.com", items.get(0).url());
        assertEquals("Example", items.get(0).title());
        assertEquals("enc_abc123", items.get(0).encryptedContent());
        assertEquals("2 days", items.get(0).pageAge().orElseThrow());

        assertTrue(blocks.get(2).isText());
    }

    @Test
    void testServerToolErrorResultEchoedAsRequestError() {
        String errorJson =
                "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_err\","
                        + "\"content\":{\"type\":\"web_search_tool_result_error\","
                        + "\"error_code\":\"max_uses_exceeded\"}}";
        ToolResultBlock errorResult =
                ToolResultBlock.builder()
                        .id("srvtoolu_err")
                        .name("web_search")
                        .output(TextBlock.builder().text("[ERROR] Web search failed").build())
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        AnthropicResponseParser.METADATA_SERVER_TOOL_RESULT,
                                        errorJson))
                        .build();
        Msg assistantMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        serverToolUseBlock("srvtoolu_err"),
                                        errorResult,
                                        TextBlock.builder().text("Search failed.").build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(assistantMsg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertTrue(blocks.get(1).isWebSearchToolResult());
        var resultParam = blocks.get(1).asWebSearchToolResult();
        assertTrue(resultParam.content().isRequestError());
    }

    @Test
    void testCodeExecutionServerToolResultEchoed() {
        String rawJson =
                "{\"type\":\"code_execution_tool_result\",\"tool_use_id\":\"srvtoolu_code\","
                        + "\"content\":{\"type\":\"code_execution_result\",\"stdout\":\"42\\n\","
                        + "\"stderr\":\"\",\"return_code\":0,\"content\":[]}}";
        ToolResultBlock codeResult =
                ToolResultBlock.builder()
                        .id("srvtoolu_code")
                        .name("code_execution")
                        .output(TextBlock.builder().text("42").build())
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        AnthropicResponseParser.METADATA_SERVER_TOOL_RESULT,
                                        rawJson))
                        .build();
        Msg assistantMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("srvtoolu_code")
                                                .name("code_execution")
                                                .input(Map.of("code", "print(42)"))
                                                .metadata(
                                                        Map.of(
                                                                ToolUseBlock.METADATA_SERVER_TOOL,
                                                                true))
                                                .build(),
                                        codeResult))
                        .build();

        List<MessageParam> result = converter.convert(List.of(assistantMsg));

        assertEquals(1, result.size());
        List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).isServerToolUse());
        assertTrue(blocks.get(1).isCodeExecutionToolResult());
        assertEquals("srvtoolu_code", blocks.get(1).asCodeExecutionToolResult().toolUseId());
    }

    @Test
    void testServerToolBlocksDoNotBreakClientToolResultSplitting() {
        // Assistant message: server tool call + inline result + client tool call
        Msg assistantMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        serverToolUseBlock("srvtoolu_02"),
                                        serverToolResultBlock("srvtoolu_02"),
                                        ToolUseBlock.builder()
                                                .id("call_client")
                                                .name("weather")
                                                .input(Map.of("city", "Hangzhou"))
                                                .build()))
                        .build();
        // Client tool result arrives as separate TOOL message
        Msg toolResultMsg =
                Msg.builder()
                        .name("Tool")
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_client")
                                                .name("weather")
                                                .output(TextBlock.builder().text("Sunny").build())
                                                .build()))
                        .build();

        List<MessageParam> result = converter.convert(List.of(assistantMsg, toolResultMsg));

        assertEquals(2, result.size());

        // Assistant message keeps server tool blocks inline plus the client tool_use
        List<ContentBlockParam> assistantBlocks = result.get(0).content().asBlockParams();
        assertEquals(3, assistantBlocks.size());
        assertTrue(assistantBlocks.get(0).isServerToolUse());
        assertTrue(assistantBlocks.get(1).isWebSearchToolResult());
        assertTrue(assistantBlocks.get(2).isToolUse());
        assertEquals("call_client", assistantBlocks.get(2).asToolUse().id());

        // Client tool result becomes a separate user message with tool_result
        assertEquals(MessageParam.Role.USER, result.get(1).role());
        List<ContentBlockParam> userBlocks = result.get(1).content().asBlockParams();
        assertTrue(userBlocks.get(0).isToolResult());
        assertEquals("call_client", userBlocks.get(0).asToolResult().toolUseId());
    }
}
