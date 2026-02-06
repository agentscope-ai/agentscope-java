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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        AnthropicMessage msgParam = result.get(0);
        assertEquals("user", msgParam.getRole());

        List<AnthropicContent> contents = msgParam.getContent();
        assertEquals(1, contents.size());
        assertEquals("text", contents.get(0).getType());
        assertEquals("Hello", contents.get(0).getText());
    }

    @Test
    void testConvertAssistantMessage() {
        Msg msg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hi there").build()))
                        .build();

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("assistant", result.get(0).getRole());
    }

    @Test
    void testConvertSystemMessageFirst() {
        Msg msg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("System prompt").build()))
                        .build();

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        // In AnthropicMessageConverter.convertRole: SYSTEM -> "user"
        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
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

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<AnthropicContent> blocks = result.get(0).getContent();
        assertEquals(2, blocks.size());
        assertEquals("First", blocks.get(0).getText());
        assertEquals("Second", blocks.get(1).getText());
    }

    @Test
    void testConvertImageBlock() throws Exception {
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

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<AnthropicContent> blocks = result.get(0).getContent();
        assertEquals(1, blocks.size());
        assertEquals("image", blocks.get(0).getType());
        // For DTO verification
        assertNotNull(blocks.get(0).getSource());
        assertEquals("base64", blocks.get(0).getSource().getType());
        assertEquals("image/png", blocks.get(0).getSource().getMediaType());
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

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<AnthropicContent> blocks = result.get(0).getContent();
        assertEquals(1, blocks.size());
        assertEquals("thinking", blocks.get(0).getType());
        assertEquals("Let me think...", blocks.get(0).getThinking());
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

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        assertEquals(1, result.size());
        List<AnthropicContent> blocks = result.get(0).getContent();
        assertEquals(1, blocks.size());
        assertEquals("tool_use", blocks.get(0).getType());
        assertEquals("call_123", blocks.get(0).getId());
        assertEquals("search", blocks.get(0).getName());
        assertEquals(input, blocks.get(0).getInput());
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

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        // Tool result creates separate user message
        assertEquals(1, result.size());
        AnthropicMessage param = result.get(0);
        assertEquals("user", param.getRole());

        List<AnthropicContent> blocks = param.getContent();
        assertEquals(1, blocks.size());
        assertEquals("tool_result", blocks.get(0).getType());
        assertEquals("call_123", blocks.get(0).getToolUseId());

        // Check content - it's a List<AnthropicContent> in the new implementation
        // (because ToolResultBlock output is a List)
        Object contentObj = blocks.get(0).getContent();
        assertNotNull(contentObj);
        assertTrue(contentObj instanceof List, "Content should be a List");
        List<?> contentList = (List<?>) contentObj;
        assertEquals(1, contentList.size());
        assertTrue(contentList.get(0) instanceof AnthropicContent);
        AnthropicContent textContent = (AnthropicContent) contentList.get(0);
        assertEquals("text", textContent.getType());
        assertEquals("Result text", textContent.getText());
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

        List<AnthropicMessage> result = converter.convert(List.of(msg));

        // Should split into two messages: regular content + tool result
        assertEquals(2, result.size());

        // First message has regular content
        assertEquals("user", result.get(0).getRole());
        List<AnthropicContent> firstBlocks = result.get(0).getContent();
        assertEquals(1, firstBlocks.size());
        assertEquals("text", firstBlocks.get(0).getType());

        // Second message has tool result
        assertEquals("user", result.get(1).getRole());
        List<AnthropicContent> secondBlocks = result.get(1).getContent();
        assertEquals(1, secondBlocks.size());
        assertEquals("tool_result", secondBlocks.get(0).getType());
        // Verify content structure for tool result (List of 1 TextBlock)
        Object contentObj = secondBlocks.get(0).getContent();
        assertTrue(contentObj instanceof List);
        List<?> contentList = (List<?>) contentObj;
        assertEquals(1, contentList.size());
        AnthropicContent textContent = (AnthropicContent) contentList.get(0);
        assertEquals("Result", textContent.getText());
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

        List<AnthropicMessage> result = converter.convert(List.of(msg1, msg2));

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("assistant", result.get(1).getRole());
    }
}
