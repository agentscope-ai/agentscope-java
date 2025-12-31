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
}
